package com.study.playground.pipeline.engine.step;

import com.study.playground.adapter.RegistryAdapter;
import com.study.playground.pipeline.domain.PipelineExecution;
import com.study.playground.pipeline.domain.PipelineStep;
import com.study.playground.pipeline.engine.PipelineStepExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 컨테이너 레지스트리에서 이미지의 존재 여부를 확인하는 스텝.
 *
 * <p>실제 Docker pull 대신 레지스트리 API로 이미지 존재를 검증하는 이유는,
 * 파이프라인 서버가 Docker 데몬에 직접 접근하지 않고도 배포 전 이미지를
 * 사전 검증할 수 있기 때문이다. 이미지가 없으면 배포 스텝까지 진행하지 않고
 * 조기에 실패하여 불필요한 작업을 방지한다.
 *
 * <p>이 스텝은 동기 실행이므로 webhook 대기가 필요 없다. 레지스트리 API 조회는
 * 즉시 응답하기 때문이다.
 *
 * <p>SAGA 보상: {@link #compensate}에서 로컬 Docker 데몬에 풀링된 이미지를 제거한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RegistryImagePullStep implements PipelineStepExecutor {

    private final RegistryAdapter registryAdapter;

    /**
     * 레지스트리에서 이미지의 존재를 확인하고 사용 가능한 태그 목록을 기록한다.
     *
     * <p>스텝 이름에 {@code [FAIL]} 마커가 있으면 의도적으로 실패시킨다.
     * 이는 SAGA 보상 흐름을 데모용으로 재현하기 위한 장치다.
     *
     * @param step 실행할 스텝 정보 (스텝 이름에서 이미지 참조를 파싱한다)
     * @throws Exception 레지스트리 연결 불가, 이미지 미존재 시
     */
    @Override
    public void execute(PipelineStep step) throws Exception {
        log.info("[Real] RegistryImagePull 시작: {}", step.getStepName());

        String stepName = step.getStepName();

        // 데모: [FAIL] 마커 - SAGA 데모를 위한 이미지 풀 실패 시뮬레이션
        if (stepName != null && stepName.contains("[FAIL]")) {
            log.error("[Real] FAIL 마커 감지 - 의도적 실패 발생: {}", stepName);
            Thread.sleep(2000);
            throw new RuntimeException("Image pull failed: manifest not found (demo failure)");
        }

        if (!registryAdapter.isAvailable()) {
            throw new RuntimeException("Registry 연결 불가: " + step.getStepName());
        }

        // 형식: "Pull: repo/image:tag"
        ImageRef ref = parseImageRef(step.getStepName());
        log.info("[Real] Registry 이미지 확인: {}:{}", ref.name, ref.tag);

        boolean exists = registryAdapter.imageExists(ref.name, ref.tag);
        if (!exists) {
            throw new RuntimeException(String.format(
                    "Registry에서 이미지를 찾을 수 없음: %s:%s", ref.name, ref.tag));
        }

        List<String> tags = registryAdapter.getTags(ref.name);
        String tagSummary = tags.isEmpty() ? ref.tag : String.join(", ", tags);

        step.setLog(String.format(
                "Image %s:%s confirmed in registry | available tags: [%s]",
                ref.name, ref.tag, tagSummary));
        log.info("[Real] RegistryImagePull 완료: {}", step.getLog());
    }

    /**
     * 스텝 이름에서 이미지 참조(이름과 태그)를 파싱한다.
     *
     * <p>예상 형식: {@code "Pull: repo/image:tag"} 또는 {@code "Pull: image:tag"}.
     * {@code lastIndexOf(':')}를 사용하는 이유는 {@code repo/image:tag}처럼 슬래시가
     * 포함된 경우에도 태그 구분자인 마지막 콜론을 정확히 찾기 위해서다.
     * 파싱 실패 시 기본 이미지로 폴백한다.
     *
     * @param stepName 스텝 이름 문자열
     * @return 파싱된 이미지 참조, 파싱 실패 시 기본값 반환
     */
    private ImageRef parseImageRef(String stepName) {
        // 예상 형식: "Pull: repo/image:tag" 또는 "Pull: image:tag"
        String raw = stepName;
        if (stepName != null && stepName.contains("Pull:")) {
            raw = stepName.substring(stepName.indexOf("Pull:") + 5).trim();
        }
        if (raw != null && raw.contains(":")) {
            int colonIdx = raw.lastIndexOf(':');
            String name = raw.substring(0, colonIdx).trim();
            String tag = raw.substring(colonIdx + 1).trim();
            if (!name.isBlank() && !tag.isBlank()) {
                return new ImageRef(name, tag);
            }
        }
        return new ImageRef("playground/app", "latest");
    }

    /**
     * SAGA 보상: 로컬 Docker 데몬에 풀링된 이미지를 제거한다.
     * 현재는 로그만 남기며, 프로덕션에서는 실제 이미지 제거 로직을 구현해야 한다.
     *
     * @param execution 파이프라인 실행 컨텍스트
     * @param step      보상할 스텝 정보
     */
    @Override
    public void compensate(PipelineExecution execution, PipelineStep step) throws Exception {
        log.info("[SAGA COMPENSATE] Rolling back {}: stepName={}, executionId={}",
                "image pull",
                step.getStepName(), execution.getId());
        // 프로덕션: 로컬 Docker 데몬에서 풀링된 이미지 제거
        // 예: docker rmi <image>:<tag>
    }

    /** 컨테이너 이미지의 이름과 태그를 나타내는 값 객체. */
    private record ImageRef(String name, String tag) {}
}
