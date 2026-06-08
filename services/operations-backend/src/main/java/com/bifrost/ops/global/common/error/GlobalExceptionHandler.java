package com.bifrost.ops.global.common.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException e) {
        return ResponseEntity.status(e.code().status())
            .body(ErrorResponse.of(e.code(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        List<ErrorResponse.FieldError> details = e.getBindingResult().getFieldErrors().stream()
            .map(fe -> new ErrorResponse.FieldError(fe.getField(), fe.getDefaultMessage()))
            .toList();
        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.status())
            .body(ErrorResponse.of(ErrorCode.VALIDATION_FAILED, "유효성 검증 실패", details));
    }

    /**
     * 잘못된 요청 본문(JSON 파싱 실패 또는 record 컴팩트 생성자의 검증 실패).
     * PipelineProvisionCommand 등은 생성자에서 IllegalArgumentException을 던지며,
     * Jackson이 이를 HttpMessageNotReadableException으로 감싼다. 둘 다 400으로 매핑.
     */
    @ExceptionHandler({HttpMessageNotReadableException.class, IllegalArgumentException.class})
    public ResponseEntity<ErrorResponse> handleBadRequest(Exception e) {
        Throwable cause = e instanceof HttpMessageNotReadableException && e.getCause() != null
                ? e.getCause() : e;
        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.status())
            .body(ErrorResponse.of(ErrorCode.VALIDATION_FAILED, cause.getMessage()));
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoHandler(NoHandlerFoundException e) {
        return ResponseEntity.status(404)
            .body(new ErrorResponse("404", "요청한 경로를 찾을 수 없습니다", List.of()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnknown(Exception e) {
        log.error("처리되지 않은 예외", e);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.status())
            .body(ErrorResponse.of(ErrorCode.INTERNAL_ERROR, "내부 오류"));
    }
}
