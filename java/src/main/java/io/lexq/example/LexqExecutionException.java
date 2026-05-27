package io.lexq.example;

/** Thrown when LexQ returns result != SUCCESS, or when the call fails in transit. */
public class LexqExecutionException extends RuntimeException {

    private final String code;

    public LexqExecutionException(String code, String message) {
        super("LexQ " + (code != null ? code + " " : "")
                + (message != null ? message : "execution failed"));
        this.code = code;
    }

    /** The LexQ error code (e.g. {@code P-001}), or {@code null} for a transport error. */
    public String getCode() {
        return code;
    }
}