package com.study.playground.adapter;

import com.study.playground.adapter.dto.JenkinsBuildInfo;
import com.study.playground.supporttool.domain.SupportTool;
import com.study.playground.supporttool.domain.ToolType;
import com.study.playground.supporttool.service.ToolRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Component
public class JenkinsAdapter {

    private final RestTemplate restTemplate;
    private final ToolRegistry toolRegistry;

    public JenkinsAdapter(RestTemplate restTemplate, ToolRegistry toolRegistry) {
        this.restTemplate = restTemplate;
        this.toolRegistry = toolRegistry;
    }

    private SupportTool getTool() {
        return toolRegistry.getActiveTool(ToolType.JENKINS);
    }

    /**
     * Returns build info for a given job and build number (Query).
     */
    public JenkinsBuildInfo getBuildInfo(String jobName, int buildNumber) {
        try {
            String url = getTool().getUrl() + "/job/" + jobName + "/" + buildNumber + "/api/json";
            ResponseEntity<JenkinsBuildInfo> response = restTemplate.exchange(
                    url, HttpMethod.GET,
                    new HttpEntity<>(buildHeaders()),
                    new ParameterizedTypeReference<>() {});
            return response.getBody();
        } catch (Exception e) {
            log.warn("Jenkins getBuildInfo failed: job={}, build={}: {}", jobName, buildNumber, e.getMessage());
            return null;
        }
    }

    /**
     * Returns the last build number for a job (Query).
     */
    public int getLastBuildNumber(String jobName) {
        try {
            String url = getTool().getUrl() + "/job/" + jobName + "/lastBuild/api/json";
            ResponseEntity<JenkinsBuildInfo> response = restTemplate.exchange(
                    url, HttpMethod.GET,
                    new HttpEntity<>(buildHeaders()),
                    new ParameterizedTypeReference<>() {});
            JenkinsBuildInfo body = response.getBody();
            return body != null ? body.number() : -1;
        } catch (Exception e) {
            log.warn("Jenkins getLastBuildNumber failed for job={}: {}", jobName, e.getMessage());
            return -1;
        }
    }

    /**
     * Checks if Jenkins is reachable (Query).
     */
    public boolean isAvailable() {
        try {
            SupportTool tool = getTool();
            restTemplate.exchange(
                    tool.getUrl() + "/api/json", HttpMethod.GET,
                    new HttpEntity<>(buildHeaders()),
                    new ParameterizedTypeReference<JenkinsBuildInfo>() {});
            return true;
        } catch (Exception e) {
            log.debug("Jenkins not available: {}", e.getMessage());
            return false;
        }
    }

    private HttpHeaders buildHeaders() {
        SupportTool tool = getTool();
        HttpHeaders headers = new HttpHeaders();
        String credential = toolRegistry.decodeCredential(tool);
        if (!credential.isBlank()) {
            headers.setBasicAuth(tool.getUsername(), credential);
        }
        return headers;
    }
}
