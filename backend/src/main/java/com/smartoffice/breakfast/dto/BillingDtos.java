package com.smartoffice.breakfast.dto;

import com.smartoffice.breakfast.entity.RoomStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

public class BillingDtos {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserBillResponse {
        private Long userId;
        private String userName;
        private Double foodSubtotal;
        private Double deliveryShare;
        private Double finalTotal;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BillResponse {
        private Long roomId;
        private String restaurantName;
        private Double totalDelivery;
        private Integer participantCount;
        private Double deliverySharePerPerson;
        private Double totalFoodCost;
        private Double grandTotal;
        private List<UserBillResponse> breakdown;

        /** Where the room stood when this split was produced. */
        private RoomStatus roomStatus;

        /**
         * True only when every ordered line was priced from the paper receipt.
         * A false value means at least one line is still a guess typed during
         * the open room, so this split is a preview and nothing is owed yet.
         */
        private Boolean pricesVerified;
    }

    /**
     * A single participant's own split, for the "what do I owe" screen.
     * Deliberately narrower than {@link BillResponse}: it never includes
     * other participants' names or amounts, only room-level context plus the
     * calling user's own numbers.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MyBillResponse {
        private Long roomId;
        private String restaurantName;
        private RoomStatus roomStatus;
        private Boolean pricesVerified;
        private Integer participantCount;
        private Double deliverySharePerPerson;
        private Double foodSubtotal;
        private Double deliveryShare;
        private Double finalTotal;
    }
}
