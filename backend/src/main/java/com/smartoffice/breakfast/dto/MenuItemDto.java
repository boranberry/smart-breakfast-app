package com.smartoffice.breakfast.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * One verified menu line. Used in both directions:
 *  - inbound, as an item of {@link ApproveReceiptRequest} carrying the price
 *    the admin read off the paper receipt;
 *  - outbound, when serving a restaurant's saved menu to a future room.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MenuItemDto {

    /** Null on the way in (the item may not exist yet), populated on the way out. */
    private Long id;

    @NotBlank(message = "Item name is required")
    private String name;

    @NotNull(message = "Verified price is required")
    @PositiveOrZero(message = "Verified price cannot be negative")
    private Double verifiedPrice;

    private LocalDateTime lastVerifiedAt;
}
