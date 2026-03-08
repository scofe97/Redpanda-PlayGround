package com.study.playground.pipeline.mapper;

import com.study.playground.pipeline.domain.PipelineStep;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Mapper
public interface PipelineStepMapper {
    void insertBatch(@Param("executionId") UUID executionId, @Param("steps") List<PipelineStep> steps);
    List<PipelineStep> findByExecutionId(@Param("executionId") UUID executionId);
    void updateStatus(@Param("id") Long id,
                      @Param("status") String status,
                      @Param("log") String log,
                      @Param("completedAt") LocalDateTime completedAt);
    PipelineStep findByExecutionIdAndStepOrder(@Param("executionId") UUID executionId,
                                                @Param("stepOrder") int stepOrder);
    List<PipelineStep> findWaitingWebhookStepsOlderThan(@Param("minutes") int minutes);

    /**
     * CAS 방식 상태 업데이트: expectedStatus가 현재 상태와 일치할 때만 변경.
     * @return 업데이트된 행 수 (0이면 상태가 이미 변경됨)
     */
    int updateStatusIfCurrent(@Param("id") Long id,
                              @Param("expectedStatus") String expectedStatus,
                              @Param("newStatus") String newStatus,
                              @Param("log") String log,
                              @Param("completedAt") LocalDateTime completedAt);
}
