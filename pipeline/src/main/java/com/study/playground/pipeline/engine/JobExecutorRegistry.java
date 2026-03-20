package com.study.playground.pipeline.engine;

import com.study.playground.pipeline.domain.PipelineJobType;
import com.study.playground.pipeline.engine.step.JenkinsCloneAndBuildStep;
import com.study.playground.pipeline.engine.step.JenkinsDeployStep;
import com.study.playground.pipeline.engine.step.NexusDownloadStep;
import com.study.playground.pipeline.engine.step.RegistryImagePullStep;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * PipelineJobType → PipelineJobExecutor 매핑을 관리한다.
 *
 * <p>PipelineEngine의 기존 Map&lt;PipelineJobType, Executor&gt;와 별도로 존재하는 이유:
 * JobType은 사용자 구성 레벨(BUILD, DEPLOY)이고, 엔진 실행 레벨에서는
 * 각 JobType에 대응하는 실행기가 1:1이 아닐 수 있으므로 별도 레지스트리가 필요하다.</p>
 *
 * <p>현재는 1:1 매핑이지만, 향후 하나의 JobType이 여러 실행을 생성하는 확장에 대비한다.</p>
 */
@Component
public class JobExecutorRegistry {

    private final Map<PipelineJobType, PipelineJobExecutor> executors;

    public JobExecutorRegistry(
            JenkinsCloneAndBuildStep gitCloneAndBuild,
            NexusDownloadStep nexusDownload,
            RegistryImagePullStep imagePull,
            JenkinsDeployStep deploy) {
        this.executors = Map.of(
                PipelineJobType.BUILD, gitCloneAndBuild,
                PipelineJobType.DEPLOY, deploy,
                PipelineJobType.IMPORT, gitCloneAndBuild, // placeholder: IMPORT 전용 executor 미구현
                PipelineJobType.ARTIFACT_DOWNLOAD, nexusDownload,
                PipelineJobType.IMAGE_PULL, imagePull
        );
    }

    /**
     * JobType에 대응하는 실행기를 반환한다.
     *
     * @param jobType Job 타입
     * @return 실행기, 없으면 null
     */
    public PipelineJobExecutor getExecutor(PipelineJobType jobType) {
        return executors.get(jobType);
    }

    /**
     * JobType 기반 실행기 맵의 복사본을 반환한다.
     * SagaCompensator가 JobType 기반으로 보상을 수행하므로 필요하다.
     */
    public Map<PipelineJobType, PipelineJobExecutor> asJobTypeMap() {
        return Map.copyOf(executors);
    }
}
