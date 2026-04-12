package com.study.playground.operator.pipeline.job.infrastructure.jenkins;

import com.study.playground.operator.pipeline.job.domain.model.JenkinsJobSpec;
import com.study.playground.operator.pipeline.job.domain.port.out.JenkinsApiPort;
import com.study.playground.operator.supporttool.infrastructure.JenkinsFeignClient;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Component
@RequiredArgsConstructor
@Slf4j
public class JenkinsRestAdapter implements JenkinsApiPort {

    private final JenkinsFeignClient jenkinsFeignClient;

    @Override
    public boolean exists(JenkinsJobSpec spec, String apiPath) {
        try {
            var url = URI.create(spec.jenkinsUrl() + apiPath + "/api/json");
            jenkinsFeignClient.getItem(url, buildAuthHeader(spec));
            return true;
        } catch (FeignException.NotFound e) {
            return false;
        } catch (Exception e) {
            log.warn("[JenkinsRest] exists check failed: path={}, error={}", apiPath, e.getMessage());
            return false;
        }
    }

    @Override
    public void createFolder(JenkinsJobSpec spec, String parentPath, String folderName) {
        var url = buildCreateItemUri(spec.jenkinsUrl(), parentPath, folderName);
        jenkinsFeignClient.createItem(url, buildAuthHeader(spec), JenkinsJobSpec.folderConfigXml());
    }

    @Override
    public void createPipelineJob(JenkinsJobSpec spec, String parentPath, String jobName) {
        var url = buildCreateItemUri(spec.jenkinsUrl(), parentPath, jobName);
        jenkinsFeignClient.createItem(url, buildAuthHeader(spec), spec.toConfigXml());
    }

    private URI buildCreateItemUri(String jenkinsUrl, String parentPath, String itemName) {
        return URI.create(jenkinsUrl + parentPath + "/createItem?name="
                + URLEncoder.encode(itemName, StandardCharsets.UTF_8));
    }

    private String buildAuthHeader(JenkinsJobSpec spec) {
        if (spec.apiToken() == null || spec.apiToken().isBlank()) {
            throw new IllegalStateException("Missing Jenkins API token for job creation");
        }
        // operator도 runtime crumb fetch 대신 API token 기반 Basic Auth만 사용한다.
        var auth = Base64.getEncoder().encodeToString(
                (spec.username() + ":" + spec.apiToken()).getBytes(StandardCharsets.UTF_8));
        return "Basic " + auth;
    }
}
