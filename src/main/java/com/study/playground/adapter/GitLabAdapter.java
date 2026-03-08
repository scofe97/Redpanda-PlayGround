package com.study.playground.adapter;

import com.study.playground.adapter.dto.GitLabBranch;
import com.study.playground.adapter.dto.GitLabProject;
import com.study.playground.supporttool.domain.SupportTool;
import com.study.playground.supporttool.domain.ToolType;
import com.study.playground.supporttool.service.ToolRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.List;

@Slf4j
@Component
public class GitLabAdapter {

    private final RestTemplate restTemplate;
    private final ToolRegistry toolRegistry;

    public GitLabAdapter(RestTemplate restTemplate, ToolRegistry toolRegistry) {
        this.restTemplate = restTemplate;
        this.toolRegistry = toolRegistry;
    }

    private SupportTool getTool() {
        return toolRegistry.getActiveTool(ToolType.GITLAB);
    }

    public List<GitLabProject> getProjects() {
        try {
            String url = getTool().getUrl() + "/api/v4/projects";
            ResponseEntity<List<GitLabProject>> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    new HttpEntity<>(buildHeaders()),
                    new ParameterizedTypeReference<>() {}
            );
            return response.getBody() != null ? response.getBody() : Collections.emptyList();
        } catch (Exception e) {
            log.error("GitLab getProjects failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    public List<GitLabBranch> getBranches(Long projectId) {
        try {
            String url = getTool().getUrl() + "/api/v4/projects/" + projectId + "/repository/branches";
            ResponseEntity<List<GitLabBranch>> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    new HttpEntity<>(buildHeaders()),
                    new ParameterizedTypeReference<>() {}
            );
            return response.getBody() != null ? response.getBody() : Collections.emptyList();
        } catch (Exception e) {
            log.error("GitLab getBranches failed for projectId={}: {}", projectId, e.getMessage());
            return Collections.emptyList();
        }
    }

    public GitLabProject getProject(Long projectId) {
        try {
            String url = getTool().getUrl() + "/api/v4/projects/" + projectId;
            ResponseEntity<GitLabProject> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    new HttpEntity<>(buildHeaders()),
                    new ParameterizedTypeReference<>() {}
            );
            return response.getBody();
        } catch (Exception e) {
            log.error("GitLab getProject failed for projectId={}: {}", projectId, e.getMessage());
            return null;
        }
    }

    private HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        String credential = toolRegistry.decodeCredential(getTool());
        if (!credential.isBlank()) {
            headers.set("Private-Token", credential);
        }
        return headers;
    }
}
