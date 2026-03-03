package org.viators.orderprocessingsystem.product;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.viators.orderprocessingsystem.common.enums.StatusEnum;
import org.viators.orderprocessingsystem.exceptions.BusinessValidationException;
import org.viators.orderprocessingsystem.exceptions.DuplicateResourceException;
import org.viators.orderprocessingsystem.exceptions.ResourceNotFoundException;
import org.viators.orderprocessingsystem.product.dto.request.CreateProductRequest;
import org.viators.orderprocessingsystem.product.dto.request.UpdateProductRequest;
import org.viators.orderprocessingsystem.product.dto.response.ProductDetailsResponse;
import org.viators.orderprocessingsystem.product.dto.response.ProductSummaryResponse;
import org.viators.orderprocessingsystem.user.UserService;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class ProductService {

    private final ProductRepository productRepository;

    // Other Service dependencies
    private final UserService userService;


    public Page<ProductDetailsResponse> getAllActiveProducts(Pageable pageable) {
        Page<ProductT> results = productRepository.findAllByStatus(StatusEnum.ACTIVE, pageable);
        return results.map(ProductDetailsResponse::from);
    }

    public ProductDetailsResponse getProduct(String productUuid) {
        ProductT product = productRepository.findByUuid(productUuid)
            .orElseThrow(() -> new ResourceNotFoundException("Product", "uuid", productUuid));

        return ProductDetailsResponse.from(product);
    }

    @Transactional
    public ProductSummaryResponse create(CreateProductRequest request) {
        if (productRepository.existsByName(request.name())) {
            throw new IllegalArgumentException("Name already exists");
        }

        ProductT entity = request.toEntity();
        entity = productRepository.save(entity);

        return ProductSummaryResponse.from(entity);
    }

    @Transactional
    public ProductSummaryResponse update(String productUuid, UpdateProductRequest request) {

        ProductT product = productRepository.findByUuidAndStatus(productUuid, StatusEnum.ACTIVE)
            .orElseThrow(() -> new ResourceNotFoundException("Product", "uuid", productUuid));

        if (productRepository.existsByName(request.name())) {
            throw new DuplicateResourceException("Product", "name", request.name());
        }

        request.updateResource(product);
        return ProductSummaryResponse.from(product);
    }

    @Transactional
    public void deactivateProduct(String productUuid) {
        ProductT product = productRepository.findByUuidAndStatus(productUuid, StatusEnum.ACTIVE)
            .orElseThrow(() -> new ResourceNotFoundException("Product", "uuid", productUuid));

        if (product.getStatus().equals(StatusEnum.INACTIVE)) {
            throw new BusinessValidationException("Product is already deactivated/inactive");
        }

        product.setStatus(StatusEnum.INACTIVE);
    }

    @Transactional
    public void reActivateProduct(String productUuid) {
        ProductT product = productRepository.findByUuidAndStatus(productUuid, StatusEnum.ACTIVE)
            .orElseThrow(() -> new ResourceNotFoundException("Product", "uuid", productUuid));

        product.setStatus(StatusEnum.ACTIVE);
    }

}
