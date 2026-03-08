package com.study.playground.connector.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Component
public class ConnectStreamsClient {

    private final RestTemplate restTemplate;
    private final String connectBaseUrl;

    public ConnectStreamsClient(RestTemplate restTemplate,
                               @Value("${app.connect.url:http://localhost:4195}") String connectBaseUrl) {
        this.restTemplate = restTemplate;
        this.connectBaseUrl = connectBaseUrl;
    }

    public boolean registerStream(String streamId, String yamlConfig) {
        try {
            String url = connectBaseUrl + "/streams/" + streamId;
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.valueOf("application/yaml"));
            HttpEntity<String> entity = new HttpEntity<>(yamlConfig, headers);
            restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            log.info("Connect 스트림 등록 성공: {}", streamId);
            return true;
        } catch (Exception e) {
            log.warn("Connect 스트림 등록 실패: {}: {}", streamId, e.getMessage());
            return false;
        }
    }

    public boolean deleteStream(String streamId) {
        try {
            String url = connectBaseUrl + "/streams/" + streamId;
            restTemplate.exchange(url, HttpMethod.DELETE, null, String.class);
            log.info("Connect 스트림 삭제 성공: {}", streamId);
            return true;
        } catch (Exception e) {
            log.warn("Connect 스트림 삭제 실패: {}: {}", streamId, e.getMessage());
            return false;
        }
    }
}
