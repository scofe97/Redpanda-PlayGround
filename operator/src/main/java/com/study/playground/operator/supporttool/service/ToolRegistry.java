package com.study.playground.operator.supporttool.service;

import com.study.playground.common.dto.CommonErrorCode;
import com.study.playground.common.exception.BusinessException;
import com.study.playground.operator.supporttool.domain.SupportTool;
import com.study.playground.operator.supporttool.domain.ToolCategory;
import com.study.playground.operator.supporttool.repository.SupportToolRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ToolRegistry {

    private final SupportToolRepository supportToolRepository;

    /**
     * 카테고리 기반으로 활성화된 도구를 조회한다.
     * 프리셋 없이 도구를 직접 참조할 때 사용한다.
     */
    public SupportTool getActiveTool(ToolCategory category) {
        return supportToolRepository.findFirstByCategoryAndActiveTrue(category)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND,
                        "활성화된 " + category.name() + " 카테고리 도구가 없습니다"));
    }

    public String decodeCredential(SupportTool tool) {
        if (tool.getCredential() == null || tool.getCredential().isBlank()) {
            return "";
        }
        return tool.getCredential();
    }
}
