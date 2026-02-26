package org.viators.orderprocessingsystem.product;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.viators.orderprocessingsystem.product.dto.request.CreateProductRequest;
import org.viators.orderprocessingsystem.product.dto.response.ProductSummaryResponse;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class ProductService {

    private final ProductRepository productRepository;

    @Transactional
    public ProductSummaryResponse create(CreateProductRequest request) {
        if (productRepository.existsByName(request.name())) {
            throw new IllegalArgumentException("Name already exists");
        }

        ProductT entity = request.toEntity();
        entity = productRepository.save(entity);

        return ProductSummaryResponse.from(entity);
    }
}
