package com.study.playground.connector.event;

import com.study.playground.connector.service.ConnectorManager;
import com.study.playground.supporttool.event.SupportToolEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * SupportTool 이벤트를 구독하여 커넥터를 생성/삭제한다.
 * supporttool → connector 직접 의존을 제거하기 위한 이벤트 리스너.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConnectorEventListener {

    private final ConnectorManager connectorManager;

    @EventListener
    public void onToolCreated(SupportToolEvent.Created event) {
        try {
            connectorManager.createConnectors(event.tool());
        } catch (Exception e) {
            log.warn("커넥터 생성 실패 tool={}: {}", event.tool().getId(), e.getMessage());
        }
    }

    @EventListener
    public void onToolDeleted(SupportToolEvent.Deleted event) {
        try {
            connectorManager.deleteConnectors(event.toolId());
        } catch (Exception e) {
            log.warn("커넥터 삭제 실패 toolId={}: {}", event.toolId(), e.getMessage());
        }
    }
}
