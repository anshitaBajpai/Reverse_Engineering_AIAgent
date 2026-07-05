package com.reverseengineer.agent.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Map;
import java.util.stream.Collectors;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(
            MethodArgumentNotValidException ex) {

        Map<String, String> fieldErrors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .collect(Collectors.toMap(
                        FieldError::getField,
                        fe -> fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "invalid",
                        (first, second) -> first  
                ));

        log.debug("Request validation failed: {}", fieldErrors);
        return ResponseEntity.status(BAD_REQUEST)
                .body(Map.of("error", "Validation failed", "fields", fieldErrors));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleMalformedJson(
            HttpMessageNotReadableException ex) {

        log.debug("Malformed JSON in request body: {}", ex.getMessage());
        return ResponseEntity.status(BAD_REQUEST)
                .body(Map.of("error", "Malformed JSON request body"));
    }

        @ExceptionHandler(IllegalArgumentException.class)
        public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException ex) {
                log.debug("Illegal argument: {}", ex.getMessage());
                return ResponseEntity.status(BAD_REQUEST).body(Map.of("error", ex.getMessage() != null ? ex.getMessage() : "Invalid argument"));
        }

        @ExceptionHandler(MethodArgumentTypeMismatchException.class)
        public ResponseEntity<Map<String, String>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
                String msg = String.format("Parameter '%s' has invalid value '%s'", ex.getName(), ex.getValue());
                log.debug("Type mismatch: {}", msg);
                return ResponseEntity.status(BAD_REQUEST).body(Map.of("error", msg));
        }

        @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
        public ResponseEntity<Map<String, String>> handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex) {
                log.debug("Method not allowed: {}", ex.getMessage());
                return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(Map.of("error", "HTTP method not supported"));
        }

        @ExceptionHandler(ConstraintViolationException.class)
        public ResponseEntity<Map<String, Object>> handleConstraintViolation(ConstraintViolationException ex) {
                var details = ex.getConstraintViolations()
                                .stream()
                                .collect(Collectors.toMap(cv -> cv.getPropertyPath().toString(), cv -> cv.getMessage(), (a, b) -> a));
                log.debug("Constraint violations: {}", details);
                return ResponseEntity.status(BAD_REQUEST).body(Map.of("error", "Validation failed", "violations", details));
        }

        @ExceptionHandler(DataIntegrityViolationException.class)
        public ResponseEntity<Map<String, String>> handleDataIntegrity(DataIntegrityViolationException ex) {
                log.debug("Data integrity violation: {}", ex.getMessage());
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Data integrity violation"));
        }

        @ExceptionHandler(Exception.class)
        public ResponseEntity<Map<String, String>> handleGeneric(Exception ex) {
                log.error("Unhandled exception", ex);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Internal server error"));
        }
}
