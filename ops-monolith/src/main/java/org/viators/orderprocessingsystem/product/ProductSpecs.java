package org.viators.orderprocessingsystem.product;

import org.springframework.data.jpa.domain.Specification;
import org.viators.orderprocessingsystem.common.enums.CategoryEnum;
import org.viators.orderprocessingsystem.common.enums.StatusEnum;

import java.math.BigDecimal;

public class ProductSpecs {

    public static Specification<ProductT> hasStatusActive() {
        return (root, query, criteriaBuilder) ->
            criteriaBuilder.equal(root.get("status"), StatusEnum.ACTIVE);
    }

    public static Specification<ProductT> hasNameContaining(String nameText) {
        return (root, query, criteriaBuilder) ->
            criteriaBuilder.like(criteriaBuilder.lower(root.get("name")
            ), "%".concat(nameText.toLowerCase()).concat("%"));
    }

    public static Specification<ProductT> hasCategory(CategoryEnum category) {
        return (root, query, criteriaBuilder) ->
            criteriaBuilder.equal(root.get("category"), category);
    }

    public static Specification<ProductT> hasPriceBetween(BigDecimal minPrice, BigDecimal maxPrice) {
        return (root, query, criteriaBuilder) ->
            criteriaBuilder.between(root.get("price"), minPrice, maxPrice);
    }
}
