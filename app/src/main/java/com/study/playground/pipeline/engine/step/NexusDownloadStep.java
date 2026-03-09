package com.study.playground.pipeline.engine.step;

import com.study.playground.adapter.NexusAdapter;
import com.study.playground.adapter.dto.NexusAsset;
import com.study.playground.pipeline.domain.PipelineExecution;
import com.study.playground.pipeline.domain.PipelineStep;
import com.study.playground.pipeline.engine.PipelineStepExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Nexus Repository에서 Maven 아티팩트를 다운로드하는 스텝.
 *
 * <p>스텝 이름에서 Maven 좌표(groupId:artifactId:version:packaging)를 파싱하여
 * Nexus REST API로 아티팩트를 검색하고 다운로드한다. 파싱 실패 시 안전한 기본값으로
 * 폴백하므로 잘못된 스텝 이름도 예외 없이 처리된다.
 *
 * <p>이 스텝은 동기 실행이므로 webhook 대기가 필요 없다. Nexus API가 즉시 응답하기
 * 때문이다. Jenkins처럼 비동기 빌드를 기다리는 스텝과 달리 {@code waitingForWebhook}을
 * 설정하지 않는다.
 *
 * <p>SAGA 보상: {@link #compensate}에서 로컬/공유 저장소에 저장된 아티팩트를 삭제한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NexusDownloadStep implements PipelineStepExecutor {

    /** 기본 다운로드 대상 저장소. 릴리즈 아티팩트만 배포에 사용하므로 스냅샷이 아닌 releases를 기본으로 한다. */
    private static final String DEFAULT_REPOSITORY = "maven-releases";

    private final NexusAdapter nexusAdapter;

    /**
     * Nexus에서 아티팩트를 검색하고 다운로드한다.
     *
     * <p>스텝 이름에 {@code [FAIL]} 마커가 있으면 의도적으로 실패시킨다.
     * 이는 SAGA 보상 흐름을 데모용으로 재현하기 위한 장치다.
     *
     * @param step 실행할 스텝 정보 (스텝 이름에서 Maven 좌표를 파싱한다)
     * @throws Exception Nexus 연결 불가, 아티팩트 미존재, 다운로드 실패 시
     */
    @Override
    public void execute(PipelineStep step) throws Exception {
        log.info("[Real] NexusDownload 시작: {}", step.getStepName());

        String stepName = step.getStepName();

        // 데모: [FAIL] 마커 - SAGA 데모를 위한 다운로드 실패 시뮬레이션
        if (stepName != null && stepName.contains("[FAIL]")) {
            log.error("[Real] FAIL 마커 감지 - 의도적 실패 발생: {}", stepName);
            Thread.sleep(2000);
            throw new RuntimeException("Artifact download failed: connection timeout to Nexus (demo failure)");
        }

        if (!nexusAdapter.isAvailable()) {
            throw new RuntimeException("Nexus 연결 불가: " + step.getStepName());
        }

        // 형식: "Download: groupId:artifactId:version:packaging"
        ArtifactCoordinate coord = parseCoordinate(step.getStepName());
        log.info("[Real] Nexus 아티팩트 검색: {}", coord);

        List<NexusAsset> items = nexusAdapter.searchComponents(
                DEFAULT_REPOSITORY,
                coord.groupId,
                coord.artifactId,
                coord.version);

        if (items.isEmpty()) {
            throw new RuntimeException(String.format(
                    "Nexus에서 아티팩트를 찾을 수 없음: %s:%s:%s (repository=%s)",
                    coord.groupId, coord.artifactId, coord.version, DEFAULT_REPOSITORY));
        }

        // 첫 번째 매칭 에셋의 다운로드 URL 사용
        NexusAsset asset = items.get(0);
        String downloadUrl = asset.downloadUrl();

        if (downloadUrl == null || downloadUrl.isBlank()) {
            throw new RuntimeException("Nexus 아티팩트 downloadUrl 없음: " + asset);
        }

        long startMs = System.currentTimeMillis();
        byte[] content = nexusAdapter.downloadArtifact(downloadUrl);
        long elapsed = System.currentTimeMillis() - startMs;

        if (content == null) {
            throw new RuntimeException("Nexus 아티팩트 다운로드 실패: url=" + downloadUrl);
        }

        step.setLog(String.format(
                "Downloaded %s:%s:%s | size=%d bytes | elapsed=%dms | url=%s",
                coord.groupId, coord.artifactId, coord.version,
                content.length, elapsed, downloadUrl));
        log.info("[Real] NexusDownload 완료: {}", step.getLog());
    }

    /**
     * 스텝 이름에서 Maven 아티팩트 좌표를 파싱한다.
     *
     * <p>예상 형식: {@code "Download: com.example:my-app:1.0.0:jar"}.
     * 형식이 맞지 않으면 테스트 환경에서도 실행 가능하도록 안전한 기본값으로 폴백한다.
     *
     * @param stepName 스텝 이름 문자열
     * @return 파싱된 Maven 좌표, 파싱 실패 시 기본값 반환
     */
    private ArtifactCoordinate parseCoordinate(String stepName) {
        // 예상 형식: "Download: com.example:my-app:1.0.0:jar"
        // 형식이 맞지 않으면 안전한 기본값으로 폴백
        if (stepName != null && stepName.contains(":")) {
            String raw = stepName.contains("Download:")
                    ? stepName.substring(stepName.indexOf("Download:") + 9).trim()
                    : stepName;
            String[] parts = raw.split(":");
            if (parts.length >= 3) {
                String packaging = parts.length >= 4 ? parts[3].trim() : "jar";
                return new ArtifactCoordinate(
                        parts[0].trim(),
                        parts[1].trim(),
                        parts[2].trim(),
                        packaging);
            }
        }
        return new ArtifactCoordinate("com.study", "playground-artifact", "1.0.0", "jar");
    }

    /**
     * SAGA 보상: 다운로드된 아티팩트를 로컬/공유 저장소에서 삭제한다.
     * 현재는 로그만 남기며, 프로덕션에서는 실제 파일 삭제 로직을 구현해야 한다.
     *
     * @param execution 파이프라인 실행 컨텍스트
     * @param step      보상할 스텝 정보
     */
    @Override
    public void compensate(PipelineExecution execution, PipelineStep step) throws Exception {
        log.info("[SAGA COMPENSATE] Rolling back {}: stepName={}, executionId={}",
                "artifact download",
                step.getStepName(), execution.getId());
        // 프로덕션: 로컬/공유 저장소에서 다운로드된 아티팩트 삭제
        // 예: Files.deleteIfExists(localArtifactPath)
    }

    /** Maven 아티팩트 좌표를 나타내는 값 객체. 불변성을 보장하기 위해 record로 정의한다. */
    private record ArtifactCoordinate(String groupId, String artifactId, String version, String packaging) {
        @Override
        public String toString() {
            return groupId + ":" + artifactId + ":" + version + ":" + packaging;
        }
    }
}
