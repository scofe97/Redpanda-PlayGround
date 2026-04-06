package com.study.playground.executor.application;

import com.study.playground.executor.dispatch.application.ReceiveJobService;
import com.study.playground.executor.dispatch.domain.port.in.EvaluateDispatchUseCase;
import com.study.playground.executor.dispatch.domain.port.out.ExecutionJobPort;
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

    @Mock
    EvaluateDispatchUseCase evaluateDispatchUseCase;

    @InjectMocks
    ReceiveJobService service;

    @Test
    @DisplayName("신규 Job 수신 시 저장하고 tryDispatch를 호출해야 한다")
    void receive_newJob_shouldSaveAndTryDispatch() {
        // given
        given(jobPort.existsById("excn-001")).willReturn(false);

        // when
        service.receive(
                "excn-001"
                , "pipe-001"
                , "job-001"
                , 1L
                , "test-job"
                , LocalDateTime.now()
                , "user-01"
        );

        // then
        verify(jobPort).save(any());
        verify(evaluateDispatchUseCase).tryDispatch();
    }

    @Test
    @DisplayName("중복 Job 수신 시 저장과 tryDispatch를 호출하지 않아야 한다")
    void receive_duplicateJob_shouldIgnore() {
        // given
        given(jobPort.existsById("excn-001")).willReturn(true);

        // when
        service.receive(
                "excn-001"
                , "pipe-001"
                , "job-001"
                , 1L
                , "test-job"
                , LocalDateTime.now()
                , "user-01"
        );

        // then
        verify(jobPort, never()).save(any());
        verify(evaluateDispatchUseCase, never()).tryDispatch();
    }
}
