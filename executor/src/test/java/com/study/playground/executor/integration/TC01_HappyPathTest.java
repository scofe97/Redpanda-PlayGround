package com.study.playground.executor.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TC-01: 정상 실행 (Happy Path).
 * <p>
 * dispatch command 발행 → PENDING → QUEUED → RUNNING → SUCCESS 전체 흐름을 검증한다.
 * Jenkins K8s pod 생성 시간을 감안하여 타임아웃을 120초로 설정한다.
 */
@DisplayName("TC-01: 정상 실행 (Happy Path)")
class TC01_HappyPathTest extends ExecutorIntegrationTestBase {

    @Test
    @DisplayName("dispatch → PENDING → QUEUED → RUNNING → SUCCESS, 로그 파일 저장")
    void happyPath_shouldCompleteWithSuccess() throws InterruptedException {
        // given
        String jobExcnId = uniqueId("tc01-");

        // when — Operator 역할로 dispatch command 발행
        publishDispatchCommand(jobExcnId, "executor-test", 1);

        // then — SUCCESS까지 대기 (Jenkins K8s pod 생성 포함, 최대 120초)
        var result = waitForStatus(jobExcnId, "SUCCESS", 120);

        // verify — API 응답
        assertThat(result.get("status")).isEqualTo("SUCCESS");
        assertThat(result.get("buildNo")).isNotNull();
        assertThat(result.get("logFileYn")).isEqualTo("Y");

        // verify — 로그 파일이 실제로 디스크에 저장되었는지
        assertThat(logFileExists("executor-test", jobExcnId)).isTrue();
        var logContent = readLogFile("executor-test", jobExcnId);
        assertThat(logContent).isNotBlank();

        // verify — DB 상태도 일치하는지 이중 확인
        assertThat(getJobStatusFromDb(jobExcnId)).isEqualTo("SUCCESS");
    }
}
