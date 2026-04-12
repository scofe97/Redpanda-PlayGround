package com.study.playground.operator.supporttool.service;

import com.study.playground.common.dto.CommonErrorCode;
import com.study.playground.common.exception.BusinessException;
import com.study.playground.operator.common.audit.AuditEventPublisher;
import com.study.playground.operator.supporttool.domain.*;
import com.study.playground.operator.supporttool.dto.SupportToolRequest;
import com.study.playground.operator.supporttool.dto.SupportToolResponse;
import com.study.playground.operator.supporttool.event.SupportToolEvent;
import com.study.playground.operator.supporttool.infrastructure.JenkinsFeignClient;
import com.study.playground.operator.supporttool.infrastructure.SupportToolProbeFeignClient;
import com.study.playground.operator.supporttool.repository.SupportToolRepository;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class SupportToolService {

    private final SupportToolRepository supportToolRepository;
    private final AuditEventPublisher auditEventPublisher;
    private final SupportToolProbeFeignClient supportToolProbeFeignClient;
    private final JenkinsFeignClient jenkinsFeignClient;
    private final JenkinsTokenService jenkinsTokenService;
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
        return supportToolRepository.findAllByOrderByCategoryAscNameAsc().stream()
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
        supportToolRepository.save(tool);

        refreshJenkinsTokenIfNeeded(tool);

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
        supportToolRepository.save(tool);

        refreshJenkinsTokenIfNeeded(tool);

        auditEventPublisher.publish("system", "UPDATE", "SUPPORT_TOOL",
                String.valueOf(id), tool.getName());

        return SupportToolResponse.from(tool);
    }

    @Transactional
    public void delete(Long id) {
        getToolOrThrow(id);

        eventPublisher.publishEvent(new SupportToolEvent.Deleted(id));

        supportToolRepository.deleteById(id);

        auditEventPublisher.publish("system", "DELETE", "SUPPORT_TOOL",
                String.valueOf(id), null);
    }

    public boolean testConnection(Long id) {
        var tool = getToolOrThrow(id);
        try {
            if (tool.getImplementation() == ToolImplementation.JENKINS) {
                var auth = resolveJenkinsAuth(tool);
                if (auth == null) {
                    return false;
                }
                jenkinsFeignClient.getStatus(URI.create(tool.getUrl()), auth);
                return true;
            }

            var healthPath = HEALTH_PATHS.getOrDefault(tool.getImplementation(), "/");
            var targetUrl = URI.create(tool.getUrl() + healthPath);
            supportToolProbeFeignClient.get(targetUrl, buildHeaders(tool));
            return true;
        } catch (FeignException e) {
            log.warn("Tool={} connection test failed with HTTP {}", tool.getName(), e.status());
            return false;
        } catch (Exception e) {
            log.warn("Tool={} connection test failed: {}", tool.getName(), e.getMessage());
            return false;
        }
    }

    private SupportTool getToolOrThrow(Long id) {
        return supportToolRepository.findById(id)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND,
                        "도구를 찾을 수 없습니다: " + id));
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

    private void refreshJenkinsTokenIfNeeded(SupportTool tool) {
        if (tool.getImplementation() == ToolImplementation.JENKINS) {
            jenkinsTokenService.issueAndSave(tool);
        }
    }

    private String resolveJenkinsAuth(SupportTool tool) {
        if (tool.getUsername() == null || tool.getUsername().isBlank()) {
            return null;
        }
        if (tool.getApiToken() != null && !tool.getApiToken().isBlank()) {
            return buildBasicAuth(tool.getUsername(), tool.getApiToken());
        }
        if (tool.getCredential() != null && !tool.getCredential().isBlank()) {
            return buildBasicAuth(tool.getUsername(), tool.getCredential());
        }
        return null;
    }

    private Map<String, String> buildHeaders(SupportTool tool) {
        var headers = new LinkedHashMap<String, String>();
        var authType = tool.getAuthType() != null ? tool.getAuthType() : AuthType.BASIC;
        var credential = tool.getCredential();
        switch (authType) {
            case PRIVATE_TOKEN -> {
                if (credential != null && !credential.isBlank()) {
                    headers.put("Private-Token", credential);
                }
            }
            case BEARER -> {
                if (credential != null && !credential.isBlank()) {
                    headers.put("Authorization", "Bearer " + credential);
                }
            }
            case BASIC -> {
                if (tool.getUsername() != null && !tool.getUsername().isBlank()
                        && credential != null && !credential.isBlank()) {
                    headers.put("Authorization", buildBasicAuth(tool.getUsername(), credential));
                }
            }
            case NONE -> {
            }
        }
        return headers;
    }

    private String buildBasicAuth(String username, String secret) {
        var encoded = Base64.getEncoder().encodeToString(
                (username + ":" + secret).getBytes(StandardCharsets.UTF_8));
        return "Basic " + encoded;
    }
}
