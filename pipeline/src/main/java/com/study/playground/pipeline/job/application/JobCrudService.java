package com.study.playground.pipeline.job.application;

import com.study.playground.pipeline.job.domain.model.Job;
import com.study.playground.pipeline.job.domain.model.JobCategory;
import com.study.playground.pipeline.job.domain.model.JobType;
import com.study.playground.pipeline.job.domain.port.in.CreateJobUseCase;
import com.study.playground.pipeline.job.domain.port.in.DeleteJobUseCase;
import com.study.playground.pipeline.job.domain.port.out.LoadJobPort;
import com.study.playground.pipeline.job.domain.port.out.SaveJobPort;
import com.study.playground.pipeline.job.domain.service.JobService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class JobCrudService implements CreateJobUseCase, DeleteJobUseCase {

    private final LoadJobPort loadPort;
    private final SaveJobPort savePort;
    private final JobService jobService;

    @Override
    @Transactional
    public Job create(String projectId, String presetId, JobCategory category, JobType type, String rgtrId) {
        var jobId = UUID.randomUUID().toString().substring(0, 20);
        var job = Job.create(jobId, projectId, presetId, category, type, rgtrId);
        var saved = savePort.save(job);
        log.info("[Job] Created: jobId={}, category={}, type={}", saved.getJobId(), category, type);
        return saved;
    }

    @Override
    @Transactional
    public void delete(String jobId) {
        var job = loadPort.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));
        jobService.validateDeletable(job);
        job.softDelete();
        savePort.save(job);
        log.info("[Job] Deleted: jobId={}", jobId);
    }
}
