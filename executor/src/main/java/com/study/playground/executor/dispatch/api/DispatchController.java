package com.study.playground.executor.dispatch.api;

import com.study.playground.executor.dispatch.domain.model.ExecutionJob;
import com.study.playground.executor.dispatch.domain.model.ExecutionJobStatus;
import com.study.playground.executor.dispatch.domain.port.out.ExecutionJobPort;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/executor")
@RequiredArgsConstructor
public class DispatchController {

    private final ExecutionJobPort jobPort;

    @GetMapping("/jobs")
    public ResponseEntity<List<ExecutionJob>> listJobs(
            @RequestParam(required = false) String status
            , @RequestParam(required = false) String pipelineExcnId
    ) {
        if (pipelineExcnId != null) {
            return ResponseEntity.ok(jobPort.findByPipelineExcnId(pipelineExcnId));
        }
        if (status != null) {
            return ResponseEntity.ok(
                    jobPort.findByStatus(ExecutionJobStatus.valueOf(status)));
        }
        return ResponseEntity.ok(jobPort.findByStatus(ExecutionJobStatus.PENDING));
    }

    @GetMapping("/jobs/{jobExcnId}")
    public ResponseEntity<ExecutionJob> getJob(@PathVariable String jobExcnId) {
        return jobPort.findById(jobExcnId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
