package com.moveinsync.roster.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

public enum ApiErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "The request is invalid."),
    AUTHENTICATION_FAILED(HttpStatus.UNAUTHORIZED, "Authentication failed."),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "You do not have access to this operation."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "The resource was not found."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "This method is not supported for this resource."),
    CONFLICT(HttpStatus.CONFLICT, "The request conflicts with the current state of the resource."),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "This content type is not supported."),
    REQUEST_REJECTED(HttpStatus.BAD_REQUEST, "The request could not be processed."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "An internal error occurred.");

    private final HttpStatus status;
    private final String detail;

    ApiErrorCode(HttpStatus status, String detail) {
        this.status = status;
        this.detail = detail;
    }

    public HttpStatus status() {
        return status;
    }

    public String detail() {
        return detail;
    }

    static ApiErrorCode forStatus(HttpStatusCode status) {
        if (status.is5xxServerError()) {
            return INTERNAL_ERROR;
        }
        return switch (status.value()) {
            case 400 -> VALIDATION_FAILED;
            case 404 -> NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 409 -> CONFLICT;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            default -> REQUEST_REJECTED;
        };
    }
}
