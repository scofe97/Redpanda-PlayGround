package com.study.playground.operator.operatorjob.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OperatorJobRepository extends JpaRepository<OperatorJob, Long> {

    List<OperatorJob> findByExecutionPipelineId(String executionPipelineId);

    Optional<OperatorJob> findFirstByExecutionPipelineIdAndJobOrderGreaterThanAndStatusOrderByJobOrderAsc(
            String executionPipelineId, int jobOrder, OperatorJobStatus status);
}
