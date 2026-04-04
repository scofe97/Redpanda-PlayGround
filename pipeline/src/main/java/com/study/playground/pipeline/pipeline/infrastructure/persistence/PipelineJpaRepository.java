package com.study.playground.pipeline.pipeline.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PipelineJpaRepository extends JpaRepository<PipelineEntity, String> {

    List<PipelineEntity> findByProjectIdAndDelYn(String projectId, String delYn);

    List<PipelineEntity> findByDelYn(String delYn);
}
