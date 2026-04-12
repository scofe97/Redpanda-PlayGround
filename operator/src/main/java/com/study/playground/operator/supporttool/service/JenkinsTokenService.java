package com.study.playground.operator.supporttool.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.study.playground.operator.supporttool.domain.JenkinsHealthStatus;
import com.study.playground.operator.supporttool.domain.SupportTool;
import com.study.playground.operator.supporttool.domain.ToolImplementation;
import com.study.playground.operator.supporttool.infrastructure.JenkinsFeignClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Collection;

@Service
@RequiredArgsConstructor
@Slf4j
public class JenkinsTokenService {

    private final JenkinsFeignClient jenkinsFeignClient;
    private final ObjectMapper objectMapper;

    /**
     * Jenkins admin credential로 새 API token을 발급하고 support_tool health 컬럼까지 함께 갱신한다.
     */
    @Transactional
    public void issueAndSave(SupportTool tool) {
        try {
            validate(tool);
            var apiToken = issueApiToken(tool);
            tool.updateHealth(JenkinsHealthStatus.HEALTHY, LocalDateTime.now(), apiToken);
        } catch (Exception e) {
            log.error("[JenkinsToken] API token issuance failed: toolId={}, url={}, error={}"
                    , tool.getId(), tool.getUrl(), e.getMessage(), e);
            tool.updateHealth(JenkinsHealthStatus.UNHEALTHY, LocalDateTime.now(), null);
        }
    }

    private String issueApiToken(SupportTool tool) throws Exception {
        var baseUri = URI.create(tool.getUrl());
        var auth = buildBasicAuth(tool.getUsername(), tool.getCredential());

        // live Jenkins에서는 crumb body만으로는 부족했고, crumbIssuer의 session cookie도 같이 필요했다.
        var crumbResponse = jenkinsFeignClient.getCrumb(baseUri, auth);
        var crumbJson = readBody(crumbResponse.body().asInputStream());
        var crumb = objectMapper.readTree(crumbJson).path("crumb").asText(null);
        if (crumb == null || crumb.isBlank()) {
            throw new IllegalStateException("Jenkins crumb missing");
        }
        var cookie = extractSessionCookie(crumbResponse.headers().get("Set-Cookie"));
        if (cookie == null || cookie.isBlank()) {
            throw new IllegalStateException("Jenkins crumb session cookie missing");
        }

        // cookie는 발급 요청 순간에만 사용하고 DB에는 저장하지 않는다.
        var tokenName = "tps-auto-" + System.currentTimeMillis();
        var form = "newTokenName=" + URLEncoder.encode(tokenName, StandardCharsets.UTF_8);
        var tokenResponse = jenkinsFeignClient.generateApiToken(baseUri, tool.getUsername(), auth, crumb, cookie, form);
        var apiToken = objectMapper.readTree(tokenResponse).path("data").path("tokenValue").asText(null);
        if (apiToken == null || apiToken.isBlank()) {
            throw new IllegalStateException("Jenkins API token missing from response");
        }
        return apiToken;
    }

    private void validate(SupportTool tool) {
        if (tool.getImplementation() != ToolImplementation.JENKINS) {
            throw new IllegalArgumentException("Only Jenkins tools support API token issuance");
        }
        if (tool.getUsername() == null || tool.getUsername().isBlank()) {
            throw new IllegalStateException("Jenkins username is required");
        }
        if (tool.getCredential() == null || tool.getCredential().isBlank()) {
            throw new IllegalStateException("Jenkins credential is required for token issuance");
        }
    }

    private String buildBasicAuth(String username, String secret) {
        var encoded = Base64.getEncoder().encodeToString(
                (username + ":" + secret).getBytes(StandardCharsets.UTF_8));
        return "Basic " + encoded;
    }

    private String readBody(InputStream inputStream) throws Exception {
        try (inputStream) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String extractSessionCookie(Collection<String> setCookies) {
        if (setCookies == null) {
            return null;
        }
        return setCookies.stream()
                .filter(cookie -> cookie != null && !cookie.isBlank())
                .map(cookie -> cookie.split(";", 2)[0])
                .findFirst()
                .orElse(null);
    }
}
