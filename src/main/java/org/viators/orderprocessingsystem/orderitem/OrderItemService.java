package org.viators.orderprocessingsystem.orderitem;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.viators.orderprocessingsystem.order.OrderService;
import org.viators.orderprocessingsystem.order.OrderT;
import org.viators.orderprocessingsystem.orderitem.dto.request.CreateOrderItemRequest;
import org.viators.orderprocessingsystem.orderitem.dto.response.OrderItemDetailsResponse;
import org.viators.orderprocessingsystem.product.ProductService;
import org.viators.orderprocessingsystem.product.ProductT;

import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderItemService {

    private final OrderItemRepository orderItemRepository;
    private final OrderService orderService;
    private final ProductService productService;

    public OrderItemDetailsResponse create(CreateOrderItemRequest request) {
        ProductT product = productService.getActiveProduct(request.productUuid());
        OrderItemT orderItem = request.toEntity();

        orderItem.setProduct(product);
        orderItem.setProductName(product.getName());
        orderItem.setProductPrice(product.getPrice());
        orderItem = orderItemRepository.save(orderItem);

        return OrderItemDetailsResponse.from(orderItem);
    }

    public Set<OrderItemT> getOrderItemsForUuids(Set<String> uuids) {
        return orderItemRepository.findAllByUuidIn(uuids);
    }

}
