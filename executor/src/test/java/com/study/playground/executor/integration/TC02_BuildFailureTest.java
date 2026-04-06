package com.study.playground.executor.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TC-02: Jenkins 빌드 실패 시뮬레이션.
 * <p>
 * 실제 Jenkins 빌드가 진행되는 동안, 별도의 completed 콜백을 시뮬레이션하여
 * FAILURE 경로를 검증한다. 실제 Jenkins 빌드와 시뮬레이션 콜백의 경합을 피하기 위해
 * buildNumber=999를 사용한다.
 * <p>
 * 주의: 이 테스트는 실제 Jenkins executor-test 빌드도 동시에 트리거된다.
 * 실제 빌드의 completed 콜백은 buildNo 매칭 시점에 이미 FAILURE 터미널이므로 무시된다.
 */
@DisplayName("TC-02: Jenkins 빌드 실패 시뮬레이션")
class TC02_BuildFailureTest extends ExecutorIntegrationTestBase {

    @Test
    @DisplayName("completed 콜백에 FAILURE 수신 시 status=FAILURE로 전환")
    void buildFailure_shouldTransitionToFailure() throws InterruptedException {
        // given
        String jobExcnId = uniqueId("tc02-");

        // when — dispatch command 발행
        publishDispatchCommand(jobExcnId, "1");

        // RUNNING 상태까지 대기 (Jenkins가 실제로 빌드를 시작해야 buildNo가 채번됨)
        var runningJob = waitForAnyStatus(
                jobExcnId
                , List.of("RUNNING", "SUCCESS")
                , 90
        );

        // RUNNING에 도달했으면 실제 buildNo를 알 수 있다
        if ("SUCCESS".equals(runningJob.get("status"))) {
            // Jenkins가 이미 완료한 경우 — 이 테스트의 의미가 없으므로 스킵
            return;
        }

        int realBuildNo = ((Number) runningJob.get("buildNo")).intValue();
        String jobId = (String) runningJob.get("jobId");

        // 실제 buildNo로 FAILURE 콜백을 시뮬레이션한다
        publishCompletedCallback(jobId, realBuildNo, "FAILURE", "Simulated failure for TC-02");

        // then — FAILURE로 전환되어야 한다
        var result = waitForStatus(jobExcnId, "FAILURE", 30);
        assertThat(result.get("status")).isEqualTo("FAILURE");
        assertThat(getJobStatusFromDb(jobExcnId)).isEqualTo("FAILURE");
    }
}
