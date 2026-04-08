package com.study.playground.operatorstub.api;

import com.study.playground.operatorstub.domain.OperatorJob;
import com.study.playground.operatorstub.domain.OperatorJobRepository;
import com.study.playground.operatorstub.domain.OperatorJobStatus;
import com.study.playground.operatorstub.fixture.TestPipelineFixtures;
import com.study.playground.operatorstub.fixture.TestPipelineFixtures.TestJob;
import com.study.playground.operatorstub.fixture.TestPipelineFixtures.TestPipeline;
import com.study.playground.operatorstub.publisher.JobDispatchPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/operator")
@RequiredArgsConstructor
@Slf4j
public class OperatorStubController {

    private final OperatorJobRepository operatorJobRepository;
    private final JobDispatchPublisher jobDispatchPublisher;

    /**
     * 파이프라인 실행 트리거.
     * POST /api/operator/pipelines/{pipelineId}/execute
     */
    @PostMapping("/pipelines/{pipelineId}/execute")
    @Transactional
    public ResponseEntity<Map<String, Object>> executePipeline(@PathVariable String pipelineId) {
        TestPipeline pipeline = TestPipelineFixtures.findById(pipelineId);
        String executionPipelineId = UUID.randomUUID().toString();

        List<OperatorJob> jobs = pipeline.jobs().stream()
                .map(testJob -> {
                    OperatorJob job = OperatorJob.create(
                            executionPipelineId
                            , testJob.jobId()
                            , pipelineId
                            , testJob.jobName()
                            , testJob.jobOrder()
                            , testJob.jenkinsInstanceId()
                            , testJob.configJson()
                    );
                    return operatorJobRepository.save(job);
                })
                .toList();

        // 순차 실행: 첫 번째 Job만 디스패치
        OperatorJob firstJob = jobs.stream()
                .min(Comparator.comparingInt(OperatorJob::getJobOrder))
                .orElseThrow();
        jobDispatchPublisher.publishJobDispatch(firstJob);
        firstJob.updateStatus(OperatorJobStatus.QUEUING);
        operatorJobRepository.save(firstJob);

        log.info("[Operator] Pipeline execution started: pipelineId={}, executionPipelineId={}, jobs={}"
                , pipelineId, executionPipelineId, jobs.size());

        return ResponseEntity.ok(Map.of(
                "executionPipelineId", executionPipelineId
                , "jobCount", jobs.size()
        ));
    }

    /**
     * 단독 Job 실행 트리거.
     * POST /api/operator/jobs/execute?jobName=executor-test&jenkinsInstanceId=1
     */
    @PostMapping("/jobs/execute")
    @Transactional
    public ResponseEntity<Map<String, Object>> executeSingleJob(
            @RequestParam String jobName
            , @RequestParam(defaultValue = "1") long jenkinsInstanceId
    ) {
        TestJob matched = TestPipelineFixtures.findJobByName(jobName);
        OperatorJob job = OperatorJob.create(
                null  // 단독 Job: executionPipelineId = null
                , matched.jobId()
                , null
                , jobName
                , 1
                , jenkinsInstanceId
                , null
        );
        job = operatorJobRepository.save(job);

        jobDispatchPublisher.publishJobDispatch(job);
        job.updateStatus(OperatorJobStatus.QUEUING);
        operatorJobRepository.save(job);

        log.info("[Operator] Single job execution started: jobName={}, id={}"
                , jobName, job.getId());

        return ResponseEntity.ok(Map.of(
                "operatorJobId", job.getId()
                , "jobName", jobName
        ));
    }

    /**
     * Job 상태 조회.
     */
    @GetMapping("/jobs")
    public ResponseEntity<List<OperatorJob>> listJobs(
            @RequestParam(required = false) String executionPipelineId
    ) {
        if (executionPipelineId != null) {
            return ResponseEntity.ok(
                    operatorJobRepository.findByExecutionPipelineId(executionPipelineId));
        }
        return ResponseEntity.ok(operatorJobRepository.findAll());
    }

    /**
     * 사용 가능한 테스트 파이프라인 목록.
     */
    @GetMapping("/pipelines")
    public ResponseEntity<List<TestPipeline>> listPipelines() {
        return ResponseEntity.ok(TestPipelineFixtures.all());
    }
}
