package com.study.playground.supporttool.service;

import com.study.playground.common.audit.AuditEventPublisher;
import com.study.playground.common.dto.CommonErrorCode;
import com.study.playground.common.exception.BusinessException;
import com.study.playground.connector.service.ConnectorManager;
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
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class SupportToolService {

    private final SupportToolMapper supportToolMapper;
    private final AuditEventPublisher auditEventPublisher;
    private final RestTemplate restTemplate;
    private final ConnectorManager connectorManager;

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

        try {
            connectorManager.createConnectors(tool);
        } catch (Exception e) {
            log.warn("커넥터 생성 실패 tool={}: {}", tool.getId(), e.getMessage());
        }

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

        try {
            connectorManager.deleteConnectors(id);
        } catch (Exception e) {
            log.warn("커넥터 삭제 실패 tool={}: {}", id, e.getMessage());
        }

        supportToolMapper.deleteById(id);

        auditEventPublisher.publish("system", "DELETE", "SUPPORT_TOOL",
                String.valueOf(id), null);
    }

    public boolean testConnection(Long id) {
        SupportTool tool = getToolOrThrow(id);
        String healthPath = HEALTH_PATHS.getOrDefault(tool.getToolType(), "/");
        String targetUrl = tool.getUrl() + healthPath;
        try {
            HttpHeaders headers = new HttpHeaders();
            applyAuth(headers, tool);
            restTemplate.exchange(targetUrl, HttpMethod.GET,
                    new HttpEntity<>(headers), String.class);
            return true;
        } catch (HttpStatusCodeException e) {
            // HTTP 응답이 왔다면 서버는 도달 가능 (401/403 등은 인증 문제일 뿐)
            log.info("Tool={} reachable but returned HTTP {}: {}",
                    tool.getName(), e.getStatusCode().value(), targetUrl);
            return true;
        } catch (ResourceAccessException e) {
            // 연결 자체 실패 (Connection refused, timeout 등)
            log.warn("Tool={} unreachable ({}): {}",
                    tool.getName(), targetUrl, e.getMessage());
            return false;
        } catch (Exception e) {
            log.warn("Tool={} connection test failed ({}): {}",
                    tool.getName(), targetUrl, e.getMessage());
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
        return switch (toolType) {
            case "JENKINS" -> ToolType.JENKINS;
            case "GITLAB" -> ToolType.GITLAB;
            case "NEXUS" -> ToolType.NEXUS;
            case "REGISTRY" -> ToolType.REGISTRY;
            case null, default -> throw new BusinessException(CommonErrorCode.INVALID_INPUT,
                    "유효하지 않은 도구 타입입니다: " + toolType);
        };
    }

    private void encodeCredential(SupportTool tool, String rawCredential) {
        if (rawCredential != null && !rawCredential.isBlank()) {
            tool.setCredential(rawCredential);
        }
    }

    private void applyAuth(HttpHeaders headers, SupportTool tool) {
        String decoded = decodeCredential(tool);
        if (decoded == null) return;

        switch (tool.getToolType()) {
            case GITLAB -> headers.set("Private-Token", decoded);
            case JENKINS, NEXUS, REGISTRY -> {
                if (tool.getUsername() != null) {
                    headers.setBasicAuth(tool.getUsername(), decoded);
                }
            }
        }
    }

    private String decodeCredential(SupportTool tool) {
        if (tool.getCredential() == null || tool.getCredential().isBlank()) return null;
        return tool.getCredential();
    }
}
