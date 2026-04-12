package com.study.playground.executor.execution.infrastructure.jenkins;

import com.study.playground.executor.config.ExecutorProperties;
import com.study.playground.executor.execution.domain.model.BuildStatusResult;
import com.study.playground.executor.execution.domain.port.out.JenkinsQueryPort;
import com.study.playground.executor.execution.domain.port.out.JenkinsTriggerPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;

@Component
@RequiredArgsConstructor
public class JenkinsClient implements JenkinsQueryPort, JenkinsTriggerPort {

    private final ExecutorProperties properties;
    private final JenkinsToolInfoReader toolInfoReader;
    private final JenkinsRemoteApiClient remoteApiClient;

    @Override
    public int getMaxExecutors(long jenkinsInstanceId, int activeCount) {
        if (!isHealthy(jenkinsInstanceId)) {
            return 0;
        }

        var info = toolInfoReader.get(jenkinsInstanceId);
        return remoteApiClient.queryMaxExecutors(
                jenkinsInstanceId,
                URI.create(info.url()),
                buildAuthHeader(info),
                properties.getDynamicK8sDispatchCapacity(),
                activeCount
        );
    }

    @Override
    public boolean isReachable(long jenkinsInstanceId) {
        return isHealthy(jenkinsInstanceId);
    }

    @Override
    public boolean isHealthy(long jenkinsInstanceId) {
        var info = toolInfoReader.get(jenkinsInstanceId);
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
    public int queryNextBuildNumber(long jenkinsInstanceId, String jenkinsJobPath) {
        var info = toolInfoReader.get(jenkinsInstanceId);
        return remoteApiClient.queryNextBuildNumber(
                URI.create(info.url()),
                jenkinsJobPath,
                buildAuthHeader(info)
        );
    }

    @Override
    public BuildStatusResult queryBuildStatus(long jenkinsInstanceId, String jenkinsJobPath, int buildNo) {
        var info = toolInfoReader.get(jenkinsInstanceId);
        return remoteApiClient.queryBuildStatus(
                URI.create(info.url()),
                jenkinsJobPath,
                buildNo,
                buildAuthHeader(info)
        );
    }

    @Override
    public void triggerBuild(long jenkinsInstanceId, String jenkinsJobPath, String jobId) {
        var info = toolInfoReader.get(jenkinsInstanceId);
        var triggerUri = URI.create(info.url()
                + "/job/" + jenkinsJobPath.replace("/", "/job/")
                + "/buildWithParameters");
        remoteApiClient.triggerBuild(triggerUri, buildAuthHeader(info), jobId);
    }

    private String buildAuthHeader(JenkinsToolInfoReader.JenkinsToolInfo info) {
        if (info.apiToken() == null || info.apiToken().isBlank()) {
            throw new IllegalStateException("Missing Jenkins API token: instanceId=" + info.instanceId());
        }
        var encoded = Base64.getEncoder().encodeToString(
                (info.username() + ":" + info.apiToken()).getBytes(StandardCharsets.UTF_8));
        return "Basic " + encoded;
    }
}
