package com.filemaster.exception;

import org.springframework.http.HttpStatus;

/** A problem we expect and can explain to the user (bad file, wrong type, too big...). */
public class FileMasterException extends RuntimeException {

    private final HttpStatus status;

    public FileMasterException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public static FileMasterException badRequest(String message) {
        return new FileMasterException(HttpStatus.BAD_REQUEST, message);
    }
}
