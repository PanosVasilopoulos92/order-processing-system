package org.viators.orderprocessingsystem.product;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.viators.orderprocessingsystem.product.dto.request.CreateProductRequest;
import org.viators.orderprocessingsystem.product.dto.response.ProductSummaryResponse;
import org.viators.orderprocessingsystem.user.UserService;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
public class ProductController {

    private final UserService userService;
    private final ProductService productService;

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


}
