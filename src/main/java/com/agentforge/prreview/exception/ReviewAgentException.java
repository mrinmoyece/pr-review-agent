package com.agentforge.prreview.exception;

/**
 * Thrown when the PR review pipeline fails unrecoverably.
 * Callers can catch this specifically to distinguish agent failures
 * from general programming errors.
 */
public class ReviewAgentException extends RuntimeException {

    public ReviewAgentException(String message) {
        super(message);
    }

    public ReviewAgentException(String message, Throwable cause) {
        super(message, cause);
    }
}
