package com.study.playground.adapter;

import com.study.playground.adapter.dto.NexusAsset;
import com.study.playground.adapter.dto.NexusSearchResponse;
import com.study.playground.supporttool.domain.SupportTool;
import com.study.playground.supporttool.domain.ToolType;
import com.study.playground.supporttool.service.ToolRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Collections;
import java.util.List;

@Slf4j
@Component
public class NexusAdapter {

    private final RestTemplate restTemplate;
    private final ToolRegistry toolRegistry;

    public NexusAdapter(RestTemplate restTemplate, ToolRegistry toolRegistry) {
        this.restTemplate = restTemplate;
        this.toolRegistry = toolRegistry;
    }

    private SupportTool getTool() {
        return toolRegistry.getActiveTool(ToolType.NEXUS);
    }

    /** groupId, artifactId, version으로 아티팩트를 검색한다. Nexus 미응답 또는 미발견 시 빈 리스트 반환. */
    public List<NexusAsset> searchComponents(String repository, String groupId,
                                              String artifactId, String version) {
        try {
            AdapterInputValidator.validatePathParam(repository, "repository");
            AdapterInputValidator.validatePathParam(groupId, "groupId");
            AdapterInputValidator.validatePathParam(artifactId, "artifactId");
            UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(getTool().getUrl())
                    .path("/service/rest/v1/search/assets")
                    .queryParam("repository", repository)
                    .queryParam("maven.groupId", groupId)
                    .queryParam("maven.artifactId", artifactId);
            if (version != null && !version.isBlank()) {
                builder.queryParam("maven.baseVersion", version);
            }
            String url = builder.toUriString();
            ResponseEntity<NexusSearchResponse> response = restTemplate.exchange(
                    url, HttpMethod.GET,
                    new HttpEntity<>(buildHeaders()),
                    new ParameterizedTypeReference<>() {});
            NexusSearchResponse body = response.getBody();
            if (body != null && body.items() != null) {
                return body.items();
            }
            return Collections.emptyList();
        } catch (Exception e) {
            log.warn("Nexus searchComponents failed for {}:{}:{}: {}", groupId, artifactId, version, e.getMessage());
            return Collections.emptyList();
        }
    }

    /** 다운로드 URL로 아티팩트 바이너리를 다운로드한다. 실패 시 null 반환. */
    public byte[] downloadArtifact(String downloadUrl) {
        try {
            AdapterInputValidator.validateBaseUrl(downloadUrl, getTool().getUrl());
            ResponseEntity<byte[]> response = restTemplate.exchange(
                    downloadUrl, HttpMethod.GET,
                    new HttpEntity<>(buildHeaders()),
                    byte[].class);
            return response.getBody();
        } catch (Exception e) {
            log.warn("Nexus downloadArtifact failed for url={}: {}", downloadUrl, e.getMessage());
            return null;
        }
    }

    /** Nexus 연결 가능 여부를 확인한다. */
    public boolean isAvailable() {
        try {
            restTemplate.exchange(
                    getTool().getUrl() + "/service/rest/v1/status", HttpMethod.GET,
                    new HttpEntity<>(buildHeaders()),
                    new ParameterizedTypeReference<NexusSearchResponse>() {});
            return true;
        } catch (Exception e) {
            log.debug("Nexus not available: {}", e.getMessage());
            return false;
        }
    }

    private HttpHeaders buildHeaders() {
        SupportTool tool = getTool();
        HttpHeaders headers = new HttpHeaders();
        String credential = toolRegistry.decodeCredential(tool);
        if (!credential.isBlank() && tool.getUsername() != null) {
            headers.setBasicAuth(tool.getUsername(), credential);
        }
        return headers;
    }
}
