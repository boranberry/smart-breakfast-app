package com.smartoffice.breakfast.service;

import com.smartoffice.breakfast.dto.BillingDtos.BillResponse;
import com.smartoffice.breakfast.dto.BillingDtos.UserBillResponse;
import com.smartoffice.breakfast.dto.MenuItemDto;
import com.smartoffice.breakfast.dto.ReceiptDtos.ReceiptDraftResponse;
import com.smartoffice.breakfast.dto.ReceiptDtos.ReceiptEntryRequest;
import com.smartoffice.breakfast.entity.LiveOrder;
import com.smartoffice.breakfast.entity.Room;
import com.smartoffice.breakfast.entity.RoomStatus;
import com.smartoffice.breakfast.entity.User;
import com.smartoffice.breakfast.exception.BadRequestException;
import com.smartoffice.breakfast.exception.ResourceNotFoundException;
import com.smartoffice.breakfast.repository.LiveOrderRepository;
import com.smartoffice.breakfast.repository.RoomRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BillingService {

    private final LiveOrderRepository liveOrderRepository;
    private final RoomRepository roomRepository;

    /* ---------------------------------------------------------------- *
     * Step 2 of the workflow: receipt entry, once the food has arrived
     * ---------------------------------------------------------------- */

    /**
     * Records the prices from the delivered order's paper receipt, re-splits
     * the bill from those prices, and parks the room in
     * {@link RoomStatus#PENDING_ADMIN_APPROVAL}.
     *
     * Nothing reaches the restaurant menu here: a receipt is only a claim
     * until an admin approves it. See AdminApprovalService.
     */
    @Transactional
    public ReceiptDraftResponse enterReceipt(Long roomId, ReceiptEntryRequest request) {
        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new ResourceNotFoundException("Room not found with id: " + roomId));

        if (room.getStatus() == RoomStatus.OPEN) {
            throw new BadRequestException(
                    "Close the room before entering the receipt - it is still accepting orders");
        }
        if (room.getStatus() == RoomStatus.APPROVED_AND_CLOSED) {
            throw new BadRequestException(
                    "This room has already been approved and can no longer be edited");
        }

        List<LiveOrder> orders = requireOrders(roomId);

        List<String> unpriced = applyReceiptPrices(orders, request.getItems());
        liveOrderRepository.saveAll(orders);

        room.setTotalDeliveryFee(request.getTotalDelivery());
        room.setReceiptTotal(request.getReceiptTotal());
        room.setFinalizedAt(LocalDateTime.now());
        room.setStatus(RoomStatus.PENDING_ADMIN_APPROVAL);
        roomRepository.save(room);

        BillResponse bill = computeBill(room, orders, request.getTotalDelivery());

        Double delta = request.getReceiptTotal() == null
                ? null
                : round2(request.getReceiptTotal() - bill.getGrandTotal());

        return ReceiptDraftResponse.builder()
                .roomId(roomId)
                .status(room.getStatus())
                .bill(bill)
                .unpricedItems(unpriced)
                .reconciliationDelta(delta)
                .build();
    }

    /**
     * Stamps each order line with its receipt price, matching on item name
     * (trimmed, case-insensitive).
     *
     * An item missing from {@code receiptItems} is reported even when the line
     * already carries a price from an earlier submission: on a correction pass
     * the submitted list is authoritative, and silently keeping a stale price
     * for an item the admin dropped is exactly the mistake this workflow exists
     * to prevent.
     *
     * @return the distinct ordered item names this submission did not cover
     */
    public List<String> applyReceiptPrices(List<LiveOrder> orders, List<MenuItemDto> receiptItems) {
        Map<String, Double> priceByName = new LinkedHashMap<>();
        if (receiptItems != null) {
            for (MenuItemDto item : receiptItems) {
                if (item.getName() == null || item.getVerifiedPrice() == null) continue;
                priceByName.put(normalize(item.getName()), item.getVerifiedPrice());
            }
        }

        List<String> unpriced = new ArrayList<>();
        for (LiveOrder order : orders) {
            Double price = priceByName.get(normalize(order.getItemName()));
            if (price != null) {
                order.setVerifiedPrice(price);
            } else if (!unpriced.contains(order.getItemName())) {
                unpriced.add(order.getItemName());
            }
        }
        return unpriced;
    }

    /* ---------------------------------------------------------------- *
     * The split itself
     * ---------------------------------------------------------------- */

    /**
     * The bill-splitting algorithm, unchanged in shape from the original spec
     * but now driven by receipt-verified prices wherever they exist:
     *  1. Take every live_orders row for the room.
     *  2. N = number of distinct users in those rows.
     *  3. deliveryShare = totalDelivery / N.
     *  4. foodSubtotal per user = sum(price * quantity).
     *  5. finalTotal per user = foodSubtotal + deliveryShare.
     *
     * Pure: it reads orders and writes nothing, so the admin panel can render
     * a preview without moving the room through its lifecycle.
     */
    public BillResponse computeBill(Room room, List<LiveOrder> orders, Double totalDelivery) {
        double delivery = totalDelivery == null ? 0.0 : totalDelivery;
        if (delivery < 0) {
            throw new BadRequestException("totalDelivery must be a non-negative number");
        }

        Map<Long, List<LiveOrder>> ordersByUser = orders.stream()
                .collect(Collectors.groupingBy(o -> o.getUser().getId(), LinkedHashMap::new, Collectors.toList()));
        int n = ordersByUser.size();

        double deliveryShare = delivery / n;

        List<UserBillResponse> breakdown = ordersByUser.values().stream()
                .map(userOrders -> {
                    User user = userOrders.get(0).getUser();
                    double foodSubtotal = userOrders.stream()
                            .mapToDouble(LiveOrder::effectiveLineTotal)
                            .sum();

                    return UserBillResponse.builder()
                            .userId(user.getId())
                            .userName(user.getName())
                            .foodSubtotal(round2(foodSubtotal))
                            .deliveryShare(round2(deliveryShare))
                            .finalTotal(round2(foodSubtotal + deliveryShare))
                            .build();
                })
                .sorted(Comparator.comparing(UserBillResponse::getUserName))
                .toList();

        double totalFoodCost = breakdown.stream().mapToDouble(UserBillResponse::getFoodSubtotal).sum();
        boolean allVerified = orders.stream().allMatch(o -> o.getVerifiedPrice() != null);

        return BillResponse.builder()
                .roomId(room.getId())
                .restaurantName(room.getRestaurantName())
                .totalDelivery(round2(delivery))
                .participantCount(n)
                .deliverySharePerPerson(round2(deliveryShare))
                .totalFoodCost(round2(totalFoodCost))
                .grandTotal(round2(totalFoodCost + delivery))
                .breakdown(breakdown)
                .roomStatus(room.getStatus())
                .pricesVerified(allVerified)
                .build();
    }

    /** Read-only split for the admin panel; never changes the room's status. */
    @Transactional(readOnly = true)
    public BillResponse previewBill(Long roomId, Double totalDelivery) {
        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new ResourceNotFoundException("Room not found with id: " + roomId));
        Double delivery = totalDelivery != null ? totalDelivery : room.getTotalDeliveryFee();
        return computeBill(room, requireOrders(roomId), delivery);
    }

    /**
     * Delivery-fee-only finalisation, kept for rooms where the prices users
     * entered already match the receipt. Like {@link #enterReceipt} it stops
     * at PENDING_ADMIN_APPROVAL rather than closing the room outright.
     */
    @Transactional
    public BillResponse calculateBill(Long roomId, Double totalDelivery) {
        if (totalDelivery == null || totalDelivery < 0) {
            throw new BadRequestException("totalDelivery must be a non-negative number");
        }

        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new ResourceNotFoundException("Room not found with id: " + roomId));

        if (room.getStatus() == RoomStatus.APPROVED_AND_CLOSED) {
            throw new BadRequestException(
                    "This room has already been approved and can no longer be edited");
        }

        List<LiveOrder> orders = requireOrders(roomId);

        room.setTotalDeliveryFee(totalDelivery);
        room.setFinalizedAt(LocalDateTime.now());
        room.setStatus(RoomStatus.PENDING_ADMIN_APPROVAL);
        roomRepository.save(room);

        return computeBill(room, orders, totalDelivery);
    }

    List<LiveOrder> requireOrders(Long roomId) {
        List<LiveOrder> orders = liveOrderRepository.findByRoomId(roomId);
        if (orders.isEmpty()) {
            throw new BadRequestException("Cannot calculate a bill for a room with no orders");
        }
        return orders;
    }

    static String normalize(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
