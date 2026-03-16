package com.study.playground.adapter;

import com.study.playground.adapter.dto.RegistryCatalog;
import com.study.playground.adapter.dto.RegistryTagList;
import com.study.playground.supporttool.domain.SupportTool;
import com.study.playground.supporttool.domain.ToolType;
import com.study.playground.supporttool.service.ToolRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Collections;
import java.util.List;

@Slf4j
@Component
public class RegistryAdapter {

    private final RestTemplate restTemplate;
    private final ToolRegistry toolRegistry;

    public RegistryAdapter(RestTemplate restTemplate, ToolRegistry toolRegistry) {
        this.restTemplate = restTemplate;
        this.toolRegistry = toolRegistry;
    }

    private SupportTool getTool() {
        return toolRegistry.getActiveTool(ToolType.REGISTRY);
    }

    /** 레지스트리에 해당 이미지:태그가 존재하는지 확인한다. imageName 형식: "repo/image" 또는 "image". */
    public boolean imageExists(String imageName, String tag) {
        try {
            AdapterInputValidator.validatePathParam(imageName, "imageName");
            AdapterInputValidator.validatePathParam(tag, "tag");
            String url = UriComponentsBuilder.fromHttpUrl(getTool().getUrl())
                    .path("/v2/" + imageName + "/manifests/" + tag)
                    .toUriString();
            HttpHeaders headers = buildHeaders();
            headers.set("Accept", "application/vnd.docker.distribution.manifest.v2+json");
            ResponseEntity<Void> response = restTemplate.exchange(
                    url, HttpMethod.HEAD,
                    new HttpEntity<>(headers),
                    Void.class);
            boolean exists = response.getStatusCode().is2xxSuccessful();
            log.info("Registry imageExists: {}:{} -> {}", imageName, tag, exists);
            return exists;
        } catch (Exception e) {
            log.warn("Registry imageExists check failed for {}:{}: {}", imageName, tag, e.getMessage());
            return false;
        }
    }

    /** 레지스트리 카탈로그에서 전체 저장소 이름 목록을 조회한다. 미응답 시 빈 리스트 반환. */
    public List<String> listRepositories() {
        try {
            String url = UriComponentsBuilder.fromHttpUrl(getTool().getUrl())
                    .path("/v2/_catalog")
                    .toUriString();
            ResponseEntity<RegistryCatalog> response = restTemplate.exchange(
                    url, HttpMethod.GET,
                    new HttpEntity<>(buildHeaders()),
                    new ParameterizedTypeReference<>() {});
            RegistryCatalog body = response.getBody();
            if (body != null && body.repositories() != null) {
                return body.repositories();
            }
            return Collections.emptyList();
        } catch (Exception e) {
            log.warn("Registry listRepositories failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /** 지정한 이미지의 태그 목록을 조회한다. 미응답 또는 미발견 시 빈 리스트 반환. */
    public List<String> getTags(String imageName) {
        try {
            AdapterInputValidator.validatePathParam(imageName, "imageName");
            String url = UriComponentsBuilder.fromHttpUrl(getTool().getUrl())
                    .path("/v2/" + imageName + "/tags/list")
                    .toUriString();
            ResponseEntity<RegistryTagList> response = restTemplate.exchange(
                    url, HttpMethod.GET,
                    new HttpEntity<>(buildHeaders()),
                    new ParameterizedTypeReference<>() {});
            RegistryTagList body = response.getBody();
            if (body != null && body.tags() != null) {
                return body.tags();
            }
            return Collections.emptyList();
        } catch (Exception e) {
            log.warn("Registry getTags failed for {}: {}", imageName, e.getMessage());
            return Collections.emptyList();
        }
    }

    /** Docker Registry 연결 가능 여부를 확인한다. */
    public boolean isAvailable() {
        try {
            restTemplate.exchange(
                    getTool().getUrl() + "/v2/", HttpMethod.GET,
                    new HttpEntity<>(buildHeaders()),
                    new ParameterizedTypeReference<RegistryCatalog>() {});
            return true;
        } catch (Exception e) {
            log.debug("Registry not available: {}", e.getMessage());
            return false;
        }
    }

    private HttpHeaders buildHeaders() {
        SupportTool tool = getTool();
        HttpHeaders headers = new HttpHeaders();
        String credential = toolRegistry.decodeCredential(tool);
        if (!credential.isBlank() && tool.getUsername() != null) {
            headers.setBasicAuth(tool.getUsername(), credential);
        }
        return headers;
    }
}
