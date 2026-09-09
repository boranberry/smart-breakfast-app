package com.smartoffice.breakfast.controller;

import com.smartoffice.breakfast.dto.ApprovalDtos.RestaurantResponse;
import com.smartoffice.breakfast.dto.MenuItemDto;
import com.smartoffice.breakfast.dto.RestaurantAdminDtos.CreateRestaurantWithMenuRequest;
import com.smartoffice.breakfast.dto.RestaurantAdminDtos.UpdateRestaurantRequest;
import com.smartoffice.breakfast.dto.RestaurantAdminDtos.UpsertMenuItemRequest;
import com.smartoffice.breakfast.service.RestaurantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    /**
     * Admin-only bulk import: create a new restaurant together with its
     * complete starting menu in one call. Locked down twice over: by URL in
     * SecurityConfig ({@code /api/admin/**} requires ROLE_ADMIN) and by
     * {@code @PreAuthorize} here, matching the pattern used by
     * AdminApprovalController.
     */
    @PostMapping("/admin/restaurants")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Bulk import a restaurant with its menu",
            description = "Creates a new restaurant together with its complete starting menu in a single request. Admin-only.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Restaurant created with its menu"),
            @ApiResponse(responseCode = "400", description = "Invalid input data"),
            @ApiResponse(responseCode = "409", description = "A restaurant with this name already exists")
    })
    public ResponseEntity<RestaurantResponse> bulkImportRestaurant(@Valid @RequestBody CreateRestaurantWithMenuRequest request) {
        RestaurantResponse response = restaurantService.createRestaurantWithMenu(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Admin-only manual edit (Feature gap fix): update a restaurant's name
     * and/or phone without going through the receipt-approval workflow.
     * Same double lock-down pattern as bulkImportRestaurant above.
     */
    @PutMapping("/admin/restaurants/{restaurantId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Update a restaurant", description = "Manually updates a restaurant's name and/or phone. Admin-only.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Restaurant updated"),
            @ApiResponse(responseCode = "404", description = "Restaurant not found"),
            @ApiResponse(responseCode = "409", description = "Another restaurant already uses that name")
    })
    public ResponseEntity<RestaurantResponse> updateRestaurant(@PathVariable Long restaurantId,
                                                               @Valid @RequestBody UpdateRestaurantRequest request) {
        return ResponseEntity.ok(restaurantService.updateRestaurant(restaurantId, request));
    }

    /**
     * Admin-only manual menu edit (Feature gap fix): add a new item, or
     * update the price of an existing one (matched by name), without a
     * delivery + receipt cycle. Lets an admin seed a menu ahead of the first
     * order or fix a mispriced item on the spot.
     */
    @PostMapping("/admin/restaurants/{restaurantId}/menu")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Add or update a menu item", description = "Manually adds a new menu item or updates the price of an existing one (matched by name). Admin-only.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Menu item saved"),
            @ApiResponse(responseCode = "404", description = "Restaurant not found")
    })
    public ResponseEntity<MenuItemDto> upsertMenuItem(@PathVariable Long restaurantId,
                                                      @Valid @RequestBody UpsertMenuItemRequest request) {
        return ResponseEntity.ok(restaurantService.upsertMenuItem(restaurantId, request));
    }

    /** Admin-only manual menu edit (Feature gap fix): remove an item entirely. */
    @DeleteMapping("/admin/restaurants/{restaurantId}/menu/{menuItemId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Delete a menu item", description = "Manually removes a menu item from a restaurant. Admin-only.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "Menu item deleted"),
            @ApiResponse(responseCode = "404", description = "Menu item not found")
    })
    public ResponseEntity<Void> deleteMenuItem(@PathVariable Long restaurantId, @PathVariable Long menuItemId) {
        restaurantService.deleteMenuItem(restaurantId, menuItemId);
        return ResponseEntity.noContent().build();
    }

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