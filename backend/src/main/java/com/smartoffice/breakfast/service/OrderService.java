package com.smartoffice.breakfast.service;

import com.smartoffice.breakfast.dto.OrderDtos.*;
import com.smartoffice.breakfast.entity.LiveOrder;
import com.smartoffice.breakfast.entity.Room;
import com.smartoffice.breakfast.entity.User;
import com.smartoffice.breakfast.exception.ResourceNotFoundException;
import com.smartoffice.breakfast.repository.LiveOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final LiveOrderRepository liveOrderRepository;
    private final RoomService roomService;

    /**
     * Immediately persists a single order line as soon as the user adds it, so
     * there is no data loss if the browser tab is closed.
     *
     * The price is deliberately optional here. While the room is OPEN nobody
     * is expected to know what anything actually costs - the real figures come
     * off the paper receipt after delivery.
     */
    @Transactional
    public OrderItemResponse addOrder(Long roomId, User user, AddOrderRequest request) {
        Room room = roomService.getOpenRoomOrThrow(roomId);

        LiveOrder order = LiveOrder.builder()
                .room(room)
                .user(user)
                .itemName(request.getItemName().trim())
                // Stored as 0.0 rather than null so the running subtotal stays
                // arithmetic; effectiveUnitPrice() treats it the same either way.
                .priceAtOrder(request.getPrice() == null ? 0.0 : request.getPrice())
                .quantity(request.getQuantity() == null ? 1 : request.getQuantity())
                .build();

        order = liveOrderRepository.save(order);
        return toItemResponse(order);
    }

    @Transactional
    public void deleteOrder(Long roomId, Long orderId, User user) {
        // Ensure the room is still open before allowing cart edits.
        roomService.getOpenRoomOrThrow(roomId);

        LiveOrder order = liveOrderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order item not found"));

        if (!order.getUser().getId().equals(user.getId())) {
            throw new ResourceNotFoundException("Order item not found");
        }

        liveOrderRepository.delete(order);
    }

    @Transactional(readOnly = true)
    public List<OrderItemResponse> getUserCart(Long roomId, Long userId) {
        return liveOrderRepository.findByRoomIdAndUserId(roomId, userId)
                .stream()
                .map(this::toItemResponse)
                .toList();
    }

    /**
     * Admin view: item-level aggregation (e.g. total count of "Falafel Sandwich")
     * plus the raw per-user order list. This is also the worklist the receipt
     * entry form is built from - one price field per distinct item name.
     */
    @Transactional(readOnly = true)
    public RoomOrderSummaryResponse getRoomSummary(Long roomId) {
        List<LiveOrder> orders = liveOrderRepository.findByRoomId(roomId);

        Map<String, List<LiveOrder>> byItemName = orders.stream()
                .collect(Collectors.groupingBy(LiveOrder::getItemName, LinkedHashMap::new, Collectors.toList()));

        List<AggregatedItemResponse> aggregated = byItemName.entrySet().stream()
                .map(e -> {
                    List<LiveOrder> lines = e.getValue();
                    // A single unit price only makes sense if every line agrees,
                    // which after receipt entry it always does.
                    Double verified = lines.get(0).getVerifiedPrice();
                    boolean uniform = verified != null && lines.stream()
                            .allMatch(o -> o.getVerifiedPrice() != null
                                    && Double.compare(o.getVerifiedPrice(), verified) == 0);

                    return AggregatedItemResponse.builder()
                            .itemName(e.getKey())
                            .totalQuantity(lines.stream().mapToInt(LiveOrder::getQuantity).sum())
                            .totalPrice(lines.stream().mapToDouble(LiveOrder::effectiveLineTotal).sum())
                            .verifiedUnitPrice(uniform ? verified : null)
                            .build();
                })
                .sorted(Comparator.comparing(AggregatedItemResponse::getItemName))
                .toList();

        List<OrderItemResponse> allOrders = orders.stream()
                .map(this::toItemResponse)
                .toList();

        double foodTotal = orders.stream()
                .mapToDouble(LiveOrder::effectiveLineTotal)
                .sum();

        long participantCount = orders.stream()
                .map(o -> o.getUser().getId())
                .distinct()
                .count();

        boolean allVerified = !orders.isEmpty()
                && orders.stream().allMatch(o -> o.getVerifiedPrice() != null);

        return RoomOrderSummaryResponse.builder()
                .roomId(roomId)
                .aggregatedItems(aggregated)
                .allOrders(allOrders)
                .foodTotal(foodTotal)
                .participantCount((int) participantCount)
                .pricesVerified(allVerified)
                .build();
    }

    private OrderItemResponse toItemResponse(LiveOrder order) {
        return OrderItemResponse.builder()
                .id(order.getId())
                .userId(order.getUser().getId())
                .userName(order.getUser().getName())
                .itemName(order.getItemName())
                .priceAtOrder(order.getPriceAtOrder())
                .verifiedPrice(order.getVerifiedPrice())
                .quantity(order.getQuantity())
                .lineTotal(order.effectiveLineTotal())
                .build();
    }
}