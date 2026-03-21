package com.study.playground.pipeline.engine.step;

import com.study.playground.pipeline.adapter.JenkinsAdapter;
import com.study.playground.pipeline.domain.PipelineExecution;
import com.study.playground.pipeline.domain.PipelineJobExecution;
import com.study.playground.pipeline.engine.PipelineJobExecutor;
import com.study.playground.pipeline.event.PipelineCommandProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 실제 배포를 Jenkins Job으로 위임하고 webhook 완료를 대기하는 스텝.
 *
 * <p>배포를 파이프라인 서버가 직접 수행하지 않고 Jenkins에 위임하는 이유는,
 * Jenkins가 SSH 접근, 헬스체크, 롤백 스크립트 등 배포 인프라와의 통합을 이미
 * 갖추고 있기 때문이다. 파이프라인 서버는 오케스트레이터 역할만 담당한다.
 *
 * <p>Jenkins Job 트리거는 Kafka 커맨드 메시지로 발행한다. 직접 HTTP 호출 대신
 * Kafka를 사용하는 이유는 Jenkins 일시적 장애 시에도 커맨드가 유실되지 않고
 * 재처리될 수 있기 때문이다.
 *
 * <p>커맨드 발행 후 {@code jobExecution.setWaitingForWebhook(true)}를 설정하여 엔진이
 * 스레드를 해제하도록 신호를 보낸다. Jenkins 빌드 완료 시 webhook이 도착하면
 * {@code PipelineEngine.resumeAfterWebhook}에서 다음 Job이 재개된다.
 *
 * <p>SAGA 보상: {@link #compensate}에서 롤백 Jenkins Job을 트리거하거나 undeploy API를 호출한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JenkinsDeployStep implements PipelineJobExecutor {

    /** 기본 Jenkins 배포 Job 이름. Job 이름에서 별도 지정이 없으면 이 Job을 사용한다. */
    private static final String DEFAULT_JOB = "playground-deploy";

    private final JenkinsAdapter jenkinsAdapter;
    private final PipelineCommandProducer commandProducer;

    /**
     * Jenkins 배포 Job을 Kafka 커맨드로 트리거하고 webhook 대기 상태로 전환한다.
     *
     * <p>Job 이름에서 배포 대상 정보를 추출하여 Jenkins Job 파라미터로 전달한다.
     *
     * @param execution    파이프라인 실행 컨텍스트 (실행 ID를 Jenkins 파라미터로 전달)
     * @param jobExecution 실행할 Job 정보 (Job 이름에서 배포 대상을 파싱)
     * @throws Exception Jenkins 연결 불가, 커맨드 발행 실패 시
     */
    @Override
    public void execute(PipelineExecution execution, PipelineJobExecution jobExecution) throws Exception {
        log.info("[Deploy] 시작: {}", jobExecution.getJobName());

        var jobName = jobExecution.getJobName();

        if (!jenkinsAdapter.isAvailable()) {
            throw new RuntimeException("Jenkins 연결 불가: " + jobExecution.getJobName());
        }

        // per-Job Jenkins 파이프라인이 있으면 폴더 경로 포함, 없으면 범용 배포 Job 사용
        String jenkinsJobName;
        if (jobExecution.getJobId() != null) {
            var folderName = jobExecution.getJobType().toFolderName();
            jenkinsJobName = "%s/job/playground-job-%d".formatted(folderName, jobExecution.getJobId());
        } else {
            jenkinsJobName = DEFAULT_JOB;
        }

        // Job 이름에서 배포 대상 정보 추출: "Deploy: egov-sample (main), my-image:latest"
        var deployTarget = "";
        if (jobName != null && jobName.contains("Deploy:")) {
            deployTarget = jobName.substring(jobName.indexOf("Deploy:") + 7).trim();
        }

        var params = new HashMap<String, String>();
        params.put("EXECUTION_ID", jobExecution.getExecutionId().toString());
        params.put("STEP_ORDER", String.valueOf(jobExecution.getJobOrder()));
        if (!deployTarget.isEmpty()) {
            params.put("DEPLOY_TARGET", deployTarget);
            log.info("[Deploy] 대상: {}", deployTarget);
        }

        // 사용자 파라미터 병합 (시스템 파라미터가 우선 — putIfAbsent로 사용자 값 추가)
        if (jobExecution.getUserParams() != null) {
            jobExecution.getUserParams().forEach(params::putIfAbsent);
        }

        commandProducer.publishJenkinsBuildCommand(execution, jobExecution, jenkinsJobName, params);
        log.info("[Deploy] Jenkins 배포 커맨드 발행 완료 (via Kafka): job={}", jenkinsJobName);
        jobExecution.setWaitingForWebhook(true);
    }

    /**
     * SAGA 보상: 배포된 서비스를 이전 버전으로 롤백한다.
     * 현재는 로그만 남기며, 프로덕션에서는 롤백 Jenkins Job 트리거 또는 undeploy API를 구현해야 한다.
     *
     * @param execution    파이프라인 실행 컨텍스트
     * @param jobExecution 보상할 Job 정보
     */
    @Override
    public void compensate(PipelineExecution execution, PipelineJobExecution jobExecution) throws Exception {
        log.info("[SAGA COMPENSATE] Rolling back deployment: jobName={}, executionId={}",
                jobExecution.getJobName(), execution.getId());
        // 프로덕션: 외부 시스템을 호출하여 배포 롤백
        // 예: 롤백 Jenkins 작업 트리거 또는 undeploy API 호출
    }
}
