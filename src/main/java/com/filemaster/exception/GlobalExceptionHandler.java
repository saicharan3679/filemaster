package com.filemaster.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.util.Map;

/**
 * Turns every exception into a clean JSON body: {"error": "message"}.
 * The frontend reads that "error" field and shows it to the user.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(FileMasterException.class)
    public ResponseEntity<Map<String, String>> handleKnown(FileMasterException ex) {
        return body(ex.getStatus(), ex.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> handleTooLarge(MaxUploadSizeExceededException ex) {
        return body(HttpStatus.PAYLOAD_TOO_LARGE, "File is too large. Please upload a smaller file.");
    }

    @ExceptionHandler({MissingServletRequestPartException.class, MultipartException.class})
    public ResponseEntity<Map<String, String>> handleNoFile(Exception ex) {
        return body(HttpStatus.BAD_REQUEST, "No file was uploaded. Please choose a file and try again.");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleOther(Exception ex) {
        // Spring's own web exceptions (404, 405 ...) already know their status.
        if (ex instanceof ErrorResponse er) {
            int code = er.getStatusCode().value();
            String text = switch (code) {
                case 404 -> "Not found.";
                case 405 -> "This action is not allowed.";
                default -> "The request could not be processed.";
            };
            return ResponseEntity.status(er.getStatusCode()).body(Map.of("error", text));
        }
        // Anything else is our bug: log the details, show a generic message.
        log.error("Unexpected error", ex);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong on our side. Please try again.");
    }

    private ResponseEntity<Map<String, String>> body(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}
