package org.viators.orderprocessingsystem.exceptions;

import lombok.Getter;

@Getter
public class BaseException extends RuntimeException {

    private final ErrorCodeEnum errorCode;
    private final transient Object details;

    protected BaseException(String message, ErrorCodeEnum errorCode) {
        super(message);
        this.errorCode = errorCode;
        this.details = null;
    }

    protected BaseException(String message, ErrorCodeEnum errorCode, Object details) {
        super(message);
        this.errorCode = errorCode;
        this.details = details;
    }

    protected BaseException(String message, ErrorCodeEnum errorCode, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.details = null;
    }
}
