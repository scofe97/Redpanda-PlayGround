package com.study.playground.pipeline.mapper;

import com.study.playground.pipeline.domain.PipelineExecution;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Mapper
public interface PipelineExecutionMapper {
    void insert(PipelineExecution execution);
    PipelineExecution findById(@Param("id") UUID id);
    PipelineExecution findLatestByTicketId(@Param("ticketId") Long ticketId);
    List<PipelineExecution> findByTicketId(@Param("ticketId") Long ticketId);
    void updateStatus(@Param("id") UUID id,
                      @Param("status") String status,
                      @Param("completedAt") LocalDateTime completedAt,
                      @Param("errorMessage") String errorMessage);
}
