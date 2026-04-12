package com.study.playground.operator.supporttool.service;

import com.study.playground.operator.supporttool.domain.JenkinsHealthStatus;
import com.study.playground.operator.supporttool.domain.SupportTool;
import com.study.playground.operator.supporttool.domain.ToolImplementation;
import com.study.playground.operator.supporttool.infrastructure.JenkinsFeignClient;
import com.study.playground.operator.supporttool.repository.SupportToolRepository;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;

@Service
@RequiredArgsConstructor
@Slf4j
public class JenkinsHealthCheckService {

    private final SupportToolRepository supportToolRepository;
    private final JenkinsFeignClient jenkinsFeignClient;
    private final JenkinsTokenService jenkinsTokenService;

    /**
     * 기존 API token으로 Jenkins health를 확인하고, 인증 실패면 token을 즉시 재발급한다.
     */
    @Transactional
    public void checkHealth(SupportTool tool) {
        try {
            if (tool.getApiToken() == null || tool.getApiToken().isBlank()) {
                jenkinsTokenService.issueAndSave(tool);
                return;
            }

            var baseUri = URI.create(tool.getUrl());
            var auth = buildBasicAuth(tool.getUsername(), tool.getApiToken());
            jenkinsFeignClient.getStatus(baseUri, auth);
            tool.updateHealth(JenkinsHealthStatus.HEALTHY, LocalDateTime.now(), null);
        } catch (FeignException.Unauthorized | FeignException.Forbidden e) {
            log.warn("[JenkinsHealth] API token invalid, re-issuing: toolId={}, url={}"
                    , tool.getId(), tool.getUrl());
            jenkinsTokenService.issueAndSave(tool);
        } catch (Exception e) {
            log.error("[JenkinsHealth] Health check failed: toolId={}, url={}, error={}"
                    , tool.getId(), tool.getUrl(), e.getMessage(), e);
            tool.updateHealth(JenkinsHealthStatus.UNHEALTHY, LocalDateTime.now(), null);
        }
    }

    @Transactional
    public void checkAllJenkinsInstances() {
        var tools = supportToolRepository.findByImplementationAndActiveTrue(ToolImplementation.JENKINS);
        // scheduler는 active Jenkins 인스턴스만 순회하면서 health/api token을 최신화한다.
        for (var tool : tools) {
            checkHealth(tool);
        }
    }

    private String buildBasicAuth(String username, String secret) {
        var encoded = Base64.getEncoder().encodeToString(
                (username + ":" + secret).getBytes(StandardCharsets.UTF_8));
        return "Basic " + encoded;
    }
}
