package org.viators.orderprocessingsystem.product;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.viators.orderprocessingsystem.common.enums.StatusEnum;
import org.viators.orderprocessingsystem.exceptions.BusinessValidationException;
import org.viators.orderprocessingsystem.exceptions.DuplicateResourceException;
import org.viators.orderprocessingsystem.exceptions.ResourceNotFoundException;
import org.viators.orderprocessingsystem.order.OrderService;
import org.viators.orderprocessingsystem.order.OrderT;
import org.viators.orderprocessingsystem.orderitem.OrderItemService;
import org.viators.orderprocessingsystem.product.dto.request.CreateProductRequest;
import org.viators.orderprocessingsystem.product.dto.request.ProductSearchFilterRequest;
import org.viators.orderprocessingsystem.product.dto.request.UpdateProductRequest;
import org.viators.orderprocessingsystem.product.dto.response.ProductDetailsResponse;
import org.viators.orderprocessingsystem.product.dto.response.ProductSummaryResponse;
import org.viators.orderprocessingsystem.user.UserService;

import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class ProductService {

    private final ProductRepository productRepository;

    // Other Service dependencies
    private final UserService userService;

    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of("name", "price", "createdAt");

    public ProductT getActiveProduct(String productUuid) {
        return productRepository.findByUuidAndStatus(productUuid, StatusEnum.ACTIVE)
            .orElseThrow(() -> new ResourceNotFoundException("Product", "uuid", productUuid));
    }

    public Set<ProductT> getProductsInSet(Set<String> productUuids) {
        return productRepository.findAllByUuidIn(productUuids);
    }

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
        if (productRepository.existsByNameIgnoreCase(request.name())) {
            throw new DuplicateResourceException("Name already exists");
        }

        ProductT entity = request.toEntity();
        entity = productRepository.save(entity);

        return ProductSummaryResponse.from(entity);
    }

    @Transactional
    public ProductSummaryResponse update(String productUuid, UpdateProductRequest request) {

        ProductT product = productRepository.findByUuidAndStatus(productUuid, StatusEnum.ACTIVE)
            .orElseThrow(() -> new ResourceNotFoundException("Product", "uuid", productUuid));

        if (productRepository.existsByNameIgnoreCase(request.name())) {
            throw new DuplicateResourceException("Product", "name", request.name());
        }

        request.updateResource(product);
        return ProductSummaryResponse.from(product);
    }

    @Transactional
    public void deactivateProduct(String productUuid) {
        ProductT product = productRepository.findByUuid(productUuid)
            .orElseThrow(() -> new ResourceNotFoundException("Product", "uuid", productUuid));

        if (product.getStatus().equals(StatusEnum.INACTIVE)) {
            throw new BusinessValidationException("Product is already deactivated/inactive");
        }

        product.setStatus(StatusEnum.INACTIVE);
    }

    @Transactional
    public void reActivateProduct(String productUuid) {
        ProductT product = productRepository.findByUuid(productUuid)
            .orElseThrow(() -> new ResourceNotFoundException("Product", "uuid", productUuid));

        product.setStatus(StatusEnum.ACTIVE);
    }

    public Page<ProductDetailsResponse> searchDynamicallyForProduct(ProductSearchFilterRequest request, Pageable pageable) {
        pageable.getSort().stream()
            .map(Sort.Order::getProperty)
            .filter(field -> !ALLOWED_SORT_FIELDS.contains(field))
            .findFirst()
            .ifPresent(field ->{
                throw new BusinessValidationException(
                    "Invalid sort field: '%s'. Allowed fields: %s".formatted(field, ALLOWED_SORT_FIELDS));
            });

        Specification<ProductT> specs = Specification.where(ProductSpecs.hasStatusActive());

        if (request.nameText() != null) {
            specs = specs.and(ProductSpecs.hasNameContaining(request.nameText()));
        }

        if (request.category() != null) {
            specs = specs.and(ProductSpecs.hasCategory(request.category()));
        }

        if (request.minPrice() != null && request.maxPrice() != null) {
            specs = specs.and(ProductSpecs.hasPriceBetween(request.minPrice(), request.maxPrice()));
        }

        return productRepository.findAll(specs, pageable)
            .map(ProductDetailsResponse::from);
    }

}
