package io.vpp.platformapi.common;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> api(ApiException exception, HttpServletRequest request) {
        return ResponseEntity.status(exception.status()).body(new ApiError(exception.code(),
                exception.getMessage(), traceId(request), List.of()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(MethodArgumentNotValidException exception,
            HttpServletRequest request) {
        List<ApiError.Detail> details = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new ApiError.Detail(error.getField(), error.getDefaultMessage()))
                .toList();
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(new ApiError("VALIDATION_FAILED",
                "request validation failed", traceId(request), details));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiError> constraintValidation(ConstraintViolationException exception,
            HttpServletRequest request) {
        List<ApiError.Detail> details = exception.getConstraintViolations().stream()
                .map(violation -> new ApiError.Detail(violation.getPropertyPath().toString(),
                        violation.getMessage()))
                .toList();
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(new ApiError("VALIDATION_FAILED",
                "request validation failed", traceId(request), details));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> conflict(DataIntegrityViolationException exception,
            HttpServletRequest request) {
        LOG.warn("request violated a database integrity constraint", exception);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError("RESOURCE_CONFLICT",
                "resource conflicts with an existing value or interval", traceId(request), List.of()));
    }

    private static String traceId(HttpServletRequest request) {
        Object value = request.getAttribute(TraceIdFilter.ATTRIBUTE);
        return value == null ? "00000000000000000000000000000000" : value.toString();
    }
}
