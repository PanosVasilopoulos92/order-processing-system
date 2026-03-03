package org.viators.orderprocessingsystem.product.dto.response;

import org.viators.orderprocessingsystem.common.enums.CategoryEnum;
import org.viators.orderprocessingsystem.common.enums.StatusEnum;
import org.viators.orderprocessingsystem.product.ProductT;

import java.math.BigDecimal;

public record ProductDetailsResponse(
    String uuid,
    String name,
    String description,
    BigDecimal price,
    CategoryEnum category,
    Long stockQuantity,
    StatusEnum status
    ) {

    public static ProductDetailsResponse from(ProductT product) {
        return new ProductDetailsResponse(
            product.getUuid(),
            product.getName(),
            product.getDescription(),
            product.getPrice(),
            product.getCategory(),
            product.getStockQuantity(),
            product.getStatus()
        );
    }
}
