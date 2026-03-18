package org.viators.orderprocessingsystem.payment;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.viators.orderprocessingsystem.payment.dto.request.CreatePaymentRequest;
import org.viators.orderprocessingsystem.payment.dto.response.PaymentDetailsResponse;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping
    public ResponseEntity<PaymentDetailsResponse> create(@AuthenticationPrincipal(expression = "uuid") String customerUuid,
                                                         @Valid @RequestBody CreatePaymentRequest request) {
        PaymentDetailsResponse response = paymentService.create(customerUuid, request);
        URI location = ServletUriComponentsBuilder
            .fromCurrentRequest()
            .path("/{paymentUuid}")
            .buildAndExpand(response.paymentUuid())
            .toUri();

        return ResponseEntity.created(location).body(response);
    }
}
