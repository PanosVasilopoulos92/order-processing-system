package org.viators.orderprocessingsystem.exceptions;

public class DuplicateResourceException extends BaseException {

    public DuplicateResourceException(String message) {
        super(message, ErrorCodeEnum.DUPLICATE_RESOURCE);
    }

    public DuplicateResourceException(String resourceType, String field, String value) {
        super(
                "%s with %s: %s already exists in system".formatted(resourceType, field, value),
                ErrorCodeEnum.DUPLICATE_RESOURCE
        );
    }
}
