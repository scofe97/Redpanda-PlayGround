package com.study.playground.supporttool.mapper;

import com.study.playground.supporttool.domain.SupportTool;
import com.study.playground.supporttool.domain.ToolType;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SupportToolMapper {
    List<SupportTool> findAll();
    SupportTool findById(@Param("id") Long id);
    SupportTool findActiveByToolType(@Param("toolType") ToolType toolType);
    void insert(SupportTool supportTool);
    void update(SupportTool supportTool);
    void deleteById(@Param("id") Long id);
}
