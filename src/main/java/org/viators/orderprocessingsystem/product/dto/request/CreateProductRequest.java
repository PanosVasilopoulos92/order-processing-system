package org.viators.orderprocessingsystem.product.dto.request;

import jakarta.validation.constraints.*;
import org.viators.orderprocessingsystem.common.enums.CategoryEnum;
import org.viators.orderprocessingsystem.product.ProductT;

import java.math.BigDecimal;
import java.util.Optional;

public record CreateProductRequest(
        @NotBlank(message = "Name is required")
        @Size(min = 3, max = 80, message = "Name can be between 3-80 characters long")
        String name,

        @Size(max = 400, message = "Description can be at most 400 characters long")
        String description,

        @NotNull(message = "Category is required")
        CategoryEnum category,

        @NotNull(message = "Price is required")
        @Positive(message = "Price cannot be zero or negative")
        @Digits(integer = 10, fraction = 2, message = "Price must have at most 2 decimal places")
        BigDecimal price,

        @NotNull(message = "Stock quantity is required")
        @Positive(message = "Stock quantity cannot be zero or negative")
        Long stockQuantity
) {

        public ProductT toEntity() {
                ProductT productT = new ProductT();
                productT.setName(name);
                productT.setCategory(category);
                productT.setPrice(price);
                productT.setStockQuantity(stockQuantity);
                Optional.ofNullable(description).ifPresent(productT::setDescription);

                return productT;
        }
}
