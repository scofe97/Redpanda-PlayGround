package com.study.playground.pipeline.job.application;

import com.study.playground.pipeline.job.domain.model.JenkinsJobSpec;
import com.study.playground.pipeline.job.domain.port.in.CreateJenkinsJobUseCase;
import com.study.playground.pipeline.job.domain.port.out.JenkinsApiPort;
import com.study.playground.pipeline.job.domain.port.out.LoadJobDependenciesPort;
import com.study.playground.pipeline.job.domain.port.out.LoadJobDependenciesPort.JobDependencies;
import com.study.playground.pipeline.job.domain.service.JenkinsPathBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class CreateJenkinsJobService implements CreateJenkinsJobUseCase {

    private final LoadJobDependenciesPort loadPort;
    private final JenkinsApiPort jenkinsApiPort;
    private final JenkinsPathBuilder pathBuilder;

    @Override
    public void create(String jobId, String presetId) {
        JobDependencies deps = loadPort.load(jobId, presetId);

        JenkinsJobSpec spec = pathBuilder.buildSpec(
                deps.projectId(), deps.presetId(), deps.jobId()
                , deps.jenkinsUrl(), deps.username(), deps.credential()
                , deps.jenkinsScript()
        );

        if (!jenkinsApiPort.exists(spec, spec.projectFolderApiPath())) {
            jenkinsApiPort.createFolder(spec, "", spec.projectId());
            log.info("[JenkinsJob] Project folder created: {}", spec.projectId());
        }

        if (!jenkinsApiPort.exists(spec, spec.presetFolderApiPath())) {
            jenkinsApiPort.createFolder(spec, spec.projectFolderApiPath(), spec.presetId());
            log.info("[JenkinsJob] Preset folder created: {}/{}", spec.projectId(), spec.presetId());
        }

        jenkinsApiPort.createPipelineJob(spec, spec.presetFolderApiPath(), spec.jobId());
        log.info("[JenkinsJob] Pipeline job created: {}", spec.toPath());
    }
}
