package com.study.playground.executor.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TC-04: 재시도 초과 → FAILURE.
 * <p>
 * 실제 Jenkins(id=1)에 존재하지 않는 Job 이름을 사용하여 트리거 실패를 반복시킨다.
 * executor.job-max-retries=2이므로 3번째 실패 시 FAILURE로 전이해야 한다.
 * <p>
 * tryDispatch() 재실행을 위해 더미 Job을 추가 발행한다.
 * 새 Job의 receive() 마지막에 tryDispatch()가 호출되어 PENDING인 기존 Job도 재디스패치된다.
 */
@DisplayName("TC-04: 재시도 초과 → FAILURE")
class TC04_RetryExceededTest extends ExecutorIntegrationTestBase {

    @Test
    @DisplayName("트리거 실패 반복 → max-retries(2) 초과 시 FAILURE")
    void retryExceeded_shouldTransitionToFailure() throws InterruptedException {
        // given — 메인 Job (1차 시도 → 실패 → retryCnt=1, PENDING)
        String mainJob = uniqueId("tc04-");
        publishDispatchCommand(mainJob, "999");
        Thread.sleep(15_000);

        // 2차 시도 유도: 더미 Job 발행 → tryDispatch() → mainJob 재시도 → 실패 → retryCnt=2
        String dummy1 = uniqueId("tc04d1-");
        publishDispatchCommand(dummy1, "998");
        Thread.sleep(15_000);

        // 3차 시도 유도: 더미 Job 발행 → tryDispatch() → mainJob 재시도 → canRetry=false → FAILURE
        String dummy2 = uniqueId("tc04d2-");
        publishDispatchCommand(dummy2, "997");
        Thread.sleep(15_000);

        // then — 메인 Job이 FAILURE로 전이
        var job = getExecutorJob(mainJob);
        assertThat(job).as("Job이 DB에 존재해야 한다").isNotNull();
        assertThat(job.get("status")).as("max-retries 초과 후 FAILURE").isEqualTo("FAILURE");
        assertThat(((Number) job.get("retryCnt")).intValue())
                .as("retryCnt가 2 이상")
                .isGreaterThanOrEqualTo(2);
    }
}
