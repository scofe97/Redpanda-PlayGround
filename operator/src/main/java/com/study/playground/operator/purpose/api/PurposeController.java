package com.study.playground.operator.purpose.api;

import com.study.playground.operator.purpose.dto.PurposeRequest;
import com.study.playground.operator.purpose.dto.PurposeResponse;
import com.study.playground.operator.purpose.service.PurposeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/purposes")
@RequiredArgsConstructor
public class PurposeController {

    private final PurposeService purposeService;

    @GetMapping
    public ResponseEntity<List<PurposeResponse>> findAll() {
        return ResponseEntity.ok(purposeService.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<PurposeResponse> findById(@PathVariable Long id) {
        return ResponseEntity.ok(purposeService.findById(id));
    }

    @PostMapping
    public ResponseEntity<PurposeResponse> create(@Valid @RequestBody PurposeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(purposeService.create(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<PurposeResponse> update(@PathVariable Long id
            , @Valid @RequestBody PurposeRequest request) {
        return ResponseEntity.ok(purposeService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        purposeService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
