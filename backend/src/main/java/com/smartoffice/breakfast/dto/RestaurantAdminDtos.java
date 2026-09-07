package com.smartoffice.breakfast.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Admin-only "bulk import" request: create a restaurant together with its
 * complete starting menu in a single call, instead of the normal flow where
 * a menu is built up one approved receipt at a time.
 */
public class RestaurantAdminDtos {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateRestaurantWithMenuRequest {

        @NotBlank(message = "Restaurant name is required")
        private String name;

        /** Optional, same as the free-text phone captured when opening a room. */
        private String phone;

        /**
         * The full starting menu. Reuses {@link MenuItemDto}, which already
         * validates name/price on the inbound side; id and lastVerifiedAt are
         * ignored if the caller sends them.
         */
        @NotEmpty(message = "At least one menu item is required")
        @Valid
        private List<MenuItemDto> menu;
    }

    /**
     * Admin-only manual edit of a restaurant's own fields (name/phone),
     * independent of the receipt-approval workflow. Both fields optional so a
     * caller can patch just one.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateRestaurantRequest {
        private String name;
        private String phone;
    }

    /**
     * Admin-only manual add/edit of a single menu line, for restaurants that
     * need a price fixed or an item added outside of a delivery's receipt
     * cycle (e.g. seeding a menu before the first order, or correcting a typo).
     * Reuses the same validation as the receipt-approval path.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpsertMenuItemRequest {

        @jakarta.validation.constraints.NotBlank(message = "Item name is required")
        private String name;

        @jakarta.validation.constraints.NotNull(message = "Price is required")
        @jakarta.validation.constraints.PositiveOrZero(message = "Price cannot be negative")
        private Double price;
    }
}
