package io.lexq.example;

/**
 * Thrown when LexQ returns result != SUCCESS, or when the call fails in transit.
 *
 * <p>Both the HTTP status and the LexQ error code are kept: the status classifies the
 * failure, the code names it. Callers need the code to branch — see the table at
 * https://docs.lexq.io/api/execution#error-handling.
 */
public class LexqExecutionException extends RuntimeException {

    private final Integer httpStatus;
    private final String errorCode;

    /**
     * @param httpStatus LexQ's HTTP status, or {@code null} if the call never reached LexQ
     * @param errorCode  the LexQ error code (e.g. {@code P-015}), or {@code null} for a
     *                   transport error
     */
    public LexqExecutionException(Integer httpStatus, String errorCode, String message) {
        super("LexQ " + (httpStatus != null ? httpStatus + " " : "")
                + (errorCode != null ? errorCode + " " : "")
                + (message != null ? message : "execution failed"));
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
    }

    /** LexQ's HTTP status, or {@code null} when the call never reached LexQ. */
    public Integer getHttpStatus() {
        return httpStatus;
    }

    /**
     * The LexQ error code (e.g. {@code P-015}), or {@code null} for a transport error.
     *
     * <p>This is the value to branch on. It is stable across releases; the human-readable
     * message is not.
     */
    public String getErrorCode() {
        return errorCode;
    }
}
