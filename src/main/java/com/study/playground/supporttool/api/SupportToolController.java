package com.study.playground.supporttool.api;

import com.study.playground.common.dto.ApiResponse;
import com.study.playground.supporttool.dto.SupportToolRequest;
import com.study.playground.supporttool.dto.SupportToolResponse;
import com.study.playground.supporttool.service.SupportToolService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tools")
@RequiredArgsConstructor
public class SupportToolController {

    private final SupportToolService supportToolService;

    @GetMapping
    public ApiResponse<List<SupportToolResponse>> findAll() {
        return ApiResponse.success(supportToolService.findAll());
    }

    @GetMapping("/{id}")
    public ApiResponse<SupportToolResponse> findById(@PathVariable Long id) {
        return ApiResponse.success(supportToolService.findById(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SupportToolResponse> create(@RequestBody SupportToolRequest request) {
        return ApiResponse.success(supportToolService.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<SupportToolResponse> update(@PathVariable Long id, @RequestBody SupportToolRequest request) {
        return ApiResponse.success(supportToolService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        supportToolService.delete(id);
    }

    @PostMapping("/{id}/test")
    public ApiResponse<Map<String, Object>> testConnection(@PathVariable Long id) {
        boolean reachable = supportToolService.testConnection(id);
        return ApiResponse.success(Map.of("reachable", reachable));
    }
}
