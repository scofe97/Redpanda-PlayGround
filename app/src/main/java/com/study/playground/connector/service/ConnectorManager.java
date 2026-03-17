package com.study.playground.connector.service;

import com.study.playground.connector.client.ConnectStreamsClient;
import com.study.playground.connector.domain.ConnectorConfig;
import com.study.playground.connector.mapper.ConnectorConfigMapper;
import com.study.playground.supporttool.domain.SupportTool;
import com.study.playground.supporttool.domain.ToolCategory;
import com.study.playground.supporttool.domain.ToolImplementation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConnectorManager {

    private final ConnectorConfigMapper connectorConfigMapper;
    private final ConnectStreamsClient connectStreamsClient;

    private static final Map<ToolImplementation, String> COMMAND_TEMPLATES = Map.of(
            ToolImplementation.JENKINS, "connect-templates/jenkins-command.yaml",
            ToolImplementation.GITLAB, "connect-templates/gitlab-command.yaml",
            ToolImplementation.NEXUS, "connect-templates/nexus-command.yaml"
    );

    /** 커넥터 생성이 불필요한 카테고리. CONTAINER_REGISTRY는 HTTP 브릿지가 필요 없다. */
    private static final Set<ToolCategory> SKIP_CATEGORIES = Set.of(
            ToolCategory.CONTAINER_REGISTRY,
            ToolCategory.STORAGE
    );

    private static final String WEBHOOK_TEMPLATE = "connect-templates/webhook-inbound.yaml";

    public void createConnectors(SupportTool tool) {
        if (SKIP_CATEGORIES.contains(tool.getCategory())) {
            log.debug("{} 카테고리는 커넥터 생성 스킵: {}", tool.getCategory(), tool.getName());
            return;
        }

        var implName = tool.getImplementation().name().toLowerCase();
        var toolId = String.valueOf(tool.getId());
        var webhookStreamId = implName + "-" + toolId + "-webhook";
        var commandStreamId = implName + "-" + toolId + "-command";

        // 1. Webhook 커넥터
        var webhookYaml = loadAndReplace(WEBHOOK_TEMPLATE, tool);
        var webhookOk = connectStreamsClient.registerStream(webhookStreamId, webhookYaml);
        if (!webhookOk) {
            throw new RuntimeException("Webhook 커넥터 등록 실패: " + webhookStreamId);
        }

        // 2. Command 커넥터
        var commandTemplate = COMMAND_TEMPLATES.get(tool.getImplementation());
        if (commandTemplate == null) {
            log.debug("커맨드 템플릿이 없는 구현체: {}", implName);
            saveConfig(webhookStreamId, tool.getId(), webhookYaml, "INBOUND");
            return;
        }
        var commandYaml = loadAndReplace(commandTemplate, tool);
        var commandOk = connectStreamsClient.registerStream(commandStreamId, commandYaml);
        if (!commandOk) {
            connectStreamsClient.deleteStream(webhookStreamId);
            throw new RuntimeException("Command 커넥터 등록 실패 (webhook 롤백 완료): " + commandStreamId);
        }

        // 3. DB 저장
        saveConfig(webhookStreamId, tool.getId(), webhookYaml, "INBOUND");
        saveConfig(commandStreamId, tool.getId(), commandYaml, "OUTBOUND");

        log.info("커넥터 생성 완료: tool={}, webhook={}, command={}", tool.getName(), webhookStreamId, commandStreamId);
    }

    public void deleteConnectors(Long toolId) {
        var configs = connectorConfigMapper.findByToolId(toolId);
        for (var config : configs) {
            connectStreamsClient.deleteStream(config.getStreamId());
        }
        connectorConfigMapper.deleteByToolId(toolId);
        log.info("커넥터 삭제 완료: toolId={}, 삭제 건수={}", toolId, configs.size());
    }

    public void restoreConnectors() {
        var configs = connectorConfigMapper.findAll();
        if (configs.isEmpty()) {
            log.info("복원할 커넥터 없음");
            return;
        }

        var success = 0;
        var fail = 0;
        for (var config : configs) {
            var ok = connectStreamsClient.registerStream(config.getStreamId(), config.getYamlConfig());
            if (ok) {
                success++;
            } else {
                fail++;
            }
        }
        log.info("커넥터 복원 완료: 성공={}, 실패={}, 전체={}", success, fail, configs.size());
    }

    private String loadAndReplace(String templatePath, SupportTool tool) {
        try {
            var resource = new ClassPathResource(templatePath);
            var template = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            var implName = tool.getImplementation().name().toLowerCase();
            return template
                    .replace("${TOOL_TYPE}", implName)
                    .replace("${TOOL_ID}", String.valueOf(tool.getId()))
                    .replace("${TOOL_URL}", tool.getUrl())
                    .replace("${TOOL_USERNAME}", tool.getUsername() != null ? tool.getUsername() : "")
                    .replace("${TOOL_CREDENTIAL}", tool.getCredential() != null ? tool.getCredential() : "");
        } catch (IOException e) {
            throw new RuntimeException("템플릿 로드 실패: " + templatePath, e);
        }
    }

    private void saveConfig(String streamId, Long toolId, String yamlConfig, String direction) {
        var config = new ConnectorConfig();
        config.setStreamId(streamId);
        config.setToolId(toolId);
        config.setYamlConfig(yamlConfig);
        config.setDirection(direction);
        connectorConfigMapper.insert(config);
    }
}
