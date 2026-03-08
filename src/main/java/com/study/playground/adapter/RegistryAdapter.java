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

    /**
     * Checks whether an image with the given name and tag exists in the registry.
     * imageName format: "repo/image" or just "image"; tag: "latest", "1.0.0", etc.
     */
    public boolean imageExists(String imageName, String tag) {
        try {
            String url = getTool().getUrl() + "/v2/" + imageName + "/manifests/" + tag;
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

    /**
     * Returns all repository names from the registry catalog.
     * Returns empty list if registry is unavailable.
     */
    public List<String> listRepositories() {
        try {
            String url = getTool().getUrl() + "/v2/_catalog";
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

    /**
     * Returns the list of tags for a given image repository.
     * Returns empty list if registry is unavailable or image not found.
     */
    public List<String> getTags(String imageName) {
        try {
            String url = getTool().getUrl() + "/v2/" + imageName + "/tags/list";
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

    /**
     * Checks if the registry is reachable.
     */
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
