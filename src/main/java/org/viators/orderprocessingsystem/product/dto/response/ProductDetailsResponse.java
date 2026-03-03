package org.viators.orderprocessingsystem.product.dto.response;

import org.viators.orderprocessingsystem.common.enums.CategoryEnum;
import org.viators.orderprocessingsystem.product.ProductT;

import java.math.BigDecimal;

public record ProductDetailsResponse(
    String name,
    String description,
    BigDecimal price,
    CategoryEnum category,
    Long stockQuantity
) {

    public static ProductDetailsResponse from(ProductT product) {
        return new ProductDetailsResponse(
            product.getName(),
            product.getDescription(),
            product.getPrice(),
            product.getCategory(),
            product.getStockQuantity()
        );
    }
}
