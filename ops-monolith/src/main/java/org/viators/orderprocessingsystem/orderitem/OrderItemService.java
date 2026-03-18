package org.viators.orderprocessingsystem.orderitem;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderItemService {

    private final OrderItemRepository orderItemRepository;

    public Set<OrderItemT> getAllOrderItemsForOrderWithProducts(String orderUuid) {
        return orderItemRepository.findAllOrderItemsForOrderWithProducts(orderUuid);
    }
}
