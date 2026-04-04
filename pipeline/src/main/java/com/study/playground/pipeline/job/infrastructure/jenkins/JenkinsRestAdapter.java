package com.study.playground.pipeline.job.infrastructure.jenkins;

import com.study.playground.pipeline.job.domain.model.JenkinsJobSpec;
import com.study.playground.pipeline.job.domain.port.out.JenkinsApiPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Component
@RequiredArgsConstructor
@Slf4j
public class JenkinsRestAdapter implements JenkinsApiPort {

    private final RestTemplate restTemplate;

    @Override
    public boolean exists(JenkinsJobSpec spec, String apiPath) {
        try {
            var url = spec.jenkinsUrl() + apiPath + "/api/json";
            restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(buildHeaders(spec)), String.class);
            return true;
        } catch (HttpClientErrorException.NotFound e) {
            return false;
        } catch (Exception e) {
            log.warn("[JenkinsRest] exists check failed: path={}, error={}", apiPath, e.getMessage());
            return false;
        }
    }

    @Override
    public void createFolder(JenkinsJobSpec spec, String parentPath, String folderName) {
        var url = spec.jenkinsUrl() + parentPath + "/createItem?name=" + folderName;
        var headers = buildHeaders(spec);
        headers.setContentType(MediaType.APPLICATION_XML);
        var request = new HttpEntity<>(JenkinsJobSpec.folderConfigXml(), headers);
        restTemplate.exchange(url, HttpMethod.POST, request, String.class);
    }

    @Override
    public void createPipelineJob(JenkinsJobSpec spec, String parentPath, String jobName) {
        var url = spec.jenkinsUrl() + parentPath + "/createItem?name=" + jobName;
        var headers = buildHeaders(spec);
        headers.setContentType(MediaType.APPLICATION_XML);
        var request = new HttpEntity<>(spec.toConfigXml(), headers);
        restTemplate.exchange(url, HttpMethod.POST, request, String.class);
    }

    private HttpHeaders buildHeaders(JenkinsJobSpec spec) {
        var headers = new HttpHeaders();
        var auth = Base64.getEncoder().encodeToString(
                (spec.username() + ":" + spec.credential()).getBytes(StandardCharsets.UTF_8));
        headers.set("Authorization", "Basic " + auth);
        return headers;
    }
}
