package com.study.playground.pipeline.engine.step;

import com.study.playground.pipeline.adapter.JenkinsAdapter;
import com.study.playground.pipeline.domain.PipelineExecution;
import com.study.playground.pipeline.domain.PipelineJobExecution;
import com.study.playground.pipeline.engine.PipelineJobExecutor;
import com.study.playground.pipeline.event.PipelineCommandProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;

/**
 * Git 저장소 클론과 빌드를 Jenkins Job으로 위임하고 webhook 완료를 대기하는 스텝.
 *
 * <p>GIT_CLONE과 BUILD 스텝 타입을 하나의 실행기가 처리하는 이유는 Jenkins Pipeline이
 * 클론과 빌드를 단일 작업(Job)으로 실행하기 때문이다. 두 스텝을 분리하면 오히려
 * Jenkins Job을 두 번 트리거해야 하는 비효율이 생긴다.
 *
 * <p>Jenkins Job 트리거는 Kafka 커맨드 메시지로 발행한다. 직접 HTTP 호출 대신
 * Kafka를 사용하는 이유는 Jenkins 일시적 장애 시에도 커맨드가 유실되지 않고
 * 재처리될 수 있기 때문이다.
 *
 * <p>커맨드 발행 후 {@code jobExecution.setWaitingForWebhook(true)}를 설정하여 엔진이
 * 스레드를 해제하도록 신호를 보낸다. Jenkins 빌드 완료 시 webhook이 도착하면
 * {@code PipelineEngine.resumeAfterWebhook}에서 다음 Job이 재개된다.
 *
 * <p>SAGA 보상: {@link #compensate}에서 Jenkins 워크스페이스를 정리한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JenkinsCloneAndBuildStep implements PipelineJobExecutor {

    /** 기본 Jenkins 빌드 Job 이름. Job 이름에서 별도 지정이 없으면 이 Job을 사용한다. */
    private static final String DEFAULT_JOB = "playground-build";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final JenkinsAdapter jenkinsAdapter;
    private final PipelineCommandProducer commandProducer;

    /**
     * Jenkins 빌드 Job을 Kafka 커맨드로 트리거하고 webhook 대기 상태로 전환한다.
     *
     * <p>Job 이름에서 Git URL과 브랜치를 파싱하여 Jenkins Job 파라미터로 전달한다.
     * {@code localhost:29180}을 {@code gitlab:29180}으로 변환하는 이유는 Jenkins가
     * Docker 네트워크 내부에서 실행되므로 호스트 머신 주소 대신 컨테이너 서비스명을
     * 사용해야 GitLab에 접근할 수 있기 때문이다.
     *
     * @param execution    파이프라인 실행 컨텍스트 (실행 ID를 Jenkins 파라미터로 전달)
     * @param jobExecution 실행할 Job 정보 (Job 이름에서 Git URL과 브랜치를 파싱)
     * @throws Exception Jenkins 연결 불가, 커맨드 발행 실패 시
     */
    @Override
    public void execute(PipelineExecution execution, PipelineJobExecution jobExecution) throws Exception {
        log.info("[Real] JenkinsCloneAndBuild 시작: {}", jobExecution.getJobName());

        String jobName = jobExecution.getJobName();

        if (!jenkinsAdapter.isAvailable()) {
            throw new RuntimeException("Jenkins 연결 불가: " + jobExecution.getJobName());
        }

        // per-Job Jenkins 파이프라인이 있으면 폴더 경로 포함, 없으면 범용 빌드 Job 사용
        String jenkinsJobName;
        if (jobExecution.getJobId() != null) {
            String folderName = jobExecution.getJobType().toFolderName();
            jenkinsJobName = "%s/job/playground-job-%d".formatted(folderName, jobExecution.getJobId());
        } else {
            jenkinsJobName = DEFAULT_JOB;
        }

        Map<String, String> params = new HashMap<>();
        params.put("EXECUTION_ID", jobExecution.getExecutionId().toString());
        params.put("STEP_ORDER", String.valueOf(jobExecution.getJobOrder()));

        // 1) resolvedConfigJson이 있으면 파싱하여 Jenkins 파라미터로 사용
        if (jobExecution.getResolvedConfigJson() != null
                && !jobExecution.getResolvedConfigJson().isBlank()) {
            var configParams = OBJECT_MAPPER.readValue(
                    jobExecution.getResolvedConfigJson()
                    , new TypeReference<Map<String, String>>() {});
            configParams.forEach(params::putIfAbsent);
            log.info("[Real] configJson 파라미터 적용: {}", configParams.keySet());
        }
        // 2) 기존 jobName 파싱 폴백 (configJson 없는 레거시 Job 호환)
        else if (jobName != null && jobName.contains(":")) {
            String raw = jobName.substring(jobName.indexOf(':') + 1).trim();
            String gitUrl = raw.contains("#")
                    ? raw.substring(0, raw.lastIndexOf('#'))
                    : raw;
            String branch = raw.contains("#")
                    ? raw.substring(raw.lastIndexOf('#') + 1)
                    : "main";

            String internalUrl = gitUrl
                    .replace("localhost:29180", "playground-gitlab:29180")
                    .replace("34.47.74.0:29180", "playground-gitlab:29180")
                    .replace("10.178.0.3:29180", "playground-gitlab:29180");

            params.put("GIT_URL", internalUrl);
            params.put("BRANCH", branch);
            log.info("[Real] Git 파라미터 (jobName 폴백): URL={}, branch={}", internalUrl, branch);
        }

        // 사용자 파라미터 병합 (시스템 파라미터가 우선 — putIfAbsent로 사용자 값 추가)
        if (jobExecution.getUserParams() != null) {
            jobExecution.getUserParams().forEach(params::putIfAbsent);
        }

        commandProducer.publishJenkinsBuildCommand(execution, jobExecution, jenkinsJobName, params);
        log.info("[Real] Jenkins 빌드 커맨드 발행 완료 (via Kafka): job={}", jenkinsJobName);
        jobExecution.setWaitingForWebhook(true);
    }

    /**
     * SAGA 보상: Jenkins 워크스페이스를 정리하고 VCS 상태를 복원한다.
     * 현재는 로그만 남기며, 프로덕션에서는 워크스페이스 정리 Job을 트리거해야 한다.
     *
     * @param execution    파이프라인 실행 컨텍스트
     * @param jobExecution 보상할 Job 정보
     */
    @Override
    public void compensate(PipelineExecution execution, PipelineJobExecution jobExecution) throws Exception {
        log.info("[SAGA COMPENSATE] Rolling back {}: jobName={}, executionId={}",
                "Jenkins workspace",
                jobExecution.getJobName(), execution.getId());
        // 프로덕션: Jenkins 작업을 트리거하여 워크스페이스 정리 및 VCS 상태 복원
        // 예: jenkinsAdapter.triggerCleanupJob(execution, jobExecution)
    }
}
