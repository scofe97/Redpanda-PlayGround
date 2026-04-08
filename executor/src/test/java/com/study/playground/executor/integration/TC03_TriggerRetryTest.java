package com.study.playground.executor.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TC-03: Jenkins 트리거 실패 → 재시도.
 * <p>
 * operator.job에는 존재하지만 실제 Jenkins에는 없는 Job 정의를 만들어 트리거 실패를 유도한다.
 * dispatch는 통과하지만, triggerBuild에서 404 실패한다.
 * Executor는 실패 시 retryOrFail()로 PENDING 리셋 + retryCnt 증가한다.
 */
@DisplayName("TC-03: Jenkins 트리거 실패 → 재시도")
class TC03_TriggerRetryTest extends ExecutorIntegrationTestBase {

    @Test
    @DisplayName("존재하지 않는 Jenkins Job → 트리거 실패 → retryCnt 증가, 재시도 흐름 진입")
    void triggerRetry_nonExistentJob_shouldRetryAndStayPending() throws InterruptedException {
        String jobExcnId = uniqueId("tc03-");
        String jobId = createMissingJenkinsJobDefinition();

        // when — 정의는 존재하지만 실제 Jenkins에는 없는 Job 경로
        publishDispatchCommand(jobExcnId, jobId);

        // then — 비동기 재시도 흐름이 시작될 때까지 대기
        var job = waitForRetry(jobExcnId, 45);
        assertThat(job).as("Job이 DB에 존재해야 한다").isNotNull();
        assertThat(((Number) job.get("retryCnt")).intValue())
                .as("retryCnt가 1 이상이어야 한다")
                .isGreaterThanOrEqualTo(1);
        assertThat(String.valueOf(job.get("status")))
                .as("재시도 흐름 이후 SUCCESS로 완료되면 안 된다")
                .isIn("PENDING", "QUEUED", "FAILURE");
    }

    private java.util.Map<String, Object> waitForRetry(String jobExcnId, int timeoutSeconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            var job = getExecutorJob(jobExcnId);
            if (job != null && ((Number) job.get("retryCnt")).intValue() >= 1) {
                return job;
            }
            Thread.sleep(3000);
        }

        return getExecutorJob(jobExcnId);
    }
}
