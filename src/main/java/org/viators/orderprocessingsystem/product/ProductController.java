package org.viators.orderprocessingsystem.product;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.viators.orderprocessingsystem.product.dto.request.CreateProductRequest;
import org.viators.orderprocessingsystem.product.dto.request.UpdateProductRequest;
import org.viators.orderprocessingsystem.product.dto.response.ProductDetailsResponse;
import org.viators.orderprocessingsystem.product.dto.response.ProductSummaryResponse;
import org.viators.orderprocessingsystem.user.UserService;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
public class ProductController {

    private final UserService userService;
    private final ProductService productService;


    @GetMapping
    public ResponseEntity<Page<ProductDetailsResponse>> getAllActiveProducts(
        @PageableDefault(size = 12, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {

        return ResponseEntity.ok(productService.getAllActiveProducts(pageable));
    }

    @GetMapping("/{productUuid}")
    public ResponseEntity<ProductDetailsResponse> getProduct(@PathVariable String productUuid) {
        return ResponseEntity.ok(productService.getProduct(productUuid));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public ResponseEntity<ProductSummaryResponse> create(@Valid @RequestBody CreateProductRequest request) {

        ProductSummaryResponse response = productService.create(request);
        URI location = ServletUriComponentsBuilder
                .fromCurrentRequest()
                .path("/{uuid}")
                .buildAndExpand(response.uuid())
                .toUri();

        return ResponseEntity.created(location).body(response);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{productUuid}")
    public ResponseEntity<ProductSummaryResponse> update(@PathVariable String productUuid,
                                                         @Valid @RequestBody UpdateProductRequest request) {
        ProductSummaryResponse response = productService.update(productUuid, request);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{productUuid}")
    public ResponseEntity<Void> deactivateProduct(@PathVariable String productUuid) {
        productService.deactivateProduct(productUuid);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{productUuid}")
    public ResponseEntity<Void> reActivateProduct(@PathVariable String productUuid) {
        productService.reActivateProduct(productUuid);
        return ResponseEntity.noContent().build();
    }
}
