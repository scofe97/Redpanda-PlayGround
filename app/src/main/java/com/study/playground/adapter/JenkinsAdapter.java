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
import org.springframework.web.util.UriComponentsBuilder;

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

    /** 지정한 Job과 빌드 번호의 빌드 정보를 조회한다. */
    public JenkinsBuildInfo getBuildInfo(String jobName, int buildNumber) {
        try {
            AdapterInputValidator.validatePathParam(jobName, "jobName");
            String url = UriComponentsBuilder.fromHttpUrl(getTool().getUrl())
                    .pathSegment("job", jobName, String.valueOf(buildNumber), "api/json")
                    .toUriString();
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

    /** Job의 마지막 빌드 번호를 조회한다. 실패 시 -1 반환. */
    public int getLastBuildNumber(String jobName) {
        try {
            AdapterInputValidator.validatePathParam(jobName, "jobName");
            String url = UriComponentsBuilder.fromHttpUrl(getTool().getUrl())
                    .pathSegment("job", jobName, "lastBuild", "api/json")
                    .toUriString();
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

    /** Jenkins 연결 가능 여부를 확인한다. */
    public boolean isAvailable() {
        try {
            SupportTool tool = getTool();
            restTemplate.exchange(
                    tool.getUrl() + "/api/json", HttpMethod.GET,
                    new HttpEntity<>(buildHeaders()),
                    String.class);
            return true;
        } catch (Exception e) {
            log.debug("Jenkins not available: {}", e.getMessage());
            return false;
        }
    }

    /** 지정한 Job과 빌드 번호의 콘솔 로그 전문을 조회한다. */
    public String getConsoleLog(String jobName, int buildNumber) {
        try {
            AdapterInputValidator.validatePathParam(jobName, "jobName");
            String url = UriComponentsBuilder.fromHttpUrl(getTool().getUrl())
                    .pathSegment("job", jobName, String.valueOf(buildNumber), "consoleText")
                    .toUriString();
            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET,
                    new HttpEntity<>(buildHeaders()),
                    String.class);
            return response.getBody();
        } catch (Exception e) {
            log.warn("Jenkins getConsoleLog failed: job={}, build={}: {}", jobName, buildNumber, e.getMessage());
            return null;
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
