package com.study.playground.supporttool.service;

import com.study.playground.common.dto.CommonErrorCode;
import com.study.playground.common.exception.BusinessException;
import com.study.playground.supporttool.domain.SupportTool;
import com.study.playground.supporttool.domain.ToolType;
import com.study.playground.supporttool.mapper.SupportToolMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Component
@RequiredArgsConstructor
public class ToolRegistry {

    private final SupportToolMapper mapper;

    public SupportTool getActiveTool(ToolType type) {
        SupportTool tool = mapper.findActiveByToolType(type);
        if (tool == null) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND,
                    "활성화된 " + type.name() + " 도구가 없습니다");
        }
        return tool;
    }

    public String decodeCredential(SupportTool tool) {
        if (tool.getCredential() == null || tool.getCredential().isBlank()) {
            return "";
        }
        return new String(Base64.getDecoder().decode(tool.getCredential()), StandardCharsets.UTF_8);
    }
}
