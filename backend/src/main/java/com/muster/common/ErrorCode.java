package com.muster.common;

public enum ErrorCode {
    VALIDATION(400),
    UNAUTHORIZED(401),
    AUTH_FAILED(401),
    FORBIDDEN(403),
    NOT_FOUND(404),
    PERSON_NOT_FOUND(404),
    CONFLICT(409),
    ARCHIVE_REQUIRED(409),
    WINDOW_CLOSED(409),
    PHONE_DUPLICATE(400);

    private final int httpStatus;

    ErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}
