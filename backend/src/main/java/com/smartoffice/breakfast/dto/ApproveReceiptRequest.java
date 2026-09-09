package com.smartoffice.breakfast.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * What the admin submits when approving a delivered order's paper receipt.
 *
 * The items carry the final, receipt-confirmed price per unit. Whatever the
 * users guessed while the room was open is discarded in favour of these.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApproveReceiptRequest {

    @NotEmpty(message = "At least one receipt item is required")
    @Size(max = 200, message = "At most 200 receipt items per request")
    @Valid
    private List<MenuItemDto> items;

    /**
     * Delivery fee from the receipt. Optional — when omitted, the fee entered
     * at the receipt-entry step is reused.
     */
    @PositiveOrZero(message = "Delivery fee cannot be negative")
    @DecimalMax(value = "10000", message = "Delivery fee is unrealistically high")
    private Double totalDelivery;

    /**
     * Grand total printed on the receipt. Optional; when supplied it is stored
     * alongside the room so the computed total can be reconciled against it.
     */
    @PositiveOrZero(message = "Receipt total cannot be negative")
    @DecimalMax(value = "1000000", message = "Receipt total is unrealistically high")
    private Double receiptTotal;

    /**
     * Whether the approved prices should be written into the restaurant's
     * permanent menu. Defaults to true — that is the point of the workflow —
     * but an admin can approve a one-off order without polluting the menu.
     */
    @Builder.Default
    private Boolean saveToMenu = true;
}