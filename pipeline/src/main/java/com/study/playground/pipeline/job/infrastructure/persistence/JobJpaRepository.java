package com.study.playground.pipeline.job.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface JobJpaRepository extends JpaRepository<JobEntity, String> {

    List<JobEntity> findByProjectIdAndDelYn(String projectId, String delYn);

    List<JobEntity> findByDelYn(String delYn);
}
