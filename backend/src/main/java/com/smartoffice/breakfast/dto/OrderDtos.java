package com.smartoffice.breakfast.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

public class OrderDtos {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AddOrderRequest {
        @NotBlank(message = "Item name is required")
        private String itemName;

        /**
         * Optional. While the room is OPEN the price is either pre-filled from
         * the restaurant's verified menu or left unknown - the figure that ends
         * up on the bill is transcribed from the paper receipt after delivery.
         */
        @PositiveOrZero(message = "Price cannot be negative")
        private Double price;

        @Positive(message = "Quantity must be at least 1")
        private Integer quantity = 1;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderItemResponse {
        private Long id;
        private Long userId;
        private String userName;
        private String itemName;

        /** What the user typed or the menu suggested. Untrusted. */
        private Double priceAtOrder;

        /** Price from the paper receipt; null until the receipt is entered. */
        private Double verifiedPrice;

        private Integer quantity;

        /** Verified price where available, otherwise the ordering-time guess. */
        private Double lineTotal;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AggregatedItemResponse {
        private String itemName;
        private Integer totalQuantity;
        private Double totalPrice;

        /** Receipt unit price for this item, or null if not yet entered. */
        private Double verifiedUnitPrice;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RoomOrderSummaryResponse {
        private Long roomId;
        private List<AggregatedItemResponse> aggregatedItems;
        private List<OrderItemResponse> allOrders;
        private Double foodTotal;
        private Integer participantCount;

        /** True once every line carries a receipt-verified price. */
        private Boolean pricesVerified;
    }
}
