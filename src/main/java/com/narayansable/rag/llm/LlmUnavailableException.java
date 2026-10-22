package com.narayansable.rag.llm;

/** The model could not be reached after the SDK's own retries; callers degrade instead of failing. */
public class LlmUnavailableException extends RuntimeException {

    public LlmUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
