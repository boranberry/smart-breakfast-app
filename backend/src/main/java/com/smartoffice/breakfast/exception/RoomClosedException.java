package com.smartoffice.breakfast.exception;

public class RoomClosedException extends RuntimeException {
    public RoomClosedException(String message) {
        super(message);
    }
}
