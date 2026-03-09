package com.study.playground.common.exception;

import com.study.playground.common.dto.ApiError;
import com.study.playground.common.dto.CommonErrorCode;
import com.study.playground.common.dto.Exposure;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiError> handleBusinessException(BusinessException e) {
        log.warn("Business exception: {}", e.getMessage());
        String message = e.getErrorCode().getExposure() == Exposure.PUBLIC
                ? e.getMessage()
                : e.getErrorCode().getDefaultMessage();
        return ResponseEntity
                .status(e.getErrorCode().getHttpStatus())
                .body(ApiError.of(e.getErrorCode(), message));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidationException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));
        log.warn("Validation failed: {}", message);
        return ResponseEntity
                .status(CommonErrorCode.INVALID_INPUT.getHttpStatus())
                .body(ApiError.of(CommonErrorCode.INVALID_INPUT, message));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleException(Exception e) {
        log.error("Unexpected error", e);
        return ResponseEntity
                .status(CommonErrorCode.INTERNAL_ERROR.getHttpStatus())
                .body(ApiError.of(CommonErrorCode.INTERNAL_ERROR));
    }
}
