package com.smartoffice.breakfast.dto;

import com.smartoffice.breakfast.dto.BillingDtos.BillResponse;
import com.smartoffice.breakfast.entity.RoomStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

public class ApprovalDtos {

    /** A restaurant plus its verified menu, as served to future rooms. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RestaurantResponse {
        private Long id;
        private String name;
        private String phone;
        private Integer menuItemCount;
        private List<MenuItemDto> menu;
    }

    /** Result of POST /api/admin/rooms/{roomId}/approve. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ApprovalResponse {
        private Long roomId;
        private RoomStatus status;
        private LocalDateTime approvedAt;
        private String approvedByName;

        /** The locked-in split every participant owes. */
        private BillResponse bill;

        private Long restaurantId;
        private String restaurantName;

        /** Menu lines created by this approval. */
        private List<MenuItemDto> createdMenuItems;

        /** Menu lines whose verified price this approval changed. */
        private List<MenuItemDto> updatedMenuItems;
    }
}
