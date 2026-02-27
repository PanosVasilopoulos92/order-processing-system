package org.viators.orderprocessingsystem.exceptions.dto;

import org.viators.orderprocessingsystem.exceptions.ErrorCodeEnum;

import java.time.Instant;

public record ErrorResponse(
        int status,
        ErrorCodeEnum errorCode,
        String message,
        String path,
        Instant timestamp
) {

    public static ErrorResponse of(int status, ErrorCodeEnum errorCode, String message, String path) {
        return new ErrorResponse(
                status,
                errorCode,
                message,
                path,
                Instant.now()
        );
    }

}
