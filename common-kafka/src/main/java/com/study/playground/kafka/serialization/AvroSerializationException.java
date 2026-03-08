package com.study.playground.kafka.serialization;

public class AvroSerializationException extends RuntimeException {
    public AvroSerializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
