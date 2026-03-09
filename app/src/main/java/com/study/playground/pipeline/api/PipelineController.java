package com.study.playground.pipeline.api;

import com.study.playground.pipeline.dto.PipelineExecutionResponse;
import com.study.playground.pipeline.service.PipelineService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 파이프라인 실행을 트리거하고 조회하는 REST 컨트롤러.
 *
 * 파이프라인 시작은 동기 처리가 아니라 Outbox 패턴으로 비동기 발행되므로
 * 응답 코드는 202 Accepted를 사용한다. 클라이언트는 실제 진행 상황을
 * SSE 엔드포인트(PipelineSseController)를 통해 구독해야 한다.
 */
@RestController
@RequestMapping("/api/tickets/{ticketId}/pipeline")
@RequiredArgsConstructor
public class PipelineController {

    private final PipelineService pipelineService;

    /**
     * 파이프라인을 시작한다.
     *
     * 실행 레코드를 DB에 저장하고 Outbox에 이벤트를 적재한다.
     * 실제 파이프라인 엔진은 Kafka를 통해 비동기로 구동되므로
     * 202 Accepted를 반환하여 클라이언트가 폴링이나 SSE를 통해 결과를 추적하도록 안내한다.
     */
    @PostMapping("/start")
    public ResponseEntity<PipelineExecutionResponse> start(@PathVariable Long ticketId) {
        PipelineExecutionResponse response = pipelineService.startPipeline(ticketId);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(response);
    }

    /**
     * 실패 시뮬레이션: 랜덤 스텝에 [FAIL] 마커를 삽입하여 SAGA 보상 트랜잭션을 검증한다.
     */
    @PostMapping("/start-with-failure")
    public ResponseEntity<PipelineExecutionResponse> startWithFailure(@PathVariable Long ticketId) {
        PipelineExecutionResponse response = pipelineService.startPipelineWithFailure(ticketId);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(response);
    }

    /**
     * 가장 최근 파이프라인 실행 결과를 조회한다.
     */
    @GetMapping
    public ResponseEntity<PipelineExecutionResponse> getLatest(@PathVariable Long ticketId) {
        return ResponseEntity.ok(pipelineService.getLatestExecution(ticketId));
    }

    /**
     * 해당 티켓의 모든 파이프라인 실행 이력을 조회한다.
     */
    @GetMapping("/history")
    public ResponseEntity<List<PipelineExecutionResponse>> getHistory(@PathVariable Long ticketId) {
        return ResponseEntity.ok(pipelineService.getHistory(ticketId));
    }
}
