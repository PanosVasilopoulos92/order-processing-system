package org.viators.orderprocessingsystem.product;

import jakarta.persistence.*;
import lombok.*;
import org.viators.orderprocessingsystem.common.BaseEntity;
import org.viators.orderprocessingsystem.common.enums.CategoryEnum;

import java.math.BigDecimal;

@Entity
@Table(name = "products")
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@ToString(callSuper = true)
public class ProductT extends BaseEntity {

    @Column(name = "name", unique = true, nullable = false)
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "price", nullable = false)
    private BigDecimal price;

    @Enumerated(EnumType.STRING)
    @Column(name = "category")
    private CategoryEnum category;

    @Column(name = "stock_quantity")
    private Long stockQuantity;
}
