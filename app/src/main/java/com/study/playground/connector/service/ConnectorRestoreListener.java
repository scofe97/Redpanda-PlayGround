package com.study.playground.connector.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;

@Slf4j
@Component
@RequiredArgsConstructor
public class ConnectorRestoreListener {

    private final ConnectorManager connectorManager;

    private static final int MAX_RETRIES = 5;
    private static final long INITIAL_DELAY_MS = 2000;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        Executors.newSingleThreadExecutor().submit(this::restoreWithRetry);
    }

    private void restoreWithRetry() {
        long delay = INITIAL_DELAY_MS;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                connectorManager.restoreConnectors();
                return;
            } catch (Exception e) {
                log.warn("커넥터 복원 실패 (시도 {}/{}): {}", attempt, MAX_RETRIES, e.getMessage());
                if (attempt < MAX_RETRIES) {
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    delay *= 2;
                }
            }
        }
        log.error("커넥터 복원 최종 실패: {}회 재시도 후 포기", MAX_RETRIES);
    }
}
