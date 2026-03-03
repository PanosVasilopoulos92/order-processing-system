package org.viators.orderprocessingsystem.exceptions.dto;

import org.viators.orderprocessingsystem.exceptions.ErrorCodeEnum;

import java.time.Instant;
import java.util.List;

public record ValidationErrorResponse(
        int status,
        ErrorCodeEnum errorCode,
        String message,
        String path,
        Instant timestamp,
        List<FieldError> fieldErrors
) {

    public static ValidationErrorResponse of(List<FieldError> fieldErrors, String path) {
        return new ValidationErrorResponse(
                400,
                ErrorCodeEnum.VALIDATION_FAILED,
                String.format("Validation failed for %d field(s)", fieldErrors.size()),
                path,
                Instant.now(),
                fieldErrors
        );
    }

}
