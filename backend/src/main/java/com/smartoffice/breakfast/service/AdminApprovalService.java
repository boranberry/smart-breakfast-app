package com.smartoffice.breakfast.service;

import com.smartoffice.breakfast.dto.ApprovalDtos.ApprovalResponse;
import com.smartoffice.breakfast.dto.ApproveReceiptRequest;
import com.smartoffice.breakfast.dto.BillingDtos.BillResponse;
import com.smartoffice.breakfast.dto.MenuItemDto;
import com.smartoffice.breakfast.dto.RoomDtos.RoomResponse;
import com.smartoffice.breakfast.entity.LiveOrder;
import com.smartoffice.breakfast.entity.MenuItem;
import com.smartoffice.breakfast.entity.Restaurant;
import com.smartoffice.breakfast.entity.Room;
import com.smartoffice.breakfast.entity.RoomStatus;
import com.smartoffice.breakfast.entity.User;
import com.smartoffice.breakfast.exception.BadRequestException;
import com.smartoffice.breakfast.exception.ResourceNotFoundException;
import com.smartoffice.breakfast.repository.LiveOrderRepository;
import com.smartoffice.breakfast.repository.MenuItemRepository;
import com.smartoffice.breakfast.repository.RoomRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Step 3 of the workflow: an admin reviews the entered receipt and approves it.
 *
 * Approval is the only path by which a price becomes part of the restaurant's
 * permanent menu. Everything before it - the guesses typed into an open room,
 * the receipt an admin transcribed - is provisional.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminApprovalService {

    private final RoomRepository roomRepository;
    private final LiveOrderRepository liveOrderRepository;
    private final MenuItemRepository menuItemRepository;
    private final BillingService billingService;
    private final RestaurantService restaurantService;
    private final RoomService roomService;

    /**
     * Approves a room's finalized receipt:
     *  a. confirms the final bill split from the receipt-verified prices,
     *  b. extracts the ordered item names and their verified prices,
     *  c. creates or updates the Restaurant and MenuItem rows,
     *  d. moves the room to APPROVED_AND_CLOSED.
     *
     * All four steps share one transaction: a room never ends up approved with
     * a half-written menu, and never writes a menu without closing.
     */
    @Transactional
    public ApprovalResponse approveRoom(Long roomId, ApproveReceiptRequest request, User admin) {
        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new ResourceNotFoundException("Room not found with id: " + roomId));

        if (room.getStatus() == RoomStatus.APPROVED_AND_CLOSED) {
            throw new BadRequestException("This room was already approved on " + room.getApprovedAt());
        }
        if (room.getStatus() != RoomStatus.PENDING_ADMIN_APPROVAL) {
            throw new BadRequestException(
                    "Enter the receipt before approving. Room is currently " + room.getStatus());
        }

        List<LiveOrder> orders = billingService.requireOrders(roomId);

        // (b) The admin may correct prices at the approval screen, so the
        // submitted items win over whatever receipt entry recorded.
        List<String> unpriced = billingService.applyReceiptPrices(orders, request.getItems());
        if (!unpriced.isEmpty()) {
            throw new BadRequestException(
                    "Cannot approve: no receipt price was given for " + String.join(", ", unpriced));
        }
        liveOrderRepository.saveAll(orders);

        Double delivery = request.getTotalDelivery() != null
                ? request.getTotalDelivery()
                : room.getTotalDeliveryFee();
        if (delivery == null) {
            throw new BadRequestException("Cannot approve: no delivery fee has been recorded for this room");
        }

        // (c) Persist the confirmed prices against the restaurant.
        Restaurant restaurant = restaurantService.findOrCreate(
                room.getRestaurantName(), room.getRestaurantPhone());

        List<MenuItemDto> created = new ArrayList<>();
        List<MenuItemDto> updated = new ArrayList<>();
        if (!Boolean.FALSE.equals(request.getSaveToMenu())) {
            upsertMenu(restaurant, roomId, orders, created, updated);
        }

        // (d) Lock the room.
        room.setRestaurant(restaurant);
        room.setTotalDeliveryFee(delivery);
        if (request.getReceiptTotal() != null) {
            room.setReceiptTotal(request.getReceiptTotal());
        }
        room.setApprovedBy(admin);
        room.setApprovedAt(LocalDateTime.now());
        room.setStatus(RoomStatus.APPROVED_AND_CLOSED);
        roomRepository.save(room);

        // (a) Final split, from the now fully verified prices and the closed room.
        BillResponse bill = billingService.computeBill(room, orders, delivery);

        log.info("Room {} approved by user {}: {} menu item(s) created, {} updated for restaurant '{}'",
                roomId, admin.getId(), created.size(), updated.size(), restaurant.getName());

        return ApprovalResponse.builder()
                .roomId(roomId)
                .status(room.getStatus())
                .approvedAt(room.getApprovedAt())
                .approvedByName(admin.getName())
                .bill(bill)
                .restaurantId(restaurant.getId())
                .restaurantName(restaurant.getName())
                .createdMenuItems(created)
                .updatedMenuItems(updated)
                .build();
    }

    /**
     * Writes one menu line per distinct item actually ordered in the room. Items
     * the admin priced but nobody ordered are ignored: the menu should only
     * grow from food that was genuinely bought and paid for.
     */
    private void upsertMenu(Restaurant restaurant, Long roomId, List<LiveOrder> orders,
                            List<MenuItemDto> created, List<MenuItemDto> updated) {

        // Distinct by normalized name, keeping the first spelling seen.
        Map<String, LiveOrder> distinct = new LinkedHashMap<>();
        for (LiveOrder order : orders) {
            distinct.putIfAbsent(BillingService.normalize(order.getItemName()), order);
        }

        LocalDateTime now = LocalDateTime.now();
        for (LiveOrder order : distinct.values()) {
            String name = order.getItemName().trim();
            Double price = order.getVerifiedPrice();

            MenuItem existing = menuItemRepository
                    .findByRestaurantIdAndNameIgnoreCase(restaurant.getId(), name)
                    .orElse(null);

            if (existing == null) {
                MenuItem saved = menuItemRepository.save(MenuItem.builder()
                        .restaurant(restaurant)
                        .name(name)
                        .verifiedPrice(price)
                        .lastVerifiedAt(now)
                        .verifiedFromRoomId(roomId)
                        .build());
                created.add(RestaurantService.toDto(saved));
                continue;
            }

            boolean priceChanged = existing.getVerifiedPrice() == null
                    || Double.compare(existing.getVerifiedPrice(), price) != 0;

            existing.setVerifiedPrice(price);
            existing.setLastVerifiedAt(now);
            existing.setVerifiedFromRoomId(roomId);
            MenuItem saved = menuItemRepository.save(existing);

            if (priceChanged) {
                updated.add(RestaurantService.toDto(saved));
            }
        }
    }

    /**
     * Rooms sitting in PENDING_ADMIN_APPROVAL, for the admin's approval queue.
     * Mapped inside the transaction so the rooms' lazy createdBy/approvedBy
     * associations resolve while the session is still open.
     */
    @Transactional(readOnly = true)
    public List<RoomResponse> getRoomsAwaitingApproval() {
        return roomRepository.findByStatusOrderByCreatedAtDesc(RoomStatus.PENDING_ADMIN_APPROVAL)
                .stream()
                .map(roomService::toResponse)
                .toList();
    }
}
