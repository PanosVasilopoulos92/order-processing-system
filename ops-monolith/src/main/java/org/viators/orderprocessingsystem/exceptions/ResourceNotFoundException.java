package org.viators.orderprocessingsystem.exceptions;

public class ResourceNotFoundException extends BaseException {

    public ResourceNotFoundException(String message) {
        super(message, ErrorCodeEnum.RESOURCE_NOT_FOUND);
    }

    public ResourceNotFoundException(String resourceType, String identifier, String value) {
        super(
                "%s not found with %s: %s or is inactive".formatted(resourceType, identifier, value),
                ErrorCodeEnum.RESOURCE_NOT_FOUND
        );
    }
}
