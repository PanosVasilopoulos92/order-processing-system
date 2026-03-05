package org.viators.orderprocessingsystem.product.dto.request;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.viators.orderprocessingsystem.common.enums.CategoryEnum;

import java.math.BigDecimal;

public record ProductSearchFilterRequest(

    @Size(min = 2, max = 30, message = "Name text must be between 2-30 characters long")
    String nameText,

    CategoryEnum category,

    @Positive
    @Digits(integer = 10, fraction = 2, message = "Min price must have 2 digits.")
    BigDecimal minPrice,

    @Positive
    @Digits(integer = 10, fraction = 2, message = "Max price must have 2 digits.")
    BigDecimal maxPrice
) {
}
