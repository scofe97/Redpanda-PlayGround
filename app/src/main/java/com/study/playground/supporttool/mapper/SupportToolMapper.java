package com.study.playground.supporttool.mapper;

import com.study.playground.supporttool.domain.SupportTool;
import com.study.playground.supporttool.domain.ToolCategory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SupportToolMapper {
    List<SupportTool> findAll();
    SupportTool findById(@Param("id") Long id);
    SupportTool findActiveByCategory(@Param("category") ToolCategory category);
    List<SupportTool> findByCategory(@Param("category") ToolCategory category);
    void insert(SupportTool supportTool);
    void update(SupportTool supportTool);
    void deleteById(@Param("id") Long id);
}
