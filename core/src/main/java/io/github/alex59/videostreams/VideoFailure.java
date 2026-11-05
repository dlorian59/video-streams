package io.github.alex59.videostreams;

/** Failure category; cancellation or storage failure during publication is not a rollback guarantee. */
public final class VideoFailure extends RuntimeException {
    public enum Code { INVALID_SOURCE, TOO_LARGE, BUSY, PROCESS_FAILED, TIMEOUT, CANCELLED, STORAGE_FAILED, CLOSED }
    private final Code code;

    public VideoFailure(Code code, String message) { super(message); this.code = code; }
    public VideoFailure(Code code, String message, Throwable cause) { super(message, cause); this.code = code; }
    public Code code() { return code; }
}
