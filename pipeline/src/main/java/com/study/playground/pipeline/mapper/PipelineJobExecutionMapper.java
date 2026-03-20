package com.study.playground.pipeline.mapper;

import com.study.playground.pipeline.domain.PipelineJobExecution;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Mapper
public interface PipelineJobExecutionMapper {

    void insertBatch(
            @Param("executionId") UUID executionId,
            @Param("jobExecutions") List<PipelineJobExecution> jobExecutions);

    List<PipelineJobExecution> findByExecutionId(@Param("executionId") UUID executionId);

    void updateStatus(
            @Param("id") Long id,
            @Param("status") String status,
            @Param("log") String log,
            @Param("completedAt") LocalDateTime completedAt);

    PipelineJobExecution findByExecutionIdAndJobOrder(
            @Param("executionId") UUID executionId,
            @Param("jobOrder") int jobOrder);

    List<PipelineJobExecution> findWaitingWebhookOlderThan(@Param("minutes") int minutes);

    int updateStatusIfCurrent(
            @Param("id") Long id,
            @Param("expectedStatus") String expectedStatus,
            @Param("newStatus") String newStatus,
            @Param("log") String log,
            @Param("completedAt") LocalDateTime completedAt);
}
