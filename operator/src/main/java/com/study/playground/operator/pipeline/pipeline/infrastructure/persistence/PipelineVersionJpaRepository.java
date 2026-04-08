package com.study.playground.operator.pipeline.pipeline.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PipelineVersionJpaRepository extends JpaRepository<PipelineVersionEntity, Long> {

    Optional<PipelineVersionEntity> findTopByPipelineIdOrderByVersionDesc(String pipelineId);

    @Query("SELECT COALESCE(MAX(v.version), 0) + 1 FROM PipelineVersionEntity v WHERE v.pipelineId = :pipelineId")
    int findNextVersionNumber(@Param("pipelineId") String pipelineId);
}
