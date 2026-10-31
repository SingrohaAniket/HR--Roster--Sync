package com.moveinsync.roster.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail handleNotFound() {
        return Problems.of(ApiErrorCode.NOT_FOUND);
    }

    @ExceptionHandler(ConflictException.class)
    public ProblemDetail handleConflict() {
        return Problems.of(ApiErrorCode.CONFLICT);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied() {
        return Problems.of(ApiErrorCode.ACCESS_DENIED);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ProblemDetail handleAuthenticationFailure() {
        return Problems.of(ApiErrorCode.AUTHENTICATION_FAILED);
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        StackTraceElement[] stack = ex.getStackTrace();
        log.error("Unhandled exception {} at {}", ex.getClass().getName(), stack.length > 0 ? stack[0] : "unknown");
        return Problems.of(ApiErrorCode.INTERNAL_ERROR);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = Problems.of(ApiErrorCode.forStatus(status));
        problem.setStatus(status.value());
        return super.handleExceptionInternal(ex, problem, headers, status, request);
    }
}
