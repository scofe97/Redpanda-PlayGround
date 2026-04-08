package com.study.playground.executor.application;

import com.study.playground.executor.execution.application.ReceiveJobService;
import com.study.playground.executor.execution.domain.port.out.ExecutionJobPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReceiveJobService 단위 테스트")
class ReceiveJobServiceTest {

    @Mock
    ExecutionJobPort jobPort;

    @InjectMocks
    ReceiveJobService service;

    @Test
    @DisplayName("신규 Job 수신 시 저장해야 한다")
    void receive_newJob_shouldSave() {
        // given
        given(jobPort.existsById("excn-001")).willReturn(false);

        // when
        service.receive(
                "excn-001"
                , "pipe-001"
                , "job-001"
                , LocalDateTime.now()
                , "user-01"
        );

        // then
        verify(jobPort).save(any());
    }

    @Test
    @DisplayName("중복 Job 수신 시 저장하지 않아야 한다")
    void receive_duplicateJob_shouldIgnore() {
        // given
        given(jobPort.existsById("excn-001")).willReturn(true);

        // when
        service.receive(
                "excn-001"
                , "pipe-001"
                , "job-001"
                , LocalDateTime.now()
                , "user-01"
        );

        // then
        verify(jobPort, never()).save(any());
    }
}
