package com.agy.util;

/** Human-readable exception text: message, else the simple class name. */
public final class ErrorText {

    private ErrorText() {
    }

    public static String describe(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isEmpty()) {
            message = e.getClass().getSimpleName();
        }
        return message;
    }
}
