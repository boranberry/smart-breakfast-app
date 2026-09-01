package com.smartoffice.breakfast.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "live_orders")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LiveOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_id", nullable = false)
    private Room room;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "item_name", nullable = false, length = 150)
    private String itemName;

    /**
     * What the user believed the item cost when they ordered. Nullable and
     * explicitly untrusted: while the room is OPEN the price may be a guess,
     * absent, or simply wrong. It is overwritten by {@link #verifiedPrice}
     * once the paper receipt is entered.
     */
    @Column(name = "price_at_order")
    private Double priceAtOrder;

    /**
     * Price transcribed from the paper receipt. Null until the receipt is
     * entered; this is the only figure the final bill split is allowed to use.
     */
    @Column(name = "verified_price")
    private Double verifiedPrice;

    @Column(nullable = false)
    @Builder.Default
    private Integer quantity = 1;

    /** The receipt price if we have one, otherwise the user's guess, otherwise 0. */
    @Transient
    public double effectiveUnitPrice() {
        if (verifiedPrice != null) return verifiedPrice;
        return priceAtOrder != null ? priceAtOrder : 0.0;
    }

    @Transient
    public double effectiveLineTotal() {
        return effectiveUnitPrice() * (quantity == null ? 1 : quantity);
    }
}
