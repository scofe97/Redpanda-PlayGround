package com.study.playground.connector.service;

import com.study.playground.connector.client.ConnectStreamsClient;
import com.study.playground.connector.domain.ConnectorConfig;
import com.study.playground.connector.mapper.ConnectorConfigMapper;
import com.study.playground.supporttool.domain.SupportTool;
import com.study.playground.supporttool.domain.ToolType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConnectorManager {

    private final ConnectorConfigMapper connectorConfigMapper;
    private final ConnectStreamsClient connectStreamsClient;

    private static final Map<ToolType, String> COMMAND_TEMPLATES = Map.of(
            ToolType.JENKINS, "connect-templates/jenkins-command.yaml",
            ToolType.GITLAB, "connect-templates/gitlab-command.yaml",
            ToolType.NEXUS, "connect-templates/nexus-command.yaml"
    );

    private static final String WEBHOOK_TEMPLATE = "connect-templates/webhook-inbound.yaml";

    public void createConnectors(SupportTool tool) {
        if (tool.getToolType() == ToolType.REGISTRY) {
            log.debug("REGISTRY 타입은 커넥터 생성 스킵: {}", tool.getName());
            return;
        }

        String toolType = tool.getToolType().name().toLowerCase();
        String toolId = String.valueOf(tool.getId());
        String webhookStreamId = toolType + "-" + toolId + "-webhook";
        String commandStreamId = toolType + "-" + toolId + "-command";

        // 1. Webhook 커넥터
        String webhookYaml = loadAndReplace(WEBHOOK_TEMPLATE, tool);
        boolean webhookOk = connectStreamsClient.registerStream(webhookStreamId, webhookYaml);
        if (!webhookOk) {
            throw new RuntimeException("Webhook 커넥터 등록 실패: " + webhookStreamId);
        }

        // 2. Command 커넥터
        String commandTemplate = COMMAND_TEMPLATES.get(tool.getToolType());
        String commandYaml = loadAndReplace(commandTemplate, tool);
        boolean commandOk = connectStreamsClient.registerStream(commandStreamId, commandYaml);
        if (!commandOk) {
            // 보상 삭제: webhook 롤백
            connectStreamsClient.deleteStream(webhookStreamId);
            throw new RuntimeException("Command 커넥터 등록 실패 (webhook 롤백 완료): " + commandStreamId);
        }

        // 3. DB 저장
        saveConfig(webhookStreamId, tool.getId(), webhookYaml, "INBOUND");
        saveConfig(commandStreamId, tool.getId(), commandYaml, "OUTBOUND");

        log.info("커넥터 생성 완료: tool={}, webhook={}, command={}", tool.getName(), webhookStreamId, commandStreamId);
    }

    public void deleteConnectors(Long toolId) {
        List<ConnectorConfig> configs = connectorConfigMapper.findByToolId(toolId);
        for (ConnectorConfig config : configs) {
            connectStreamsClient.deleteStream(config.getStreamId());
        }
        connectorConfigMapper.deleteByToolId(toolId);
        log.info("커넥터 삭제 완료: toolId={}, 삭제 건수={}", toolId, configs.size());
    }

    public void restoreConnectors() {
        List<ConnectorConfig> configs = connectorConfigMapper.findAll();
        if (configs.isEmpty()) {
            log.info("복원할 커넥터 없음");
            return;
        }

        int success = 0;
        int fail = 0;
        for (ConnectorConfig config : configs) {
            boolean ok = connectStreamsClient.registerStream(config.getStreamId(), config.getYamlConfig());
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
            ClassPathResource resource = new ClassPathResource(templatePath);
            String template = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return template
                    .replace("${TOOL_TYPE}", tool.getToolType().name().toLowerCase())
                    .replace("${TOOL_ID}", String.valueOf(tool.getId()))
                    .replace("${TOOL_URL}", tool.getUrl())
                    .replace("${TOOL_USERNAME}", tool.getUsername() != null ? tool.getUsername() : "")
                    .replace("${TOOL_CREDENTIAL}", tool.getCredential() != null ? tool.getCredential() : "");
        } catch (IOException e) {
            throw new RuntimeException("템플릿 로드 실패: " + templatePath, e);
        }
    }

    private void saveConfig(String streamId, Long toolId, String yamlConfig, String direction) {
        ConnectorConfig config = new ConnectorConfig();
        config.setStreamId(streamId);
        config.setToolId(toolId);
        config.setYamlConfig(yamlConfig);
        config.setDirection(direction);
        connectorConfigMapper.insert(config);
    }
}
