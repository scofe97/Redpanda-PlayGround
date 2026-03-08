package com.study.playground.supporttool.service;

import com.study.playground.common.audit.AuditEventPublisher;
import com.study.playground.common.dto.CommonErrorCode;
import com.study.playground.common.exception.BusinessException;
import com.study.playground.supporttool.domain.SupportTool;
import com.study.playground.supporttool.domain.ToolType;
import com.study.playground.supporttool.dto.SupportToolRequest;
import com.study.playground.supporttool.dto.SupportToolResponse;
import com.study.playground.supporttool.mapper.SupportToolMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class SupportToolService {

    private final SupportToolMapper supportToolMapper;
    private final AuditEventPublisher auditEventPublisher;
    private final RestTemplate restTemplate;

    private static final Map<ToolType, String> HEALTH_PATHS = Map.of(
            ToolType.JENKINS, "/api/json",
            ToolType.GITLAB, "/api/v4/version",
            ToolType.NEXUS, "/service/rest/v1/status",
            ToolType.REGISTRY, "/v2/"
    );

    @Transactional(readOnly = true)
    public List<SupportToolResponse> findAll() {
        return supportToolMapper.findAll().stream()
                .map(SupportToolResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public SupportToolResponse findById(Long id) {
        return SupportToolResponse.from(getToolOrThrow(id));
    }

    @Transactional
    public SupportToolResponse create(SupportToolRequest request) {
        SupportTool tool = toEntity(request);
        encodeCredential(tool, request.getCredential());
        supportToolMapper.insert(tool);

        auditEventPublisher.publish("system", "CREATE", "SUPPORT_TOOL",
                String.valueOf(tool.getId()), tool.getName());

        return SupportToolResponse.from(tool);
    }

    @Transactional
    public SupportToolResponse update(Long id, SupportToolRequest request) {
        SupportTool tool = getToolOrThrow(id);
        tool.setToolType(parseToolType(request.getToolType()));
        tool.setName(request.getName());
        tool.setUrl(request.getUrl());
        tool.setUsername(request.getUsername());
        tool.setActive(request.isActive());
        encodeCredential(tool, request.getCredential());
        supportToolMapper.update(tool);

        auditEventPublisher.publish("system", "UPDATE", "SUPPORT_TOOL",
                String.valueOf(id), tool.getName());

        return SupportToolResponse.from(tool);
    }

    @Transactional
    public void delete(Long id) {
        getToolOrThrow(id);
        supportToolMapper.deleteById(id);

        auditEventPublisher.publish("system", "DELETE", "SUPPORT_TOOL",
                String.valueOf(id), null);
    }

    public boolean testConnection(Long id) {
        SupportTool tool = getToolOrThrow(id);
        String healthPath = HEALTH_PATHS.getOrDefault(tool.getToolType(), "/");
        try {
            HttpHeaders headers = new HttpHeaders();
            applyAuth(headers, tool);
            restTemplate.exchange(
                    tool.getUrl() + healthPath, HttpMethod.GET,
                    new HttpEntity<>(headers), String.class);
            return true;
        } catch (Exception e) {
            log.warn("Connection test failed for tool={} ({}): {}",
                    tool.getName(), tool.getUrl() + healthPath, e.getMessage());
            return false;
        }
    }

    private SupportTool getToolOrThrow(Long id) {
        SupportTool tool = supportToolMapper.findById(id);
        if (tool == null) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND,
                    "도구를 찾을 수 없습니다: " + id);
        }
        return tool;
    }

    private SupportTool toEntity(SupportToolRequest request) {
        SupportTool tool = new SupportTool();
        tool.setToolType(parseToolType(request.getToolType()));
        tool.setName(request.getName());
        tool.setUrl(request.getUrl());
        tool.setUsername(request.getUsername());
        tool.setActive(request.isActive());
        return tool;
    }

    private ToolType parseToolType(String toolType) {
        try {
            return ToolType.valueOf(toolType);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT,
                    "유효하지 않은 도구 타입입니다: " + toolType);
        }
    }

    private void encodeCredential(SupportTool tool, String rawCredential) {
        if (rawCredential != null && !rawCredential.isBlank()) {
            tool.setCredential(Base64.getEncoder()
                    .encodeToString(rawCredential.getBytes(StandardCharsets.UTF_8)));
        }
    }

    private void applyAuth(HttpHeaders headers, SupportTool tool) {
        String decoded = decodeCredential(tool);
        if (decoded == null) return;

        if (tool.getToolType() == ToolType.GITLAB) {
            headers.set("Private-Token", decoded);
        } else if (tool.getUsername() != null) {
            headers.setBasicAuth(tool.getUsername(), decoded);
        }
    }

    private String decodeCredential(SupportTool tool) {
        if (tool.getCredential() == null || tool.getCredential().isBlank()) return null;
        return new String(Base64.getDecoder().decode(tool.getCredential()), StandardCharsets.UTF_8);
    }
}
