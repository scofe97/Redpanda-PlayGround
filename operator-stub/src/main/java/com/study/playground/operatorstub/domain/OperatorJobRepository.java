package com.study.playground.operatorstub.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OperatorJobRepository extends JpaRepository<OperatorJob, Long> {

    List<OperatorJob> findByExecutionPipelineId(String executionPipelineId);
}
