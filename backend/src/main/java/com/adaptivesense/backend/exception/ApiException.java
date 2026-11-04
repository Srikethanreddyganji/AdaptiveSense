package com.adaptivesense.backend.exception;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(
            HttpStatus status,
            String message) {

        this(status, message, "REQUEST_FAILED");
    }

    public ApiException(HttpStatus status, String message, String code) {

        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
