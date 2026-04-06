package com.study.playground.executor.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TC-05: 동일 jobName 중복 실행 방지.
 * <p>
 * 같은 jobName(executor-test)으로 두 개의 Job을 연속 발행했을 때,
 * 첫 번째가 QUEUED/RUNNING 상태이면 두 번째는 PENDING에 머물러야 한다.
 * DispatchService의 existsByJobIdAndStatusIn 중복 체크를 검증한다.
 */
@DisplayName("TC-05: 동일 jobName 중복 실행 방지")
class TC05_DuplicatePreventionTest extends ExecutorIntegrationTestBase {

    @Test
    @DisplayName("첫 번째 Job이 활성 상태이면 두 번째 Job은 PENDING 유지")
    void duplicatePrevention_secondJobShouldStayPending() throws InterruptedException {
        // given
        String jobA = uniqueId("tc05a-");
        String jobB = uniqueId("tc05b-");

        // when — 같은 jobName으로 두 개 연속 발행
        publishDispatchCommand(jobA, "1");
        Thread.sleep(5_000); // jobA가 QUEUED/RUNNING에 도달할 시간

        publishDispatchCommand(jobB, "1");
        Thread.sleep(5_000); // jobB의 dispatch 평가가 완료될 시간

        // then
        var jobAResult = getExecutorJob(jobA);
        var jobBResult = getExecutorJob(jobB);

        assertThat(jobAResult).isNotNull();
        assertThat(jobBResult).isNotNull();

        // jobA는 QUEUED 이상 진행되어야 한다
        String jobAStatus = (String) jobAResult.get("status");
        assertThat(List.of("QUEUED", "RUNNING", "SUCCESS")).contains(jobAStatus);

        // jobB는 PENDING에 머물러야 한다 (동일 jobName 중복 방지)
        assertThat(jobBResult.get("status")).isEqualTo("PENDING");
    }
}
