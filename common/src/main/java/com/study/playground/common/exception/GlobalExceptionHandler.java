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
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 클라이언트가 연결을 끊은 후 응답을 쓰려 할 때 발생한다.
     * SSE 구독 해제, Prometheus 스크래핑 타임아웃 등에서 정상적으로 발생하므로
     * 로깅하지 않고 무시한다. 응답을 보낼 수 없으므로 null을 반환한다.
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleClientAbort(AsyncRequestNotUsableException e) {
        // 클라이언트가 이미 없으므로 응답 불가 — 무시
    }

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
        if (isClientAbort(e)) {
            return null;
        }
        log.error("Unexpected error", e);
        return ResponseEntity
                .status(CommonErrorCode.INTERNAL_ERROR.getHttpStatus())
                .body(ApiError.of(CommonErrorCode.INTERNAL_ERROR));
    }

    private boolean isClientAbort(Throwable e) {
        while (e != null) {
            String name = e.getClass().getSimpleName();
            if ("ClientAbortException".equals(name) || "Broken pipe".equals(e.getMessage())) {
                return true;
            }
            e = e.getCause();
        }
        return false;
    }
}
