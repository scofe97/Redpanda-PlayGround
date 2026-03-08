package com.study.playground.pipeline.engine.step;

import com.study.playground.adapter.NexusAdapter;
import com.study.playground.adapter.dto.NexusAsset;
import com.study.playground.pipeline.domain.PipelineExecution;
import com.study.playground.pipeline.domain.PipelineStep;
import com.study.playground.pipeline.engine.PipelineStepExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class NexusDownloadStep implements PipelineStepExecutor {

    private static final String DEFAULT_REPOSITORY = "maven-releases";

    private final NexusAdapter nexusAdapter;

    @Override
    public void execute(PipelineStep step) throws Exception {
        log.info("[Real] NexusDownload 시작: {}", step.getStepName());

        String stepName = step.getStepName();

        // Demo: [FAIL] marker - simulate download failure for SAGA demo
        if (stepName != null && stepName.contains("[FAIL]")) {
            log.error("[Real] FAIL 마커 감지 - 의도적 실패 발생: {}", stepName);
            Thread.sleep(2000);
            throw new RuntimeException("Artifact download failed: connection timeout to Nexus (demo failure)");
        }

        if (!nexusAdapter.isAvailable()) {
            log.warn("[Real] Nexus 연결 불가 - Mock 폴백 실행");
            Thread.sleep(2000);
            step.setLog("Downloading artifact from Nexus... done (fallback mock: Nexus unavailable)");
            log.info("[Real] NexusDownload Mock 폴백 완료");
            return;
        }

        // Format: "Download: groupId:artifactId:version:packaging"
        ArtifactCoordinate coord = parseCoordinate(step.getStepName());
        log.info("[Real] Nexus 아티팩트 검색: {}", coord);

        List<NexusAsset> items = nexusAdapter.searchComponents(
                DEFAULT_REPOSITORY, coord.groupId, coord.artifactId, coord.version);

        if (items.isEmpty()) {
            throw new RuntimeException(String.format(
                    "Nexus에서 아티팩트를 찾을 수 없음: %s:%s:%s (repository=%s)",
                    coord.groupId, coord.artifactId, coord.version, DEFAULT_REPOSITORY));
        }

        // Use the first matching asset's download URL
        NexusAsset asset = items.get(0);
        String downloadUrl = asset.downloadUrl();

        if (downloadUrl == null || downloadUrl.isBlank()) {
            throw new RuntimeException("Nexus 아티팩트 downloadUrl 없음: " + asset);
        }

        long startMs = System.currentTimeMillis();
        byte[] content = nexusAdapter.downloadArtifact(downloadUrl);
        long elapsed = System.currentTimeMillis() - startMs;

        if (content == null) {
            throw new RuntimeException("Nexus 아티팩트 다운로드 실패: url=" + downloadUrl);
        }

        step.setLog(String.format(
                "Downloaded %s:%s:%s | size=%d bytes | elapsed=%dms | url=%s",
                coord.groupId, coord.artifactId, coord.version,
                content.length, elapsed, downloadUrl));
        log.info("[Real] NexusDownload 완료: {}", step.getLog());
    }

    private ArtifactCoordinate parseCoordinate(String stepName) {
        // Expected: "Download: com.example:my-app:1.0.0:jar"
        // Falls back to safe defaults if format doesn't match
        if (stepName != null && stepName.contains(":")) {
            String raw = stepName.contains("Download:")
                    ? stepName.substring(stepName.indexOf("Download:") + 9).trim()
                    : stepName;
            String[] parts = raw.split(":");
            if (parts.length >= 3) {
                String packaging = parts.length >= 4 ? parts[3].trim() : "jar";
                return new ArtifactCoordinate(parts[0].trim(), parts[1].trim(), parts[2].trim(), packaging);
            }
        }
        return new ArtifactCoordinate("com.study", "playground-artifact", "1.0.0", "jar");
    }

    @Override
    public void compensate(PipelineExecution execution, PipelineStep step) throws Exception {
        log.info("[SAGA COMPENSATE] Rolling back {}: stepName={}, executionId={}",
                "artifact download",
                step.getStepName(), execution.getId());
        // In production: delete the downloaded artifact from local/shared storage
        // e.g., Files.deleteIfExists(localArtifactPath)
    }

    private record ArtifactCoordinate(String groupId, String artifactId, String version, String packaging) {
        @Override
        public String toString() {
            return groupId + ":" + artifactId + ":" + version + ":" + packaging;
        }
    }
}
