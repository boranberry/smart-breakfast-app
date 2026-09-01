package com.smartoffice.breakfast.service;

import com.smartoffice.breakfast.dto.ApprovalDtos.RestaurantResponse;
import com.smartoffice.breakfast.dto.MenuItemDto;
import com.smartoffice.breakfast.entity.MenuItem;
import com.smartoffice.breakfast.entity.Restaurant;
import com.smartoffice.breakfast.entity.Room;
import com.smartoffice.breakfast.exception.BadRequestException;
import com.smartoffice.breakfast.exception.ResourceNotFoundException;
import com.smartoffice.breakfast.repository.MenuItemRepository;
import com.smartoffice.breakfast.repository.RestaurantRepository;
import com.smartoffice.breakfast.repository.RoomRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Owns the verified-menu side of the app: the restaurants rooms are opened
 * for, and the prices that have survived an admin's receipt approval.
 */
@Service
@RequiredArgsConstructor
public class RestaurantService {

    private final RestaurantRepository restaurantRepository;
    private final MenuItemRepository menuItemRepository;
    private final RoomRepository roomRepository;

    /**
     * Looks up a restaurant by name, creating the row if this is the first
     * room ever opened for it. Called when a room is created so that a future
     * approval has somewhere to hang its menu.
     */
    @Transactional
    public Restaurant findOrCreate(String name, String phone) {
        if (name == null || name.isBlank()) {
            throw new BadRequestException("Restaurant name is required");
        }
        String trimmed = name.trim();

        return restaurantRepository.findByNameIgnoreCase(trimmed)
                .map(existing -> {
                    // Backfill a phone number the first time one is supplied.
                    if (existing.getPhone() == null && phone != null && !phone.isBlank()) {
                        existing.setPhone(phone.trim());
                        return restaurantRepository.save(existing);
                    }
                    return existing;
                })
                .orElseGet(() -> restaurantRepository.save(Restaurant.builder()
                        .name(trimmed)
                        .phone(phone == null || phone.isBlank() ? null : phone.trim())
                        .build()));
    }

    @Transactional(readOnly = true)
    public List<RestaurantResponse> listRestaurants() {
        return restaurantRepository.findAllByOrderByNameAsc().stream()
                .map(r -> toResponse(r, menuItemRepository.findByRestaurantIdOrderByNameAsc(r.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public RestaurantResponse getRestaurantWithMenu(Long restaurantId) {
        Restaurant restaurant = restaurantRepository.findById(restaurantId)
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant not found with id: " + restaurantId));
        return toResponse(restaurant, menuItemRepository.findByRestaurantIdOrderByNameAsc(restaurantId));
    }

    /** Verified menu for a restaurant. Empty until a receipt has been approved. */
    @Transactional(readOnly = true)
    public List<MenuItemDto> getMenu(Long restaurantId) {
        return menuItemRepository.findByRestaurantIdOrderByNameAsc(restaurantId).stream()
                .map(RestaurantService::toDto)
                .toList();
    }

    /**
     * Menu for the restaurant a room was opened for. This is what a future
     * room's users pick from, so the prices they see are ones a real receipt
     * has already confirmed.
     */
    @Transactional(readOnly = true)
    public List<MenuItemDto> getMenuForRoom(Long roomId) {
        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new ResourceNotFoundException("Room not found with id: " + roomId));

        Restaurant restaurant = room.getRestaurant();
        if (restaurant == null) {
            // Rooms created before restaurants were linked, or opened for a
            // name that has never been approved: fall back to a name lookup.
            restaurant = restaurantRepository.findByNameIgnoreCase(room.getRestaurantName()).orElse(null);
        }
        return restaurant == null ? List.of() : getMenu(restaurant.getId());
    }

    static MenuItemDto toDto(MenuItem item) {
        return MenuItemDto.builder()
                .id(item.getId())
                .name(item.getName())
                .verifiedPrice(item.getVerifiedPrice())
                .lastVerifiedAt(item.getLastVerifiedAt())
                .build();
    }

    private RestaurantResponse toResponse(Restaurant restaurant, List<MenuItem> menu) {
        return RestaurantResponse.builder()
                .id(restaurant.getId())
                .name(restaurant.getName())
                .phone(restaurant.getPhone())
                .menuItemCount(menu.size())
                .menu(menu.stream().map(RestaurantService::toDto).toList())
                .build();
    }
}
