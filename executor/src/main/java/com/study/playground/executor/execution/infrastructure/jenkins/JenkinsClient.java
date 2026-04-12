package com.study.playground.executor.execution.infrastructure.jenkins;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.study.playground.executor.config.ExecutorProperties;
import com.study.playground.executor.execution.domain.model.BuildStatusResult;
import com.study.playground.executor.execution.domain.port.out.JenkinsQueryPort;
import com.study.playground.executor.execution.domain.port.out.JenkinsTriggerPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
@Slf4j
public class JenkinsClient implements JenkinsQueryPort, JenkinsTriggerPort {

    private final JenkinsFeignClient feignClient;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final ExecutorProperties properties;

    private final Map<Long, CachedToolInfo> toolInfoCache = new ConcurrentHashMap<>();
    private final Map<Long, CachedK8sMode> k8sModeCache = new ConcurrentHashMap<>();

    private static final Duration TOOL_CACHE_TTL = Duration.ofSeconds(30);
    private static final Duration K8S_CACHE_TTL = Duration.ofMinutes(5);

    /**
     * health gate를 통과한 Jenkins에 대해 queue/executor 상태를 조회한다.
     * Kubernetes 동적 agent Jenkins는 슬롯 계산 없이 즉시 실행 가능으로 본다.
     */
    @Override
    public boolean isImmediatelyExecutable(long jenkinsInstanceId) {
        if (!isHealthy(jenkinsInstanceId)) {
            return false;
        }

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
        return isHealthy(jenkinsInstanceId);
    }

    @Override
    public boolean isHealthy(long jenkinsInstanceId) {
        var info = getToolInfo(jenkinsInstanceId);
        if (!"HEALTHY".equals(info.healthStatus())) {
            return false;
        }
        if (info.healthCheckedAt() == null) {
            return false;
        }
        var cutoff = LocalDateTime.now().minusMinutes(properties.getJenkinsHealthStalenessMinutes());
        return !info.healthCheckedAt().isBefore(cutoff);
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

    @Override
    public void triggerBuild(long jenkinsInstanceId, String jenkinsJobPath, String jobId) {
        var triggerUri = URI.create(resolveJenkinsUri(jenkinsInstanceId)
                + "/job/" + jenkinsJobPath.replace("/", "/job/")
                + "/buildWithParameters");
        var auth = buildAuthHeader(jenkinsInstanceId);
        // executor 런타임에서는 crumb을 재조회하지 않고 operator가 발급한 API token만 사용한다.
        feignClient.triggerBuild(triggerUri, auth, "JOB_ID=" + jobId);
        log.info("[JenkinsClient] Build triggered: path={}", jenkinsJobPath);
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

    private boolean isK8sDynamic(long jenkinsInstanceId, URI baseUri, String auth) {
        var cached = k8sModeCache.get(jenkinsInstanceId);
        if (cached != null && Instant.now().isBefore(cached.cachedAt().plus(K8S_CACHE_TTL))) {
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

    private JenkinsToolInfo getToolInfo(long jenkinsInstanceId) {
        var cached = toolInfoCache.get(jenkinsInstanceId);
        if (cached != null && Instant.now().isBefore(cached.cachedAt().plus(TOOL_CACHE_TTL))) {
            return cached.info();
        }

        // executor는 cross-schema read model 성격으로 operator.support_tool의 인증/health 컬럼만 읽는다.
        var sql = "SELECT url, username, api_token, max_executors, health_status, health_checked_at "
                + "FROM operator.support_tool WHERE id = ?";
        var info = jdbcTemplate.queryForObject(sql, (rs, rowNum) -> new JenkinsToolInfo(
                rs.getString("url")
                , rs.getString("username")
                , rs.getString("api_token")
                , rs.getInt("max_executors")
                , rs.getString("health_status")
                , rs.getTimestamp("health_checked_at") != null
                        ? rs.getTimestamp("health_checked_at").toLocalDateTime()
                        : null
        ), jenkinsInstanceId);
        toolInfoCache.put(jenkinsInstanceId, new CachedToolInfo(info, Instant.now()));
        return info;
    }

    private URI resolveJenkinsUri(long jenkinsInstanceId) {
        return URI.create(getToolInfo(jenkinsInstanceId).url());
    }

    private String buildAuthHeader(long jenkinsInstanceId) {
        var info = getToolInfo(jenkinsInstanceId);
        if (info.apiToken() == null || info.apiToken().isBlank()) {
            throw new IllegalStateException("Missing Jenkins API token: instanceId=" + jenkinsInstanceId);
        }
        var encoded = Base64.getEncoder().encodeToString(
                (info.username() + ":" + info.apiToken()).getBytes(StandardCharsets.UTF_8));
        return "Basic " + encoded;
    }

    record CachedToolInfo(JenkinsToolInfo info, Instant cachedAt) {}

    record CachedK8sMode(boolean isK8s, Instant cachedAt) {}

    record JenkinsToolInfo(String url,
                           String username,
                           String apiToken,
                           int maxExecutors,
                           String healthStatus,
                           LocalDateTime healthCheckedAt) {}
}
