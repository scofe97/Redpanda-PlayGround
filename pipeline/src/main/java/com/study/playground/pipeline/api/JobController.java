package com.study.playground.pipeline.api;

import com.study.playground.pipeline.dag.dto.PipelineExecuteRequest;
import com.study.playground.pipeline.dto.JobRequest;
import com.study.playground.pipeline.dto.JobResponse;
import com.study.playground.pipeline.dto.PipelineExecutionResponse;
import com.study.playground.pipeline.service.JobService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Job 독립 CRUD와 단독 실행을 위한 REST 컨트롤러.
 */
@RestController
@RequestMapping("/api/jobs")
@RequiredArgsConstructor
public class JobController {

    private final JobService service;

    @PostMapping
    public ResponseEntity<JobResponse> create(
            @Valid @RequestBody JobRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @GetMapping
    public ResponseEntity<List<JobResponse>> findAll() {
        return ResponseEntity.ok(service.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<JobResponse> findById(@PathVariable Long id) {
        return ResponseEntity.ok(service.findById(id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<JobResponse> update(
            @PathVariable Long id
            , @Valid @RequestBody JobRequest request) {
        return ResponseEntity.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/execute")
    public ResponseEntity<PipelineExecutionResponse> execute(
            @PathVariable Long id
            , @RequestBody(required = false) PipelineExecuteRequest request) {
        var params = request != null ? request.getParams() : null;
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.execute(id, params));
    }

    @PostMapping("/{id}/retry-provision")
    public ResponseEntity<Void> retryJenkinsProvision(@PathVariable Long id) {
        service.retryJenkinsProvision(id);
        return ResponseEntity.accepted().build();
    }
}
