package org.viators.orderprocessingsystem.exceptions;

public class InvalidCredentialsException extends BaseException {

    public InvalidCredentialsException(String message) {
        super(message, ErrorCodeEnum.INVALID_CREDENTIALS);
    }

    public InvalidCredentialsException() {
        super(
                "Invalid credentials provided",
                ErrorCodeEnum.INVALID_CREDENTIALS
        );
    }
}
