package com.bifrost.ops.global.common.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String LOG_UNHANDLED_EXCEPTION = "처리되지 않은 예외";

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
            .body(ErrorResponse.of(ErrorCode.VALIDATION_FAILED, ErrorMessages.VALIDATION_FAILED, details));
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

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<ErrorResponse> handleNotFound(Exception e) {
        return error(ErrorCode.RESOURCE_NOT_FOUND, ErrorMessages.RESOURCE_NOT_FOUND);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return error(ErrorCode.METHOD_NOT_ALLOWED, ErrorMessages.METHOD_NOT_ALLOWED);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException e) {
        return error(ErrorCode.UNSUPPORTED_MEDIA_TYPE, ErrorMessages.UNSUPPORTED_MEDIA_TYPE);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnknown(Exception e) {
        log.error(LOG_UNHANDLED_EXCEPTION, e);
        return error(ErrorCode.INTERNAL_ERROR, ErrorMessages.INTERNAL_ERROR);
    }

    private static ResponseEntity<ErrorResponse> error(ErrorCode code, String message) {
        return ResponseEntity.status(code.status())
            .body(ErrorResponse.of(code, message));
    }
}
