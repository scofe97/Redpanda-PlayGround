package com.study.playground.pipeline.engine.step;

import com.study.playground.adapter.RegistryAdapter;
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
public class RegistryImagePullStep implements PipelineStepExecutor {

    private final RegistryAdapter registryAdapter;

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

    @Override
    public void compensate(PipelineExecution execution, PipelineStep step) throws Exception {
        log.info("[SAGA COMPENSATE] Rolling back {}: stepName={}, executionId={}",
                "image pull",
                step.getStepName(), execution.getId());
        // 프로덕션: 로컬 Docker 데몬에서 풀링된 이미지 제거
        // 예: docker rmi <image>:<tag>
    }

    private record ImageRef(String name, String tag) {}
}
