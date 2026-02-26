package org.viators.orderprocessingsystem.product.dto.response;

import org.viators.orderprocessingsystem.common.enums.CategoryEnum;
import org.viators.orderprocessingsystem.product.ProductT;

public record ProductSummaryResponse(
        String uuid,
        String name,
        CategoryEnum category
) {

    public static ProductSummaryResponse from(ProductT product) {
        return new ProductSummaryResponse(
                product.getUuid(),
                product.getName(),
                product.getCategory()
        );
    }
}
