package com.study.playground.pipeline.mapper;

import com.study.playground.pipeline.domain.PipelineDefinition;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface PipelineDefinitionMapper {
    void insert(PipelineDefinition definition);
    PipelineDefinition findById(@Param("id") Long id);
    List<PipelineDefinition> findAll();
    void updateStatus(@Param("id") Long id, @Param("status") String status);
    void delete(@Param("id") Long id);
}
