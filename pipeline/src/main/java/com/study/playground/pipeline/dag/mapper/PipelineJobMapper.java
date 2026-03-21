package com.study.playground.pipeline.dag.mapper;

import com.study.playground.pipeline.dag.domain.PipelineJob;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface PipelineJobMapper {
    void insertBatch(@Param("definitionId") Long definitionId, @Param("jobs") List<PipelineJob> jobs);
    List<PipelineJob> findByDefinitionId(@Param("definitionId") Long definitionId);
    void deleteByDefinitionId(@Param("definitionId") Long definitionId);
    List<Long> findDependsOnJobIds(@Param("definitionId") Long definitionId, @Param("jobId") Long jobId);
    void insertDependency(@Param("definitionId") Long definitionId, @Param("jobId") Long jobId, @Param("dependsOnJobId") Long dependsOnJobId);
    void deleteDependenciesByDefinitionId(@Param("definitionId") Long definitionId);
}
