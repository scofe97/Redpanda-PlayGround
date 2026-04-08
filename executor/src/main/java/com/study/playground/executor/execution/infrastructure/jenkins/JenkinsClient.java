package com.study.playground.executor.execution.infrastructure.jenkins;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.study.playground.executor.execution.domain.model.BuildStatusResult;
import com.study.playground.executor.execution.domain.port.out.JenkinsQueryPort;
import com.study.playground.executor.execution.domain.port.out.JenkinsTriggerPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Jenkins REST API 클라이언트.
 * JenkinsQueryPort + JenkinsTriggerPort 구현.
 * Jenkins 인스턴스 정보는 cross-schema 쿼리로 SupportTool에서 동적 조회.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JenkinsClient implements JenkinsQueryPort, JenkinsTriggerPort {

    private final JenkinsFeignClient feignClient;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;

    private final Map<Long, CachedToolInfo> toolInfoCache = new ConcurrentHashMap<>();
    private final Map<Long, CachedK8sMode> k8sModeCache = new ConcurrentHashMap<>();

    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

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
    public boolean isReachable(long jenkinsInstanceId) {
        try {
            var baseUri = resolveJenkinsUri(jenkinsInstanceId);
            var auth = buildAuthHeader(jenkinsInstanceId);
            feignClient.getComputerStatus(baseUri, auth);
            return true;
        } catch (Exception e) {
            log.warn("[JenkinsClient] Unreachable: instanceId={}, error={}"
                    , jenkinsInstanceId, e.getMessage());
            return false;
        }
    }

    @Override
    public int getMaxExecutors(long jenkinsInstanceId) {
        return getToolInfo(jenkinsInstanceId).maxExecutors();
    }

    @Override
    public int queryNextBuildNumber(long jenkinsInstanceId, String jenkinsJobPath) {
        var baseUri = resolveJenkinsUri(jenkinsInstanceId);
        var auth = buildAuthHeader(jenkinsInstanceId);
        return getNextBuildNumber(baseUri, jenkinsJobPath, auth);
    }

    @Override
    public BuildStatusResult queryBuildStatus(long jenkinsInstanceId, String jenkinsJobPath, int buildNo) {
        var baseUri = resolveJenkinsUri(jenkinsInstanceId);
        var auth = buildAuthHeader(jenkinsInstanceId);
        var encodedPath = jenkinsJobPath.replace("/", "/job/");
        try {
            var response = feignClient.getBuildInfo(baseUri, encodedPath, buildNo, auth);
            var node = objectMapper.readTree(response);
            boolean building = node.path("building").asBoolean(false);
            if (building) {
                return BuildStatusResult.building();
            }
            String result = node.path("result").asText(null);
            return BuildStatusResult.completed(result);
        } catch (feign.FeignException.NotFound e) {
            return BuildStatusResult.notFound();
        } catch (Exception e) {
            log.warn("[JenkinsClient] queryBuildStatus failed: path={}, buildNo={}, error={}"
                    , jenkinsJobPath, buildNo, e.getMessage());
            return BuildStatusResult.notFound();
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

    // === 빌드 트리거 ===

    public void triggerBuild(long jenkinsInstanceId, String jenkinsJobPath, String jobId) {
        var baseUri = resolveJenkinsUri(jenkinsInstanceId);
        var auth = buildAuthHeader(jenkinsInstanceId);

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

        var request = new HttpEntity<>("JOB_ID=" + jobId, headers);
        restTemplate.exchange(triggerUrl, HttpMethod.POST, request, String.class);

        log.info("[JenkinsClient] Build triggered: path={}", jenkinsJobPath);
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
        var cached = k8sModeCache.get(jenkinsInstanceId);
        if (cached != null && Instant.now().isBefore(cached.cachedAt().plus(CACHE_TTL))) {
            return cached.isK8s();
        }

        boolean isK8s;
        try {
            var response = feignClient.getComputerClasses(baseUri, auth);
            var node = objectMapper.readTree(response);

            int totalExecutors = node.path("totalExecutors").asInt(0);
            if (totalExecutors == 0) {
                log.info("[JenkinsClient] K8S dynamic detected (totalExecutors=0): instanceId={}", jenkinsInstanceId);
                isK8s = true;
            } else {
                isK8s = false;
                for (var computer : node.path("computer")) {
                    if (computer.path("_class").asText("").toLowerCase().contains("kubernetes")) {
                        log.info("[JenkinsClient] K8S dynamic detected: instanceId={}", jenkinsInstanceId);
                        isK8s = true;
                        break;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[JenkinsClient] K8S detection failed: instanceId={}", jenkinsInstanceId);
            isK8s = false;
        }

        k8sModeCache.put(jenkinsInstanceId, new CachedK8sMode(isK8s, Instant.now()));
        return isK8s;
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

    // === 인증 ===
    private JenkinsToolInfo getToolInfo(long jenkinsInstanceId) {
        var cached = toolInfoCache.get(jenkinsInstanceId);
        if (cached != null && Instant.now().isBefore(cached.cachedAt().plus(CACHE_TTL))) {
            return cached.info();
        }

        var sql = "SELECT url, username, credential, max_executors FROM operator.support_tool WHERE id = ?";
        var info = jdbcTemplate.queryForObject(sql, (rs, rowNum) -> new JenkinsToolInfo(
                rs.getString("url")
                , rs.getString("username")
                , rs.getString("credential")
                , rs.getInt("max_executors")
        ), jenkinsInstanceId);
        toolInfoCache.put(jenkinsInstanceId, new CachedToolInfo(info, Instant.now()));
        return info;
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

    record CachedToolInfo(JenkinsToolInfo info, Instant cachedAt) {}

    record CachedK8sMode(boolean isK8s, Instant cachedAt) {}

    record JenkinsToolInfo(String url, String username, String credential, int maxExecutors) {}
}
