package org.viators.orderprocessingsystem.exceptions;

public class InvalidStateException extends BaseException {

    public InvalidStateException(String message) {
        super(message, ErrorCodeEnum.INVALID_STATE);
    }

    public InvalidStateException(String resource, String attemptedAction, String currentState) {
        super(
                "Cannot %s %s in state %s".formatted(attemptedAction, resource, currentState),
                ErrorCodeEnum.INVALID_STATE
        );
    }
}
