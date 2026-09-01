package com.smartoffice.breakfast.dto;

import com.smartoffice.breakfast.dto.BillingDtos.BillResponse;
import com.smartoffice.breakfast.entity.RoomStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Post-delivery receipt entry: step 2 of the workflow, before admin approval. */
public class ReceiptDtos {

    /**
     * Submitted once the food has arrived and the paper receipt is in hand.
     * Every distinct item ordered in the room must be priced.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReceiptEntryRequest {

        @NotEmpty(message = "At least one receipt item is required")
        @Valid
        private List<MenuItemDto> items;

        @NotNull(message = "Total delivery fee is required")
        @PositiveOrZero(message = "Delivery fee cannot be negative")
        private Double totalDelivery;

        /** Grand total printed on the receipt, used only for reconciliation. */
        @PositiveOrZero(message = "Receipt total cannot be negative")
        private Double receiptTotal;
    }

    /**
     * The draft split produced by receipt entry. Nothing here is final until an
     * admin approves it — {@code status} will read PENDING_ADMIN_APPROVAL.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReceiptDraftResponse {
        private Long roomId;
        private RoomStatus status;
        private BillResponse bill;

        /** Item names ordered in the room that the receipt did not price. */
        private List<String> unpricedItems;

        /** Receipt total minus computed total; non-null only if a receipt total was given. */
        private Double reconciliationDelta;
    }
}
