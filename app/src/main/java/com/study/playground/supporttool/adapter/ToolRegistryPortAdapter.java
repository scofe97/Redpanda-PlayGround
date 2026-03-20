package com.study.playground.supporttool.adapter;

import com.study.playground.pipeline.port.JenkinsToolInfo;
import com.study.playground.pipeline.port.ToolRegistryPort;
import com.study.playground.supporttool.domain.SupportTool;
import com.study.playground.supporttool.domain.ToolCategory;
import com.study.playground.supporttool.service.ToolRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * pipeline 모듈의 ToolRegistryPort 구현체.
 * supporttool 도메인의 ToolRegistry를 위임하여 Jenkins 도구 정보를 제공한다.
 */
@Component
@RequiredArgsConstructor
public class ToolRegistryPortAdapter implements ToolRegistryPort {

    private final ToolRegistry toolRegistry;

    @Override
    public JenkinsToolInfo getActiveJenkinsTool() {
        SupportTool tool = toolRegistry.getActiveTool(ToolCategory.CI_CD_TOOL);
        String credential = toolRegistry.decodeCredential(tool);
        return new JenkinsToolInfo(tool.getUrl(), tool.getUsername(), credential);
    }
}
