package org.viators.orderprocessingsystem.exceptions;

public class AccessDeniedException extends BaseException {

    public AccessDeniedException(String message) {
        super(message, ErrorCodeEnum.ACCESS_DENIED);
    }

    public AccessDeniedException() {
        super(
                "You don't have permission to access this resource",
                ErrorCodeEnum.ACCESS_DENIED
        );
    }
}
