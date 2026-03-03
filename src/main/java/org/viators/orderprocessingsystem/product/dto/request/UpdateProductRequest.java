package org.viators.orderprocessingsystem.product.dto.request;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.viators.orderprocessingsystem.common.enums.CategoryEnum;
import org.viators.orderprocessingsystem.product.ProductT;

import java.math.BigDecimal;
import java.util.Optional;

public record UpdateProductRequest(
        String name,
        String description,
        CategoryEnum category,

        @Positive(message = "Price cannot be zero or negative")
        @Digits(integer = 10, fraction = 2, message = "Price must have at most 2 decimal places")
        BigDecimal price,

        @PositiveOrZero(message = "Stock quantity cannot be negative")
        Long stockQuantity
) {

    public void updateResource(ProductT product) {
        Optional.ofNullable(name).ifPresent(product::setName);
        Optional.ofNullable(description).ifPresent(product::setDescription);
        Optional.ofNullable(price).ifPresent(product::setPrice);
        Optional.ofNullable(category).ifPresent(product::setCategory);
        Optional.ofNullable(stockQuantity).ifPresent(product::setStockQuantity);
    }
}
