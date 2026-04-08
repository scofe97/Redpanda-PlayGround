package com.study.playground.operator.supporttool.api;

import com.study.playground.operator.supporttool.dto.SupportToolRequest;
import com.study.playground.operator.supporttool.dto.SupportToolResponse;
import com.study.playground.operator.supporttool.service.SupportToolService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tools")
@RequiredArgsConstructor
public class SupportToolController {

    private final SupportToolService supportToolService;

    @GetMapping
    public ResponseEntity<List<SupportToolResponse>> findAll() {
        return ResponseEntity.ok(supportToolService.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<SupportToolResponse> findById(@PathVariable Long id) {
        return ResponseEntity.ok(supportToolService.findById(id));
    }

    @PostMapping
    public ResponseEntity<SupportToolResponse> create(@Valid @RequestBody SupportToolRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(supportToolService.create(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<SupportToolResponse> update(@PathVariable Long id, @Valid @RequestBody SupportToolRequest request) {
        return ResponseEntity.ok(supportToolService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        supportToolService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/test")
    public ResponseEntity<Map<String, Object>> testConnection(@PathVariable Long id) {
        boolean reachable = supportToolService.testConnection(id);
        return ResponseEntity.ok(Map.of("reachable", reachable));
    }
}
