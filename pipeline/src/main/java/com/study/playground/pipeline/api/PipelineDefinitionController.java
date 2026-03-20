package com.study.playground.pipeline.api;

import com.study.playground.pipeline.dto.*;
import com.study.playground.pipeline.service.PipelineDefinitionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * 파이프라인 정의의 CRUD와 실행 트리거를 위한 REST 컨트롤러.
 */
@RestController
@RequestMapping("/api/pipelines")
@RequiredArgsConstructor
public class PipelineDefinitionController {

    private final PipelineDefinitionService service;

    @PostMapping
    public ResponseEntity<PipelineDefinitionResponse> create(
            @Valid @RequestBody PipelineDefinitionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @GetMapping
    public ResponseEntity<List<PipelineDefinitionResponse>> findAll() {
        return ResponseEntity.ok(service.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<PipelineDefinitionResponse> findById(@PathVariable Long id) {
        return ResponseEntity.ok(service.findById(id));
    }

    @PutMapping("/{id}/mappings")
    public ResponseEntity<PipelineDefinitionResponse> updateMappings(
            @PathVariable Long id
            , @Valid @RequestBody List<PipelineJobMappingRequest> mappings) {
        return ResponseEntity.ok(service.updateMappings(id, mappings));
    }

    @GetMapping("/{id}/executions")
    public ResponseEntity<List<PipelineExecutionResponse>> getExecutions(@PathVariable Long id) {
        return ResponseEntity.ok(service.getExecutions(id));
    }

    @GetMapping("/executions/{executionId}")
    public ResponseEntity<PipelineExecutionResponse> getExecution(@PathVariable UUID executionId) {
        return ResponseEntity.ok(service.getExecution(executionId));
    }

    @PostMapping("/{id}/execute")
    public ResponseEntity<PipelineExecutionResponse> execute(@PathVariable Long id) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.execute(id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
