package org.viators.orderprocessingsystem.exceptions;

import java.util.Map;

public class BusinessValidationException extends BaseException {

    public BusinessValidationException(String message) {
        super(message, ErrorCodeEnum.BUSINESS_VALIDATION_FAILED);
    }

    public BusinessValidationException(String message, Map<String, String> fieldErrors) {
        super(message, ErrorCodeEnum.BUSINESS_VALIDATION_FAILED, fieldErrors);
    }

}
