package com.smartoffice.breakfast.controller;

import com.smartoffice.breakfast.dto.ApprovalDtos.RestaurantResponse;
import com.smartoffice.breakfast.dto.MenuItemDto;
import com.smartoffice.breakfast.service.RestaurantService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only access to restaurants and their verified menus. Any authenticated
 * user may read these: it is how a new room offers pre-priced items.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class RestaurantController {

    private final RestaurantService restaurantService;

    @GetMapping("/restaurants")
    public ResponseEntity<List<RestaurantResponse>> listRestaurants() {
        return ResponseEntity.ok(restaurantService.listRestaurants());
    }

    @GetMapping("/restaurants/{restaurantId}")
    public ResponseEntity<RestaurantResponse> getRestaurant(@PathVariable Long restaurantId) {
        return ResponseEntity.ok(restaurantService.getRestaurantWithMenu(restaurantId));
    }

    @GetMapping("/restaurants/{restaurantId}/menu")
    public ResponseEntity<List<MenuItemDto>> getMenu(@PathVariable Long restaurantId) {
        return ResponseEntity.ok(restaurantService.getMenu(restaurantId));
    }

    /**
     * The verified menu for the restaurant this room was opened for. Empty
     * until that restaurant has had at least one receipt approved, which is
     * what the frontend falls back to a free-text price field for.
     */
    @GetMapping("/rooms/{roomId}/menu")
    public ResponseEntity<List<MenuItemDto>> getRoomMenu(@PathVariable Long roomId) {
        return ResponseEntity.ok(restaurantService.getMenuForRoom(roomId));
    }
}
