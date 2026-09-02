package com.smartoffice.breakfast.controller;

import com.smartoffice.breakfast.dto.ApprovalDtos.RestaurantResponse;
import com.smartoffice.breakfast.dto.MenuItemDto;
import com.smartoffice.breakfast.service.RestaurantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Restaurants", description = "Restaurant and menu information endpoints")
@SecurityRequirement(name = "bearerAuth")
public class RestaurantController {

    private final RestaurantService restaurantService;

    @GetMapping("/restaurants")
    @Operation(summary = "List all restaurants", description = "Retrieves a list of all available restaurants")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Restaurants retrieved successfully")
    })
    public ResponseEntity<List<RestaurantResponse>> listRestaurants() {
        return ResponseEntity.ok(restaurantService.listRestaurants());
    }

    @GetMapping("/restaurants/{restaurantId}")
    @Operation(summary = "Get restaurant details", description = "Retrieves detailed information about a specific restaurant including its menu")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Restaurant details retrieved successfully"),
            @ApiResponse(responseCode = "404", description = "Restaurant not found")
    })
    public ResponseEntity<RestaurantResponse> getRestaurant(@Parameter(description = "Restaurant ID") @PathVariable Long restaurantId) {
        return ResponseEntity.ok(restaurantService.getRestaurantWithMenu(restaurantId));
    }

    @GetMapping("/restaurants/{restaurantId}/menu")
    @Operation(summary = "Get restaurant menu", description = "Retrieves the menu items for a specific restaurant")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Menu retrieved successfully"),
            @ApiResponse(responseCode = "404", description = "Restaurant not found")
    })
    public ResponseEntity<List<MenuItemDto>> getMenu(@Parameter(description = "Restaurant ID") @PathVariable Long restaurantId) {
        return ResponseEntity.ok(restaurantService.getMenu(restaurantId));
    }

    /**
     * The verified menu for the restaurant this room was opened for. Empty
     * until that restaurant has had at least one receipt approved, which is
     * what the frontend falls back to a free-text price field for.
     */
    @GetMapping("/rooms/{roomId}/menu")
    @Operation(summary = "Get room menu", description = "Retrieves the verified menu for the restaurant associated with this room")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Room menu retrieved successfully"),
            @ApiResponse(responseCode = "404", description = "Room not found")
    })
    public ResponseEntity<List<MenuItemDto>> getRoomMenu(@Parameter(description = "Room ID") @PathVariable Long roomId) {
        return ResponseEntity.ok(restaurantService.getMenuForRoom(roomId));
    }
}
