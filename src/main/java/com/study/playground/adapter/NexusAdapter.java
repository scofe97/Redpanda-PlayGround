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

    /**
     * Searches for assets by groupId, artifactId, version.
     * Returns list of NexusAsset. Returns empty list if Nexus is unavailable or artifact not found.
     */
    public List<NexusAsset> searchComponents(String repository, String groupId,
                                              String artifactId, String version) {
        try {
            StringBuilder urlBuilder = new StringBuilder(getTool().getUrl())
                    .append("/service/rest/v1/search/assets")
                    .append("?repository=").append(repository)
                    .append("&maven.groupId=").append(groupId)
                    .append("&maven.artifactId=").append(artifactId);
            if (version != null && !version.isBlank()) {
                urlBuilder.append("&maven.baseVersion=").append(version);
            }
            String url = urlBuilder.toString();
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

    /**
     * Downloads an artifact by its direct download URL.
     * Returns the byte array content, or null if unavailable.
     */
    public byte[] downloadArtifact(String downloadUrl) {
        try {
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

    /**
     * Checks if Nexus is reachable.
     */
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
