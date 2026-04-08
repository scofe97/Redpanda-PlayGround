package com.study.playground.executor.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TC-04: 재시도 초과 → FAILURE.
 * <p>
 * operator.job에는 존재하지만 실제 Jenkins에는 없는 Job 정의를 사용하여 트리거 실패를 반복시킨다.
 * executor.job-max-retries=2이므로 3번째 실패 시 FAILURE로 전이해야 한다.
 */
@DisplayName("TC-04: 재시도 초과 → FAILURE")
class TC04_RetryExceededTest extends ExecutorIntegrationTestBase {

    @Test
    @DisplayName("트리거 실패 반복 → 스케줄러 재디스패치 후 max-retries(2) 초과 시 FAILURE")
    void retryExceeded_shouldTransitionToFailure() {
        String jobExcnId = uniqueId("tc04-");
        publishDispatchCommand(jobExcnId, createMissingJenkinsJobDefinition());

        var job = waitForStatus(jobExcnId, "FAILURE", 90);
        assertThat(job).as("Job이 DB에 존재해야 한다").isNotNull();
        assertThat(job.get("status")).as("max-retries 초과 후 FAILURE").isEqualTo("FAILURE");
        assertThat(((Number) job.get("retryCnt")).intValue())
                .as("retryCnt가 2 이상")
                .isGreaterThanOrEqualTo(2);
    }
}
