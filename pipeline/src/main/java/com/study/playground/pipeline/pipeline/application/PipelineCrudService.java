package com.study.playground.pipeline.pipeline.application;

import com.study.playground.pipeline.pipeline.domain.model.Pipeline;
import com.study.playground.pipeline.pipeline.domain.model.PipelineVersion;
import com.study.playground.pipeline.pipeline.domain.port.in.CreatePipelineUseCase;
import com.study.playground.pipeline.pipeline.domain.port.in.DeletePipelineUseCase;
import com.study.playground.pipeline.pipeline.domain.port.in.UpdatePipelineUseCase;
import com.study.playground.pipeline.pipeline.domain.port.out.LoadPipelinePort;
import com.study.playground.pipeline.pipeline.domain.port.out.SavePipelinePort;
import com.study.playground.pipeline.pipeline.domain.service.PipelineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PipelineCrudService implements CreatePipelineUseCase, UpdatePipelineUseCase, DeletePipelineUseCase {

    private final LoadPipelinePort loadPort;
    private final SavePipelinePort savePort;
    private final PipelineService pipelineService;

    @Override
    @Transactional
    public Pipeline create(String projectId, String name, String description, List<String> jobIds, String rgtrId) {
        var pipelineId = UUID.randomUUID().toString().substring(0, 20);
        var pipeline = Pipeline.create(pipelineId, projectId, name, description, rgtrId);
        var saved = savePort.save(pipeline);

        var version = pipelineService.createInitialVersion(saved.getPipelineId(), jobIds, rgtrId);
        savePort.saveVersion(version);

        log.info("[Pipeline] Created: id={}, name={}, steps={}", saved.getPipelineId(), name, jobIds.size());
        return saved;
    }

    @Override
    @Transactional
    public void update(String pipelineId, String name, String description, boolean failContinue
            , List<String> jobIds, String versionDesc, String mdfrId) {
        var pipeline = loadPort.findById(pipelineId)
                .orElseThrow(() -> new IllegalArgumentException("Pipeline not found: " + pipelineId));
        pipelineService.validateNotDeleted(pipeline);

        pipeline.update(name, description, failContinue, mdfrId);
        savePort.save(pipeline);

        int nextVer = loadPort.getNextVersionNumber(pipelineId);
        var version = pipelineService.createNewVersion(pipelineId, nextVer, jobIds, versionDesc, mdfrId);
        savePort.saveVersion(version);

        log.info("[Pipeline] Updated: id={}, newVersion={}", pipelineId, nextVer);
    }

    @Override
    @Transactional
    public void delete(String pipelineId) {
        var pipeline = loadPort.findById(pipelineId)
                .orElseThrow(() -> new IllegalArgumentException("Pipeline not found: " + pipelineId));
        pipeline.softDelete();
        savePort.save(pipeline);
        log.info("[Pipeline] Deleted: id={}", pipelineId);
    }
}
