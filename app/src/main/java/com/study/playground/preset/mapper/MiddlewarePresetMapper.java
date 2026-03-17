package com.study.playground.preset.mapper;

import com.study.playground.preset.domain.MiddlewarePreset;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface MiddlewarePresetMapper {
    List<MiddlewarePreset> findAll();
    MiddlewarePreset findById(@Param("id") Long id);
    void insert(MiddlewarePreset preset);
    void update(MiddlewarePreset preset);
    void deleteById(@Param("id") Long id);
    int countByToolId(@Param("toolId") Long toolId);
}
