package com.study.playground.executor.runner.infrastructure.jenkins;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.study.playground.executor.dispatch.domain.port.out.JenkinsQueryPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Jenkins REST API 클라이언트.
 * JenkinsQueryPort 구현 + 빌드 트리거 + Crumb 캐싱.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JenkinsClient implements JenkinsQueryPort {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    // TODO: SupportTool에서 동적 조회
    private static final String DEFAULT_JENKINS_URL = "http://localhost:8080";
    private static final String DEFAULT_USERNAME = "admin";
    private static final String DEFAULT_API_TOKEN = "admin";

    private final Map<Long, CrumbSession> crumbCache = new ConcurrentHashMap<>();
    private final Map<Long, Boolean> k8sModeCache = new ConcurrentHashMap<>();

    // === CheckSlotAvailablePort 구현 ===

    @Override
    public boolean isImmediatelyExecutable(long jenkinsInstanceId) {
        try {
            var baseUrl = resolveJenkinsUrl(jenkinsInstanceId);

            // K8S Dynamic 자동 감지
            if (isK8sDynamic(jenkinsInstanceId, baseUrl)) {
                // TODO: app 레벨 판단 (max_executors - 활성 Job 수)
                return true;
            }

            // VM/정적: queue empty && busy < total
            return isQueueEmpty(baseUrl, jenkinsInstanceId)
                    && hasIdleExecutors(baseUrl, jenkinsInstanceId);
        } catch (Exception e) {
            log.warn("[JenkinsClient] Slot check failed: instanceId={}, error={}"
                    , jenkinsInstanceId, e.getMessage());
            return false; // API 실패 시 실행하지 않고 PENDING 유지
        }
    }

    // === QueryNextBuildNumberPort 구현 ===

    @Override
    public int queryNextBuildNumber(long jenkinsInstanceId, String jenkinsJobPath) {
        var baseUrl = resolveJenkinsUrl(jenkinsInstanceId);
        return getNextBuildNumber(baseUrl, jenkinsJobPath, jenkinsInstanceId);
    }

    // === ResolveJenkinsInstancePort 구현 ===

    @Override
    public long resolveJenkinsInstance(String jobId) {
        // TODO: Job → Preset → PurposeEntry(CI_CD_TOOL) → SupportTool.id 조회
        // 현재는 단일 Jenkins 기준으로 1L 반환
        return 1L;
    }

    // === 빌드 트리거 ===

    public int triggerBuild(long jenkinsInstanceId, String jobName, String jobExcnId) {
        var baseUrl = resolveJenkinsUrl(jenkinsInstanceId);
        int nextBuildNo = getNextBuildNumber(baseUrl, jobName, jenkinsInstanceId);

        var triggerUrl = baseUrl + "/job/" + jobName + "/buildWithParameters";
        var headers = buildPostHeaders(jenkinsInstanceId);
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        var params = "EXECUTION_JOB_ID=" + jobExcnId;
        var request = new HttpEntity<>(params, headers);

        var response = restTemplate.exchange(triggerUrl, HttpMethod.POST, request, String.class);
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new RuntimeException("Jenkins returned HTTP " + response.getStatusCode());
        }

        log.info("[JenkinsClient] Build triggered: jobName={}, buildNo={}", jobName, nextBuildNo);
        return nextBuildNo;
    }

    // === K8S 감지 ===

    private boolean isK8sDynamic(long jenkinsInstanceId, String baseUrl) {
        return k8sModeCache.computeIfAbsent(jenkinsInstanceId, id -> {
            try {
                var url = baseUrl + "/computer/api/json?tree=computer[_class]";
                var request = new HttpEntity<>(buildGetHeaders(id));
                var response = restTemplate.exchange(url, HttpMethod.GET, request, String.class);
                var node = objectMapper.readTree(response.getBody());

                for (var computer : node.path("computer")) {
                    if (computer.path("_class").asText("").toLowerCase().contains("kubernetes")) {
                        log.info("[JenkinsClient] K8S dynamic detected: instanceId={}", id);
                        return true;
                    }
                }
                return false;
            } catch (Exception e) {
                log.warn("[JenkinsClient] K8S detection failed: instanceId={}", id);
                return false;
            }
        });
    }

    // === Internal ===

    private boolean isQueueEmpty(String baseUrl, long jenkinsInstanceId) {
        var url = baseUrl + "/queue/api/json?tree=items[id]";
        var request = new HttpEntity<>(buildGetHeaders(jenkinsInstanceId));
        var response = restTemplate.exchange(url, HttpMethod.GET, request, String.class);
        try {
            return objectMapper.readTree(response.getBody()).path("items").isEmpty();
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse queue response", e);
        }
    }

    private boolean hasIdleExecutors(String baseUrl, long jenkinsInstanceId) {
        var url = baseUrl + "/computer/api/json?tree=busyExecutors,totalExecutors";
        var request = new HttpEntity<>(buildGetHeaders(jenkinsInstanceId));
        var response = restTemplate.exchange(url, HttpMethod.GET, request, String.class);
        try {
            var node = objectMapper.readTree(response.getBody());
            return node.path("busyExecutors").asInt(0) < node.path("totalExecutors").asInt(0);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse computer response", e);
        }
    }

    private int getNextBuildNumber(String baseUrl, String jobName, long jenkinsInstanceId) {
        var url = baseUrl + "/job/" + jobName + "/api/json?tree=nextBuildNumber";
        var request = new HttpEntity<>(buildGetHeaders(jenkinsInstanceId));
        var response = restTemplate.exchange(url, HttpMethod.GET, request, String.class);
        try {
            return objectMapper.readTree(response.getBody()).path("nextBuildNumber").asInt();
        } catch (Exception e) {
            throw new RuntimeException("Failed to get nextBuildNumber", e);
        }
    }

    // === 인증 ===

    private HttpHeaders buildGetHeaders(long jenkinsInstanceId) {
        var headers = new HttpHeaders();
        headers.set("Authorization", "Basic " + getBasicAuth(jenkinsInstanceId));
        return headers;
    }

    private HttpHeaders buildPostHeaders(long jenkinsInstanceId) {
        var headers = buildGetHeaders(jenkinsInstanceId);
        var crumb = getCrumb(jenkinsInstanceId);
        if (crumb != null) {
            headers.set(crumb.crumbRequestField, crumb.crumb);
            headers.set("Cookie", crumb.cookie);
        }
        return headers;
    }

    private CrumbSession getCrumb(long jenkinsInstanceId) {
        return crumbCache.computeIfAbsent(jenkinsInstanceId, this::fetchCrumb);
    }

    private CrumbSession fetchCrumb(long jenkinsInstanceId) {
        try {
            var url = resolveJenkinsUrl(jenkinsInstanceId) + "/crumbIssuer/api/json";
            var headers = new HttpHeaders();
            headers.set("Authorization", "Basic " + getBasicAuth(jenkinsInstanceId));
            var response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            var node = objectMapper.readTree(response.getBody());
            var cookie = response.getHeaders().getFirst("Set-Cookie");
            return new CrumbSession(
                    node.path("crumb").asText()
                    , node.path("crumbRequestField").asText("Jenkins-Crumb")
                    , cookie
            );
        } catch (Exception e) {
            log.warn("[JenkinsClient] Crumb fetch failed (may not be required): {}", e.getMessage());
            return null;
        }
    }

    private String getBasicAuth(long jenkinsInstanceId) {
        return Base64.getEncoder().encodeToString(
                (DEFAULT_USERNAME + ":" + DEFAULT_API_TOKEN).getBytes(StandardCharsets.UTF_8));
    }

    private String resolveJenkinsUrl(long jenkinsInstanceId) {
        return DEFAULT_JENKINS_URL;
    }

    record CrumbSession(String crumb, String crumbRequestField, String cookie) {}
}
