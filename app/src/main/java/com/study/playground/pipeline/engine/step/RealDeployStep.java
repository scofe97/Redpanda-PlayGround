package com.study.playground.pipeline.engine.step;

import com.study.playground.adapter.JenkinsAdapter;
import com.study.playground.pipeline.domain.PipelineExecution;
import com.study.playground.pipeline.domain.PipelineStep;
import com.study.playground.pipeline.engine.PipelineStepExecutor;
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
 * <p>커맨드 발행 후 {@code step.setWaitingForWebhook(true)}를 설정하여 엔진이
 * 스레드를 해제하도록 신호를 보낸다. Jenkins 빌드 완료 시 webhook이 도착하면
 * {@code PipelineEngine.resumeAfterWebhook}에서 다음 스텝이 재개된다.
 *
 * <p>SAGA 보상: {@link #compensate}에서 롤백 Jenkins Job을 트리거하거나 undeploy API를 호출한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RealDeployStep implements PipelineStepExecutor {

    /** 기본 Jenkins 배포 Job 이름. 스텝 이름에서 별도 지정이 없으면 이 Job을 사용한다. */
    private static final String DEFAULT_JOB = "playground-deploy";

    private final JenkinsAdapter jenkinsAdapter;
    private final PipelineCommandProducer commandProducer;

    /**
     * 이 스텝은 {@link PipelineExecution} 컨텍스트가 필수이므로 단일 파라미터 오버로드를 지원하지 않는다.
     * 엔진은 항상 {@link #execute(PipelineExecution, PipelineStep)}를 호출하므로
     * 이 메서드가 직접 호출되는 경우는 프로그래밍 오류다.
     *
     * @throws RuntimeException 항상 던짐 — 잘못된 호출 경로 감지용
     */
    @Override
    public void execute(PipelineStep step) throws Exception {
        throw new RuntimeException("RealDeployStep requires PipelineExecution context");
    }

    /**
     * Jenkins 배포 Job을 Kafka 커맨드로 트리거하고 webhook 대기 상태로 전환한다.
     *
     * <p>스텝 이름에서 배포 대상 정보를 추출하여 Jenkins Job 파라미터로 전달한다.
     * {@code [SLOW]} 마커는 SSE 실시간 스트리밍 데모를 위해 의도적으로 지연을 추가한다.
     * {@code [FAIL]} 마커는 DLQ(Dead Letter Queue) 데모를 위해 의도적으로 실패시킨다.
     *
     * @param execution 파이프라인 실행 컨텍스트 (실행 ID를 Jenkins 파라미터로 전달)
     * @param step      실행할 스텝 정보 (스텝 이름에서 배포 대상을 파싱)
     * @throws Exception Jenkins 연결 불가, 커맨드 발행 실패 시
     */
    @Override
    public void execute(PipelineExecution execution, PipelineStep step) throws Exception {
        log.info("[Real] Deploy 시작: {}", step.getStepName());

        String stepName = step.getStepName();

        // 데모: [SLOW] 마커 - SSE 실시간 데모를 위한 느린 배포 시뮬레이션
        if (stepName != null && stepName.contains("[SLOW]")) {
            log.info("[Real] SLOW 모드 감지 - 10초 대기");
            Thread.sleep(10000);
        }

        // 데모: [FAIL] 마커 - DLQ 데모를 위한 배포 실패 시뮬레이션
        if (stepName != null && stepName.contains("[FAIL]")) {
            log.error("[Real] FAIL 마커 감지 - 의도적 실패 발생");
            throw new RuntimeException("Deployment failed: connection refused to target server (demo failure)");
        }

        if (!jenkinsAdapter.isAvailable()) {
            throw new RuntimeException("Jenkins 연결 불가: " + step.getStepName());
        }

        String jobName = DEFAULT_JOB;

        // 스텝 이름에서 배포 대상 정보 추출: "Deploy: egov-sample (main), my-image:latest"
        String deployTarget = "";
        if (stepName != null && stepName.contains("Deploy:")) {
            deployTarget = stepName.substring(stepName.indexOf("Deploy:") + 7).trim();
            deployTarget = deployTarget.replace("[FAIL]", "").replace("[SLOW]", "").trim();
        }

        Map<String, String> params = new HashMap<>();
        params.put("EXECUTION_ID", step.getExecutionId().toString());
        params.put("STEP_ORDER", String.valueOf(step.getStepOrder()));
        if (!deployTarget.isEmpty()) {
            params.put("DEPLOY_TARGET", deployTarget);
            log.info("[Real] Deploy 대상: {}", deployTarget);
        }

        commandProducer.publishJenkinsBuildCommand(execution, step, jobName, params);
        log.info("[Real] Jenkins 배포 커맨드 발행 완료 (via Kafka): job={}", jobName);
        step.setWaitingForWebhook(true);
    }

    /**
     * SAGA 보상: 배포된 서비스를 이전 버전으로 롤백한다.
     * 현재는 로그만 남기며, 프로덕션에서는 롤백 Jenkins Job 트리거 또는 undeploy API를 구현해야 한다.
     *
     * @param execution 파이프라인 실행 컨텍스트
     * @param step      보상할 스텝 정보
     */
    @Override
    public void compensate(PipelineExecution execution, PipelineStep step) throws Exception {
        log.info("[SAGA COMPENSATE] Rolling back {}: stepName={}, executionId={}",
                "deployment",
                step.getStepName(), execution.getId());
        // 프로덕션: 외부 시스템을 호출하여 배포 롤백
        // 예: 롤백 Jenkins 작업 트리거 또는 undeploy API 호출
    }
}
