package com.study.playground.supporttool.service;

import com.study.playground.common.audit.AuditEventPublisher;
import com.study.playground.common.dto.CommonErrorCode;
import com.study.playground.common.exception.BusinessException;
import com.study.playground.supporttool.domain.*;
import com.study.playground.supporttool.event.SupportToolEvent;
import com.study.playground.supporttool.dto.SupportToolRequest;
import com.study.playground.supporttool.dto.SupportToolResponse;
import com.study.playground.supporttool.mapper.SupportToolMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.context.ApplicationEventPublisher;
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
    private final ApplicationEventPublisher eventPublisher;

    private static final Map<ToolImplementation, String> HEALTH_PATHS = Map.of(
            ToolImplementation.JENKINS, "/api/json",
            ToolImplementation.GITLAB, "/api/v4/version",
            ToolImplementation.GITHUB, "/api/v3",
            ToolImplementation.NEXUS, "/service/rest/v1/status",
            ToolImplementation.ARTIFACTORY, "/api/system/ping",
            ToolImplementation.HARBOR, "/api/v2.0/ping",
            ToolImplementation.DOCKER_REGISTRY, "/v2/",
            ToolImplementation.ARGOCD, "/api/version",
            ToolImplementation.MINIO, "/minio/health/live"
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
        var tool = toEntity(request);
        encodeCredential(tool, request.getCredential());
        supportToolMapper.insert(tool);

        auditEventPublisher.publish("system", "CREATE", "SUPPORT_TOOL",
                String.valueOf(tool.getId()), tool.getName());

        eventPublisher.publishEvent(new SupportToolEvent.Created(tool));

        return SupportToolResponse.from(tool);
    }

    @Transactional
    public SupportToolResponse update(Long id, SupportToolRequest request) {
        var tool = getToolOrThrow(id);
        tool.setCategory(parseCategory(request.getCategory()));
        tool.setImplementation(parseImplementation(request.getImplementation()));
        tool.setName(request.getName());
        tool.setUrl(request.getUrl());
        tool.setAuthType(parseAuthType(request.getAuthType()));
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

        eventPublisher.publishEvent(new SupportToolEvent.Deleted(id));

        supportToolMapper.deleteById(id);

        auditEventPublisher.publish("system", "DELETE", "SUPPORT_TOOL",
                String.valueOf(id), null);
    }

    public boolean testConnection(Long id) {
        var tool = getToolOrThrow(id);
        var healthPath = HEALTH_PATHS.getOrDefault(tool.getImplementation(), "/");
        var targetUrl = tool.getUrl() + healthPath;
        try {
            var headers = new HttpHeaders();
            applyAuth(headers, tool);
            restTemplate.exchange(targetUrl, HttpMethod.GET,
                    new HttpEntity<>(headers), String.class);
            return true;
        } catch (HttpStatusCodeException e) {
            log.info("Tool={} reachable but returned HTTP {}: {}",
                    tool.getName(), e.getStatusCode().value(), targetUrl);
            return true;
        } catch (ResourceAccessException e) {
            log.warn("Tool={} unreachable ({}): {}",
                    tool.getName(), targetUrl, e.getMessage());
            return false;
        } catch (Exception e) {
            log.warn("Tool={} connection test failed ({}): {}",
                    tool.getName(), targetUrl, e.getMessage());
            return false;
        }
    }

    // ── private helpers ──────────────────────────────────────────────

    private SupportTool getToolOrThrow(Long id) {
        var tool = supportToolMapper.findById(id);
        if (tool == null) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND,
                    "도구를 찾을 수 없습니다: " + id);
        }
        return tool;
    }

    private SupportTool toEntity(SupportToolRequest request) {
        var tool = new SupportTool();
        tool.setCategory(parseCategory(request.getCategory()));
        tool.setImplementation(parseImplementation(request.getImplementation()));
        tool.setName(request.getName());
        tool.setUrl(request.getUrl());
        tool.setAuthType(parseAuthType(request.getAuthType()));
        tool.setUsername(request.getUsername());
        tool.setActive(request.isActive());
        return tool;
    }

    private ToolCategory parseCategory(String category) {
        try {
            return ToolCategory.valueOf(category);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT,
                    "유효하지 않은 카테고리입니다: %s".formatted(category));
        }
    }

    private ToolImplementation parseImplementation(String implementation) {
        try {
            return ToolImplementation.valueOf(implementation);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT,
                    "유효하지 않은 구현체입니다: %s".formatted(implementation));
        }
    }

    private AuthType parseAuthType(String authType) {
        if (authType == null || authType.isBlank()) {
            return AuthType.BASIC;
        }
        try {
            return AuthType.valueOf(authType);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT,
                    "유효하지 않은 인증 타입입니다: %s".formatted(authType));
        }
    }

    private void encodeCredential(SupportTool tool, String rawCredential) {
        if (rawCredential != null && !rawCredential.isBlank()) {
            tool.setCredential(rawCredential);
        }
    }

    private void applyAuth(HttpHeaders headers, SupportTool tool) {
        var decoded = decodeCredential(tool);
        if (decoded == null) return;

        var authType = tool.getAuthType() != null ? tool.getAuthType() : AuthType.BASIC;
        switch (authType) {
            case PRIVATE_TOKEN -> headers.set("Private-Token", decoded);
            case BEARER -> headers.setBearerAuth(decoded);
            case BASIC -> {
                if (tool.getUsername() != null) {
                    headers.setBasicAuth(tool.getUsername(), decoded);
                }
            }
            case NONE -> { /* no auth */ }
        }
    }

    private String decodeCredential(SupportTool tool) {
        if (tool.getCredential() == null || tool.getCredential().isBlank()) return null;
        return tool.getCredential();
    }
}
