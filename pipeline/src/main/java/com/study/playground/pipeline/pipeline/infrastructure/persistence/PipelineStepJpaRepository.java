package com.study.playground.pipeline.pipeline.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PipelineStepJpaRepository extends JpaRepository<PipelineStepEntity, Long> {

    List<PipelineStepEntity> findByVersionIdOrderBySeqAsc(Long versionId);
}
