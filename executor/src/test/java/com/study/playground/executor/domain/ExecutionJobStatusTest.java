package com.study.playground.executor.domain;

import com.study.playground.executor.execution.domain.model.ExecutionJobStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.study.playground.executor.execution.domain.model.ExecutionJobStatus.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ExecutionJobStatus 상태 전이 단위 테스트")
class ExecutionJobStatusTest {

    @Test
    @DisplayName("유효한 전이는 모두 성공해야 한다")
    void validTransitions_shouldSucceed() {
        // PENDING → QUEUED
        assertThat(PENDING.canTransitionTo(QUEUED)).isTrue();

        // QUEUED → SUBMITTED, PENDING, FAILURE
        assertThat(QUEUED.canTransitionTo(SUBMITTED)).isTrue();
        assertThat(QUEUED.canTransitionTo(PENDING)).isTrue();
        assertThat(QUEUED.canTransitionTo(FAILURE)).isTrue();

        // SUBMITTED → RUNNING, PENDING, FAILURE
        assertThat(SUBMITTED.canTransitionTo(RUNNING)).isTrue();
        assertThat(SUBMITTED.canTransitionTo(PENDING)).isTrue();
        assertThat(SUBMITTED.canTransitionTo(FAILURE)).isTrue();

        // RUNNING → 모든 터미널 상태 + PENDING
        assertThat(RUNNING.canTransitionTo(SUCCESS)).isTrue();
        assertThat(RUNNING.canTransitionTo(FAILURE)).isTrue();
        assertThat(RUNNING.canTransitionTo(UNSTABLE)).isTrue();
        assertThat(RUNNING.canTransitionTo(ABORTED)).isTrue();
        assertThat(RUNNING.canTransitionTo(NOT_BUILT)).isTrue();
        assertThat(RUNNING.canTransitionTo(NOT_EXECUTED)).isTrue();
        assertThat(RUNNING.canTransitionTo(PENDING)).isTrue();
    }

    @Test
    @DisplayName("QUEUED → RUNNING 직접 전이는 불가해야 한다")
    void invalidTransition_queuedToRunning_shouldFail() {
        assertThat(QUEUED.canTransitionTo(RUNNING)).isFalse();
    }

    @Test
    @DisplayName("PENDING → RUNNING 전이는 IllegalStateException을 던져야 한다")
    void invalidTransition_pendingToRunning_shouldThrow() {
        // given
        ExecutionJobStatus from = PENDING;
        ExecutionJobStatus to = RUNNING;

        // when / then
        assertThatThrownBy(() -> ExecutionJobStatus.validateTransition(from, to))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PENDING")
                .hasMessageContaining("RUNNING");
    }

    @Test
    @DisplayName("터미널 상태에서 PENDING으로 전이하면 IllegalStateException을 던져야 한다")
    void invalidTransition_successToPending_shouldThrow() {
        // given
        ExecutionJobStatus from = SUCCESS;
        ExecutionJobStatus to = PENDING;

        // when / then
        assertThatThrownBy(() -> ExecutionJobStatus.validateTransition(from, to))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("터미널 상태들은 isTerminal()이 true를 반환해야 한다")
    void isTerminal_terminalStatuses_shouldReturnTrue() {
        assertThat(SUCCESS.isTerminal()).isTrue();
        assertThat(FAILURE.isTerminal()).isTrue();
        assertThat(UNSTABLE.isTerminal()).isTrue();
        assertThat(ABORTED.isTerminal()).isTrue();
        assertThat(NOT_BUILT.isTerminal()).isTrue();
        assertThat(NOT_EXECUTED.isTerminal()).isTrue();
    }

    @Test
    @DisplayName("비터미널 상태들은 isTerminal()이 false를 반환해야 한다")
    void isTerminal_nonTerminalStatuses_shouldReturnFalse() {
        assertThat(PENDING.isTerminal()).isFalse();
        assertThat(QUEUED.isTerminal()).isFalse();
        assertThat(SUBMITTED.isTerminal()).isFalse();
        assertThat(RUNNING.isTerminal()).isFalse();
    }

    @Test
    @DisplayName("알려진 Jenkins 결과 문자열은 올바른 상태로 매핑되어야 한다")
    void fromJenkinsResult_knownResults() {
        assertThat(fromJenkinsResult("SUCCESS")).isEqualTo(SUCCESS);
        assertThat(fromJenkinsResult("FAILURE")).isEqualTo(FAILURE);
        assertThat(fromJenkinsResult("UNSTABLE")).isEqualTo(UNSTABLE);
        assertThat(fromJenkinsResult("ABORTED")).isEqualTo(ABORTED);
        assertThat(fromJenkinsResult("NOT_BUILT")).isEqualTo(NOT_BUILT);
        assertThat(fromJenkinsResult("NOT_EXECUTED")).isEqualTo(NOT_EXECUTED);
    }

    @Test
    @DisplayName("null 또는 알 수 없는 Jenkins 결과는 FAILURE로 매핑되어야 한다")
    void fromJenkinsResult_nullOrUnknown_shouldReturnFailure() {
        assertThat(fromJenkinsResult(null)).isEqualTo(FAILURE);
        assertThat(fromJenkinsResult("UNKNOWN")).isEqualTo(FAILURE);
        assertThat(fromJenkinsResult("")).isEqualTo(FAILURE);
    }

    @Test
    @DisplayName("SUBMITTED → 터미널 상태 전이가 가능해야 한다 (시작 웹훅 유실 대비)")
    void submittedToTerminal_shouldSucceed() {
        assertThat(SUBMITTED.canTransitionTo(SUCCESS)).isTrue();
        assertThat(SUBMITTED.canTransitionTo(UNSTABLE)).isTrue();
        assertThat(SUBMITTED.canTransitionTo(ABORTED)).isTrue();
        assertThat(SUBMITTED.canTransitionTo(NOT_BUILT)).isTrue();
        assertThat(SUBMITTED.canTransitionTo(NOT_EXECUTED)).isTrue();
    }
}
