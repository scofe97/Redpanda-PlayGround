package com.study.playground.executor.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TC-03: Jenkins 트리거 실패 → 재시도.
 * <p>
 * 실제 Jenkins(id=1)에 존재하지 않는 Job 이름을 사용하여 트리거 실패를 유도한다.
 * slot check(isImmediatelyExecutable)는 통과하지만, triggerBuild에서 404 실패한다.
 * Executor는 실패 시 retryOrFail()로 PENDING 리셋 + retryCnt 증가한다.
 */
@DisplayName("TC-03: Jenkins 트리거 실패 → 재시도")
class TC03_TriggerRetryTest extends ExecutorIntegrationTestBase {

    @Test
    @DisplayName("존재하지 않는 Jenkins Job → 트리거 실패 → retryCnt 증가, PENDING 유지")
    void triggerRetry_nonExistentJob_shouldRetryAndStayPending() throws InterruptedException {
        // given — 존재하지 않는 Job 이름 사용 (실제 Jenkins에서 404)
        String jobExcnId = uniqueId("tc03-");

        // when — 실제 Jenkins(id=1)이지만 없는 Job 이름
        publishDispatchCommand(jobExcnId, "999");

        // 트리거 실패 + 재시도까지 대기
        Thread.sleep(20_000);

        // then — PENDING 상태, retryCnt >= 1
        var job = getExecutorJob(jobExcnId);
        assertThat(job).as("Job이 DB에 존재해야 한다").isNotNull();
        assertThat(job.get("status")).as("트리거 실패 후 PENDING으로 리셋").isEqualTo("PENDING");
        assertThat(((Number) job.get("retryCnt")).intValue())
                .as("retryCnt가 1 이상이어야 한다")
                .isGreaterThanOrEqualTo(1);
    }
}
