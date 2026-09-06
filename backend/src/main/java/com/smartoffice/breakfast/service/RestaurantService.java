package com.smartoffice.breakfast.service;

import com.smartoffice.breakfast.dto.ApprovalDtos.RestaurantResponse;
import com.smartoffice.breakfast.dto.MenuItemDto;
import com.smartoffice.breakfast.dto.RestaurantAdminDtos.CreateRestaurantWithMenuRequest;
import com.smartoffice.breakfast.dto.RestaurantAdminDtos.UpdateRestaurantRequest;
import com.smartoffice.breakfast.dto.RestaurantAdminDtos.UpsertMenuItemRequest;
import com.smartoffice.breakfast.entity.MenuItem;
import com.smartoffice.breakfast.entity.Restaurant;
import com.smartoffice.breakfast.entity.Room;
import com.smartoffice.breakfast.exception.BadRequestException;
import com.smartoffice.breakfast.exception.DuplicateResourceException;
import com.smartoffice.breakfast.exception.ResourceNotFoundException;
import com.smartoffice.breakfast.repository.MenuItemRepository;
import com.smartoffice.breakfast.repository.RestaurantRepository;
import com.smartoffice.breakfast.repository.RoomRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    /**
     * Admin-only bulk import: creates a brand-new restaurant together with
     * its complete starting menu in one transaction. Unlike {@link #findOrCreate},
     * this never silently reuses an existing restaurant — an admin doing a
     * bulk import expects to be creating something new, so a name collision
     * is rejected rather than merged.
     *
     * The menu items are attached to the (transient) restaurant before it is
     * saved, so {@code CascadeType.ALL} on {@link Restaurant#getMenuItems()}
     * persists all of them in the same INSERT batch as the restaurant itself.
     */
    @Transactional
    public RestaurantResponse createRestaurantWithMenu(CreateRestaurantWithMenuRequest request) {
        String trimmedName = request.getName().trim();

        if (restaurantRepository.findByNameIgnoreCase(trimmedName).isPresent()) {
            throw new DuplicateResourceException("A restaurant named '" + trimmedName + "' already exists");
        }

        // Catch a same-request duplicate item name here, with a clear message,
        // rather than letting it surface as a raw DB constraint violation
        // (uk_menu_item_restaurant_name) once cascaded on save.
        Set<String> seenNames = new HashSet<>();
        for (MenuItemDto item : request.getMenu()) {
            if (!seenNames.add(item.getName().trim().toLowerCase())) {
                throw new BadRequestException("Duplicate menu item name in request: " + item.getName());
            }
        }

        Restaurant restaurant = Restaurant.builder()
                .name(trimmedName)
                .phone(request.getPhone() == null || request.getPhone().isBlank() ? null : request.getPhone().trim())
                .build();

        LocalDateTime importedAt = LocalDateTime.now();
        List<MenuItem> menuItems = request.getMenu().stream()
                .map(dto -> MenuItem.builder()
                        .restaurant(restaurant)
                        .name(dto.getName().trim())
                        .verifiedPrice(dto.getVerifiedPrice())
                        .lastVerifiedAt(importedAt)
                        .build())
                .toList();
        restaurant.setMenuItems(menuItems);

        Restaurant saved = restaurantRepository.save(restaurant);
        return toResponse(saved, saved.getMenuItems());
    }

    /**
     * Admin-only manual edit of a restaurant's own fields, independent of the
     * receipt-approval workflow (spec gap: previously the only way to touch a
     * restaurant's name/phone was to create a brand new one). Renaming checks
     * for a collision the same way bulk-import does, since {@code name} is
     * unique.
     */
    @Transactional
    public RestaurantResponse updateRestaurant(Long restaurantId, UpdateRestaurantRequest request) {
        Restaurant restaurant = restaurantRepository.findById(restaurantId)
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant not found with id: " + restaurantId));

        if (request.getName() != null && !request.getName().isBlank()) {
            String trimmed = request.getName().trim();
            restaurantRepository.findByNameIgnoreCase(trimmed)
                    .filter(existing -> !existing.getId().equals(restaurantId))
                    .ifPresent(existing -> {
                        throw new DuplicateResourceException("A restaurant named '" + trimmed + "' already exists");
                    });
            restaurant.setName(trimmed);
        }
        if (request.getPhone() != null) {
            restaurant.setPhone(request.getPhone().isBlank() ? null : request.getPhone().trim());
        }

        Restaurant saved = restaurantRepository.save(restaurant);
        return toResponse(saved, menuItemRepository.findByRestaurantIdOrderByNameAsc(restaurantId));
    }

    /**
     * Admin-only manual menu edit: add a brand-new item, or update the price
     * of an existing one (matched case-insensitively by name), without going
     * through a delivery + receipt cycle. Fills the gap where a restaurant
     * with an empty menu had no way to get one started, and a mispriced item
     * had no way to be corrected outside of a full room approval.
     */
    @Transactional
    public MenuItemDto upsertMenuItem(Long restaurantId, UpsertMenuItemRequest request) {
        Restaurant restaurant = restaurantRepository.findById(restaurantId)
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant not found with id: " + restaurantId));

        String trimmedName = request.getName().trim();
        MenuItem item = menuItemRepository.findByRestaurantIdAndNameIgnoreCase(restaurantId, trimmedName)
                .orElseGet(() -> MenuItem.builder().restaurant(restaurant).name(trimmedName).build());

        item.setVerifiedPrice(request.getPrice());
        item.setLastVerifiedAt(LocalDateTime.now());

        MenuItem saved = menuItemRepository.save(item);
        return toDto(saved);
    }

    /** Admin-only manual menu edit: remove an item entirely. */
    @Transactional
    public void deleteMenuItem(Long restaurantId, Long menuItemId) {
        MenuItem item = menuItemRepository.findById(menuItemId)
                .orElseThrow(() -> new ResourceNotFoundException("Menu item not found with id: " + menuItemId));

        if (!item.getRestaurant().getId().equals(restaurantId)) {
            // Don't leak whether the item exists under a different restaurant.
            throw new ResourceNotFoundException("Menu item not found with id: " + menuItemId);
        }

        menuItemRepository.delete(item);
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
