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

@Slf4j
@Component
@RequiredArgsConstructor
public class NexusDownloadStep implements PipelineStepExecutor {

    private static final String DEFAULT_REPOSITORY = "maven-releases";

    private final NexusAdapter nexusAdapter;

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
                DEFAULT_REPOSITORY, coord.groupId, coord.artifactId, coord.version);

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
                return new ArtifactCoordinate(parts[0].trim(), parts[1].trim(), parts[2].trim(), packaging);
            }
        }
        return new ArtifactCoordinate("com.study", "playground-artifact", "1.0.0", "jar");
    }

    @Override
    public void compensate(PipelineExecution execution, PipelineStep step) throws Exception {
        log.info("[SAGA COMPENSATE] Rolling back {}: stepName={}, executionId={}",
                "artifact download",
                step.getStepName(), execution.getId());
        // 프로덕션: 로컬/공유 저장소에서 다운로드된 아티팩트 삭제
        // 예: Files.deleteIfExists(localArtifactPath)
    }

    private record ArtifactCoordinate(String groupId, String artifactId, String version, String packaging) {
        @Override
        public String toString() {
            return groupId + ":" + artifactId + ":" + version + ":" + packaging;
        }
    }
}
