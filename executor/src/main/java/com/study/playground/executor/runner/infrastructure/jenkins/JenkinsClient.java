package com.study.playground.executor.runner.infrastructure.jenkins;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.study.playground.executor.dispatch.domain.port.out.JenkinsQueryPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Jenkins REST API 클라이언트.
 * JenkinsQueryPort 구현 + 빌드 트리거.
 * Jenkins 인스턴스 정보는 cross-schema 쿼리로 SupportTool에서 동적 조회.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JenkinsClient implements JenkinsQueryPort {

    private final JenkinsFeignClient feignClient;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;

    private final Map<Long, JenkinsToolInfo> toolInfoCache = new ConcurrentHashMap<>();
    private final Map<Long, Boolean> k8sModeCache = new ConcurrentHashMap<>();

    // === JenkinsQueryPort 구현 ===

    @Override
    public boolean isImmediatelyExecutable(long jenkinsInstanceId) {
        try {
            var baseUri = resolveJenkinsUri(jenkinsInstanceId);
            var auth = buildAuthHeader(jenkinsInstanceId);

            if (isK8sDynamic(jenkinsInstanceId, baseUri, auth)) {
                return true;
            }

            return isQueueEmpty(baseUri, auth)
                    && hasIdleExecutors(baseUri, auth);
        } catch (Exception e) {
            log.warn("[JenkinsClient] Slot check failed: instanceId={}, error={}"
                    , jenkinsInstanceId, e.getMessage());
            return false;
        }
    }

    @Override
    public int queryNextBuildNumber(long jenkinsInstanceId, String jenkinsJobPath) {
        var baseUri = resolveJenkinsUri(jenkinsInstanceId);
        var auth = buildAuthHeader(jenkinsInstanceId);
        return getNextBuildNumber(baseUri, jenkinsJobPath, auth);
    }

    // === 빌드 트리거 ===

    public int triggerBuild(long jenkinsInstanceId, String jenkinsJobPath, String jobExcnId) {
        var baseUri = resolveJenkinsUri(jenkinsInstanceId);
        var auth = buildAuthHeader(jenkinsInstanceId);
        int nextBuildNo = getNextBuildNumber(baseUri, jenkinsJobPath, auth);

        // crumb + 세션 쿠키를 함께 전송해야 하므로 RestTemplate 사용
        var crumbSession = fetchCrumbWithCookie(baseUri, auth);

        var triggerUrl = baseUri + "/job/" + jenkinsJobPath.replace("/", "/job/")
                + "/buildWithParameters";
        var headers = new HttpHeaders();
        headers.set("Authorization", auth);
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        if (crumbSession != null) {
            headers.set("Jenkins-Crumb", crumbSession.crumb());
            if (crumbSession.cookie() != null) {
                headers.set("Cookie", crumbSession.cookie());
            }
        }

        var request = new HttpEntity<>("EXECUTION_JOB_ID=" + jobExcnId, headers);
        restTemplate.exchange(triggerUrl, HttpMethod.POST, request, String.class);

        log.info("[JenkinsClient] Build triggered: path={}, buildNo={}", jenkinsJobPath, nextBuildNo);
        return nextBuildNo;
    }

    private CrumbSession fetchCrumbWithCookie(URI baseUri, String auth) {
        try {
            var url = baseUri + "/crumbIssuer/api/json";
            var headers = new HttpHeaders();
            headers.set("Authorization", auth);
            var response = restTemplate.exchange(url, HttpMethod.GET
                    , new HttpEntity<>(headers), String.class);
            var node = objectMapper.readTree(response.getBody());
            var cookie = response.getHeaders().getFirst("Set-Cookie");
            return new CrumbSession(node.path("crumb").asText(), cookie);
        } catch (Exception e) {
            log.warn("[JenkinsClient] Crumb fetch failed: {}", e.getMessage());
            return null;
        }
    }

    record CrumbSession(String crumb, String cookie) {}

    // === K8S 감지 ===

    private boolean isK8sDynamic(long jenkinsInstanceId, URI baseUri, String auth) {
        return k8sModeCache.computeIfAbsent(jenkinsInstanceId, id -> {
            try {
                var response = feignClient.getComputerClasses(baseUri, auth);
                var node = objectMapper.readTree(response);

                // K8s dynamic agent: master만 존재하고 totalExecutors=0이면 동적
                int totalExecutors = node.path("totalExecutors").asInt(0);
                if (totalExecutors == 0) {
                    log.info("[JenkinsClient] K8S dynamic detected (totalExecutors=0): instanceId={}", id);
                    return true;
                }
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
    private boolean isQueueEmpty(URI baseUri, String auth) {
        try {
            var response = feignClient.getQueueStatus(baseUri, auth);
            return objectMapper.readTree(response).path("items").isEmpty();
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse queue response", e);
        }
    }

    private boolean hasIdleExecutors(URI baseUri, String auth) {
        try {
            var response = feignClient.getComputerStatus(baseUri, auth);
            var node = objectMapper.readTree(response);
            return node.path("busyExecutors").asInt(0) < node.path("totalExecutors").asInt(0);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse computer response", e);
        }
    }

    private int getNextBuildNumber(URI baseUri, String jobPath, String auth) {
        try {
            var encodedPath = jobPath.replace("/", "/job/");
            var response = feignClient.getJobInfo(baseUri, encodedPath, auth);
            return objectMapper.readTree(response).path("nextBuildNumber").asInt();
        } catch (Exception e) {
            throw new RuntimeException("Failed to get nextBuildNumber", e);
        }
    }


    // === 인증 ===
    private JenkinsToolInfo getToolInfo(long jenkinsInstanceId) {
        return toolInfoCache.computeIfAbsent(jenkinsInstanceId, id -> {
            var sql = "SELECT url, username, credential FROM public.support_tool WHERE id = ?";
            return jdbcTemplate.queryForObject(sql, (rs, rowNum) -> new JenkinsToolInfo(
                    rs.getString("url")
                    , rs.getString("username")
                    , rs.getString("credential")
            ), id);
        });
    }

    private URI resolveJenkinsUri(long jenkinsInstanceId) {
        return URI.create(getToolInfo(jenkinsInstanceId).url());
    }

    private String buildAuthHeader(long jenkinsInstanceId) {
        var info = getToolInfo(jenkinsInstanceId);
        var encoded = Base64.getEncoder().encodeToString(
                (info.username() + ":" + info.credential()).getBytes(StandardCharsets.UTF_8));
        return "Basic " + encoded;
    }

    record JenkinsToolInfo(String url, String username, String credential) {}
}
