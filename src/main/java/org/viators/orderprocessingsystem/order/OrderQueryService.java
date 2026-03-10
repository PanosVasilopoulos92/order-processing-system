package org.viators.orderprocessingsystem.order;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.viators.orderprocessingsystem.common.enums.OrderStateEnum;
import org.viators.orderprocessingsystem.common.enums.StatusEnum;
import org.viators.orderprocessingsystem.exceptions.BusinessValidationException;
import org.viators.orderprocessingsystem.exceptions.ResourceNotFoundException;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class OrderQueryService {

    private final OrderRepository orderRepository;

    public OrderT getOrderForPayment(String orderUuid) {
        return orderRepository.findByUuidAndOrderStateAndStatus(orderUuid, OrderStateEnum.PENDING, StatusEnum.ACTIVE)
            .orElseThrow(() -> new BusinessValidationException("Payment can be created only for active orders in 'PENDING' state"));
    }

    public OrderT getActiveOrder(String orderUuid) {
        return orderRepository.findByUuidAndStatus(orderUuid, StatusEnum.ACTIVE)
            .orElseThrow(() -> new ResourceNotFoundException("Order", "uuid", orderUuid));
    }
}
