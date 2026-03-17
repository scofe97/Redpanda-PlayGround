package com.study.playground.preset.api;

import com.study.playground.preset.dto.PresetRequest;
import com.study.playground.preset.dto.PresetResponse;
import com.study.playground.preset.service.PresetService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/presets")
@RequiredArgsConstructor
public class PresetController {

    private final PresetService presetService;

    @GetMapping
    public ResponseEntity<List<PresetResponse>> findAll() {
        return ResponseEntity.ok(presetService.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<PresetResponse> findById(@PathVariable Long id) {
        return ResponseEntity.ok(presetService.findById(id));
    }

    @PostMapping
    public ResponseEntity<PresetResponse> create(@Valid @RequestBody PresetRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(presetService.create(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<PresetResponse> update(@PathVariable Long id
            , @Valid @RequestBody PresetRequest request) {
        return ResponseEntity.ok(presetService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        presetService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
