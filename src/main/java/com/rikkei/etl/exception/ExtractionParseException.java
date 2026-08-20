package com.rikkei.etl.exception;

public class ExtractionParseException extends RuntimeException {
    public ExtractionParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
