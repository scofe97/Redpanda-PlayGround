package com.study.playground.pipeline.api;

import com.study.playground.common.dto.ApiResponse;
import com.study.playground.pipeline.dto.PipelineExecutionResponse;
import com.study.playground.pipeline.service.PipelineService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/tickets/{ticketId}/pipeline")
@RequiredArgsConstructor
public class PipelineController {

    private final PipelineService pipelineService;

    @PostMapping("/start")
    public ResponseEntity<ApiResponse<PipelineExecutionResponse>> start(@PathVariable Long ticketId) {
        PipelineExecutionResponse response = pipelineService.startPipeline(ticketId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(response));
    }

    /**
     * 실패 시뮬레이션: 랜덤 스텝에 [FAIL] 마커를 삽입하여 SAGA 보상 트랜잭션을 검증한다.
     */
    @PostMapping("/start-with-failure")
    public ResponseEntity<ApiResponse<PipelineExecutionResponse>> startWithFailure(@PathVariable Long ticketId) {
        PipelineExecutionResponse response = pipelineService.startPipelineWithFailure(ticketId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(response));
    }

    @GetMapping
    public ApiResponse<PipelineExecutionResponse> getLatest(@PathVariable Long ticketId) {
        return ApiResponse.success(pipelineService.getLatestExecution(ticketId));
    }

    @GetMapping("/history")
    public ApiResponse<List<PipelineExecutionResponse>> getHistory(@PathVariable Long ticketId) {
        return ApiResponse.success(pipelineService.getHistory(ticketId));
    }
}
