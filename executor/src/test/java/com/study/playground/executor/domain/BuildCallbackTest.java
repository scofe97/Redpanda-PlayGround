package com.study.playground.executor.domain;

import com.study.playground.executor.execution.domain.model.BuildCallback;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BuildCallback 도메인 단위 테스트")
class BuildCallbackTest {

    @Test
    @DisplayName("started()는 result='STARTED', logContent=null로 생성되어야 한다")
    void started_shouldCreateWithCorrectFields() {
        // when
        BuildCallback callback = BuildCallback.started("job-excn-001", 5);

        // then
        assertThat(callback.jobId()).isEqualTo("job-excn-001");
        assertThat(callback.buildNumber()).isEqualTo(5);
        assertThat(callback.result()).isEqualTo("STARTED");
        assertThat(callback.logContent()).isNull();
    }

    @Test
    @DisplayName("completed()는 모든 필드를 포함하여 생성되어야 한다")
    void completed_shouldCreateWithAllFields() {
        // given
        String logContent = "Build log content here";

        // when
        BuildCallback callback = BuildCallback.completed(
                "job-excn-002"
                , 10
                , "SUCCESS"
                , logContent
        );

        // then
        assertThat(callback.jobId()).isEqualTo("job-excn-002");
        assertThat(callback.buildNumber()).isEqualTo(10);
        assertThat(callback.result()).isEqualTo("SUCCESS");
        assertThat(callback.logContent()).isEqualTo(logContent);
    }

    @Test
    @DisplayName("completed()는 logContent가 null이어도 허용해야 한다")
    void completed_withNullLogContent_shouldAcceptNull() {
        // when
        BuildCallback callback = BuildCallback.completed(
                "job-excn-003"
                , 3
                , "FAILURE"
                , null
        );

        // then
        assertThat(callback.logContent()).isNull();
        assertThat(callback.result()).isEqualTo("FAILURE");
    }
}
