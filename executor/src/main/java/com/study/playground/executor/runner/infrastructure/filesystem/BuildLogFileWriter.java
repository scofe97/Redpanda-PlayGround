package com.study.playground.executor.runner.infrastructure.filesystem;

import com.study.playground.executor.runner.domain.port.out.SaveBuildLogPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * SaveBuildLogPort 구현.
 * TPS 구조 차용: {logPath}/{directoryPath}/{jobExcnId}_0
 */
@Component
@Slf4j
public class BuildLogFileWriter implements SaveBuildLogPort {

    @Value("${executor.log-path:/tmp/executor-logs}")
    private String logPath;

    @Override
    public boolean save(String directoryPath, String jobExcnId, String logContent) {
        try {
            var dirPath = Path.of(logPath, directoryPath);
            Files.createDirectories(dirPath);

            var filePath = dirPath.resolve(jobExcnId + "_0");

            try (BufferedWriter writer = Files.newBufferedWriter(
                    filePath, StandardCharsets.UTF_8
                    , StandardOpenOption.CREATE
                    , StandardOpenOption.TRUNCATE_EXISTING
            )) {
                writer.write(logContent);
            }

            log.info("[LogWriter] Log saved: {}", filePath);
            return true;
        } catch (Exception e) {
            log.error("[LogWriter] Failed to save log: dir={}, jobExcnId={}, error={}"
                    , directoryPath, jobExcnId, e.getMessage());
            return false;
        }
    }
}
