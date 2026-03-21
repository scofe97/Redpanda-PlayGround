package com.study.playground.pipeline.dag.mapper;

import com.study.playground.pipeline.dag.domain.PipelineJobMapping;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface PipelineJobMappingMapper {
    void insertBatch(@Param("definitionId") Long definitionId
            , @Param("mappings") List<PipelineJobMapping> mappings);
    List<PipelineJobMapping> findByDefinitionId(@Param("definitionId") Long definitionId);
    void deleteByDefinitionId(@Param("definitionId") Long definitionId);
}
