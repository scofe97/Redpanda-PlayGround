package com.study.playground.connector.mapper;

import com.study.playground.connector.domain.ConnectorConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ConnectorConfigMapper {
    List<ConnectorConfig> findAll();
    List<ConnectorConfig> findByToolId(@Param("toolId") Long toolId);
    void insert(ConnectorConfig connectorConfig);
    void deleteByToolId(@Param("toolId") Long toolId);
}
