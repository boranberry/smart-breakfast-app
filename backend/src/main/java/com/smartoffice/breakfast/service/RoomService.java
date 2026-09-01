package com.smartoffice.breakfast.service;

import com.smartoffice.breakfast.dto.RoomDtos.CreateRoomRequest;
import com.smartoffice.breakfast.dto.RoomDtos.RoomResponse;
import com.smartoffice.breakfast.entity.Restaurant;
import com.smartoffice.breakfast.entity.Room;
import com.smartoffice.breakfast.entity.RoomStatus;
import com.smartoffice.breakfast.entity.User;
import com.smartoffice.breakfast.exception.ResourceNotFoundException;
import com.smartoffice.breakfast.exception.RoomClosedException;
import com.smartoffice.breakfast.repository.MenuItemRepository;
import com.smartoffice.breakfast.repository.RestaurantRepository;
import com.smartoffice.breakfast.repository.RoomRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RoomService {

    private final RoomRepository roomRepository;
    private final RestaurantRepository restaurantRepository;
    private final MenuItemRepository menuItemRepository;
    private final RestaurantService restaurantService;

    @Value("${app.room.duration-minutes:60}")
    private long roomDurationMinutes;

    /**
     * Opening a room also materialises its Restaurant, so that a later
     * approval has a stable row to attach verified prices to, and so that a
     * repeat order from the same place immediately picks up the saved menu.
     */
    @Transactional
    public RoomResponse createRoom(CreateRoomRequest request, User admin) {
        Restaurant restaurant;
        if (request.getRestaurantId() != null) {
            restaurant = restaurantRepository.findById(request.getRestaurantId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Restaurant not found with id: " + request.getRestaurantId()));
        } else {
            restaurant = restaurantService.findOrCreate(
                    request.getRestaurantName(), request.getRestaurantPhone());
        }

        String phone = request.getRestaurantPhone() != null && !request.getRestaurantPhone().isBlank()
                ? request.getRestaurantPhone()
                : restaurant.getPhone();

        Room room = Room.builder()
                .restaurantName(restaurant.getName())
                .restaurantPhone(phone)
                .description(request.getDescription())
                .createdAt(LocalDateTime.now())
                .createdBy(admin)
                .restaurant(restaurant)
                .status(RoomStatus.OPEN)
                .build();

        room = roomRepository.save(room);
        return toResponse(room);
    }

    @Transactional(readOnly = true)
    public List<RoomResponse> getOpenRooms() {
        return roomRepository.findByStatusOrderByCreatedAtDesc(RoomStatus.OPEN)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<RoomResponse> getAllRooms() {
        return roomRepository.findAllByOrderByCreatedAtDesc()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /** Rooms that still need admin attention: closed but not yet approved. */
    @Transactional(readOnly = true)
    public List<RoomResponse> getUnapprovedRooms() {
        return roomRepository.findByStatusInOrderByCreatedAtDesc(
                        List.of(RoomStatus.CLOSED, RoomStatus.PENDING_ADMIN_APPROVAL))
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public Room getRoomEntityOrThrow(Long roomId) {
        return roomRepository.findById(roomId)
                .orElseThrow(() -> new ResourceNotFoundException("Room not found with id: " + roomId));
    }

    @Transactional(readOnly = true)
    public RoomResponse getRoomById(Long roomId) {
        return toResponse(getRoomEntityOrThrow(roomId));
    }

    /**
     * Ensures a room is still OPEN and hasn't silently expired since the scheduler last ran.
     * Called before any order-mutating action so users can't sneak in writes in the gap
     * between expiry and the next scheduler tick.
     */
    @Transactional
    public Room getOpenRoomOrThrow(Long roomId) {
        Room room = getRoomEntityOrThrow(roomId);

        if (room.getStatus() == RoomStatus.OPEN
                && room.getCreatedAt().plusMinutes(roomDurationMinutes).isBefore(LocalDateTime.now())) {
            room.setStatus(RoomStatus.CLOSED);
            room = roomRepository.save(room);
        }

        if (room.getStatus() != RoomStatus.OPEN) {
            throw new RoomClosedException("This room is closed and no longer accepting orders");
        }

        return room;
    }

    /**
     * Ends the ordering window. The room is not finished at this point - it now
     * waits for the delivered food's receipt to be entered.
     */
    @Transactional
    public RoomResponse closeRoom(Long roomId) {
        Room room = getRoomEntityOrThrow(roomId);

        if (room.getStatus() != RoomStatus.OPEN) {
            throw new RoomClosedException("This room is already " + room.getStatus());
        }

        room.setStatus(RoomStatus.CLOSED);
        room = roomRepository.save(room);
        return toResponse(room);
    }

    /**
     * Package-visible to the admin controller so the approval queue can reuse
     * exactly the room shape the rest of the app renders.
     */
    public RoomResponse toResponse(Room room) {
        LocalDateTime expiresAt = room.getCreatedAt().plusMinutes(roomDurationMinutes);
        long secondsRemaining = room.getStatus() == RoomStatus.OPEN
                ? Math.max(0, Duration.between(LocalDateTime.now(), expiresAt).getSeconds())
                : 0;

        Restaurant restaurant = room.getRestaurant();
        Long restaurantId = restaurant != null ? restaurant.getId() : null;
        int menuItemCount = restaurantId == null
                ? 0
                : menuItemRepository.findByRestaurantIdOrderByNameAsc(restaurantId).size();

        return RoomResponse.builder()
                .id(room.getId())
                .restaurantName(room.getRestaurantName())
                .restaurantPhone(room.getRestaurantPhone())
                .description(room.getDescription())
                .createdAt(room.getCreatedAt())
                .expiresAt(expiresAt)
                .secondsRemaining(secondsRemaining)
                .status(room.getStatus())
                .createdByName(room.getCreatedBy() != null ? room.getCreatedBy().getName() : null)
                .restaurantId(restaurantId)
                .menuItemCount(menuItemCount)
                .totalDeliveryFee(room.getTotalDeliveryFee())
                .receiptTotal(room.getReceiptTotal())
                .finalizedAt(room.getFinalizedAt())
                .approvedAt(room.getApprovedAt())
                .approvedByName(room.getApprovedBy() != null ? room.getApprovedBy().getName() : null)
                .build();
    }
}
