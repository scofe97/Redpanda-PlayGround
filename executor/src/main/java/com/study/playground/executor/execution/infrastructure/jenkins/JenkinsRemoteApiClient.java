package com.study.playground.executor.execution.infrastructure.jenkins;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.study.playground.executor.execution.domain.model.BuildStatusResult;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
@Slf4j
public class JenkinsRemoteApiClient {

    private final JenkinsFeignClient feignClient;
    private final ObjectMapper objectMapper;

    private final Map<Long, CachedK8sMode> k8sModeCache = new ConcurrentHashMap<>();

    private static final Duration K8S_CACHE_TTL = Duration.ofMinutes(5);

    public int queryDispatchCapacity(long jenkinsInstanceId,
                                     URI baseUri,
                                     String auth,
                                     int dynamicK8sDispatchCapacity) {
        if (isK8sDynamic(jenkinsInstanceId, baseUri, auth)) {
            return dynamicK8sDispatchCapacity;
        }
        return getComputerSnapshot(baseUri, auth).totalExecutors();
    }

    public int queryNextBuildNumber(URI baseUri, String jobPath, String auth) {
        try {
            var encodedPath = jobPath.replace("/", "/job/");
            var response = feignClient.getJobInfo(baseUri, encodedPath, auth);
            return objectMapper.readTree(response).path("nextBuildNumber").asInt();
        } catch (Exception e) {
            throw new RuntimeException("Failed to get nextBuildNumber", e);
        }
    }

    public BuildStatusResult queryBuildStatus(URI baseUri, String jenkinsJobPath, int buildNo, String auth) {
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
        } catch (FeignException.NotFound e) {
            return BuildStatusResult.notFound();
        } catch (Exception e) {
            log.warn("[JenkinsRemoteApiClient] queryBuildStatus failed: path={}, buildNo={}, error={}"
                    , jenkinsJobPath, buildNo, e.getMessage());
            return BuildStatusResult.notFound();
        }
    }

    public void triggerBuild(URI triggerUri, String auth, String jobId) {
        feignClient.triggerBuild(triggerUri, auth, "JOB_ID=" + jobId);
        log.info("[JenkinsRemoteApiClient] Build triggered: uri={}", triggerUri);
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

            isK8s = node.path("totalExecutors").asInt(0) == 0;

            for (var computer : node.path("computer")) {
                if (computer.path("_class").asText("").toLowerCase().contains("kubernetes")) {
                    isK8s = true;
                    break;
                }

                for (var label : computer.path("assignedLabels")) {
                    if (label.path("name").asText("").toLowerCase().contains("k8s")) {
                        isK8s = true;
                        break;
                    }
                }

                if (isK8s) {
                    break;
                }
            }
        } catch (Exception e) {
            log.warn("[JenkinsRemoteApiClient] K8S detection failed: instanceId={}", jenkinsInstanceId);
            isK8s = false;
        }

        k8sModeCache.put(jenkinsInstanceId, new CachedK8sMode(isK8s, Instant.now()));
        return isK8s;
    }

    private JenkinsComputerSnapshot getComputerSnapshot(URI baseUri, String auth) {
        try {
            var response = feignClient.getComputerStatus(baseUri, auth);
            var node = objectMapper.readTree(response);
            return new JenkinsComputerSnapshot(
                    node.path("busyExecutors").asInt(0),
                    node.path("totalExecutors").asInt(0)
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse computer response", e);
        }
    }

    record CachedK8sMode(boolean isK8s, Instant cachedAt) {}

    record JenkinsComputerSnapshot(int busyExecutors,
                                   int totalExecutors) {}
}
