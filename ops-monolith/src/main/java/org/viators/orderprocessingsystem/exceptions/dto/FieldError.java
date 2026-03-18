package org.viators.orderprocessingsystem.exceptions.dto;

public record FieldError(
        String field,
        String message,
        Object rejectedValue
) {
}
