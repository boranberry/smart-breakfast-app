package com.smartoffice.breakfast.dto;

import com.smartoffice.breakfast.entity.RoomStatus;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

public class RoomDtos {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateRoomRequest {
        @NotBlank(message = "Restaurant name is required")
        private String restaurantName;

        private String restaurantPhone;

        private String description;

        /**
         * Open the room for a restaurant that already exists. When set, the
         * name and phone are taken from that restaurant and the room starts
         * with its verified menu available.
         */
        private Long restaurantId;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RoomResponse {
        private Long id;
        private String restaurantName;
        private String restaurantPhone;
        private String description;
        private LocalDateTime createdAt;
        private LocalDateTime expiresAt;
        private Long secondsRemaining;
        private RoomStatus status;
        private String createdByName;

        /** Restaurant this room orders from; drives the pre-priced menu. */
        private Long restaurantId;

        /** How many verified menu items that restaurant has. 0 means free entry. */
        private Integer menuItemCount;

        // --- Populated once the receipt has been entered ---
        private Double totalDeliveryFee;
        private Double receiptTotal;
        private LocalDateTime finalizedAt;

        // --- Populated once an admin has approved ---
        private LocalDateTime approvedAt;
        private String approvedByName;
    }
}
