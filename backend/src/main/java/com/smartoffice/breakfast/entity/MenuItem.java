package com.smartoffice.breakfast.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * A menu line with a price that has been confirmed against a real paper
 * receipt and approved by an admin. These are the only prices the app ever
 * pre-fills for a new order — anything a user typed by hand during an open
 * room stays unverified until it survives the approval step.
 */
@Entity
@Table(name = "menu_items",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_menu_item_restaurant_name",
                columnNames = {"restaurant_id", "name"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MenuItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "restaurant_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Restaurant restaurant;

    @Column(nullable = false, length = 150)
    private String name;

    /** Price taken from the approved receipt, not from what a user guessed. */
    @Column(name = "verified_price", nullable = false)
    private Double verifiedPrice;

    /** When this price was last confirmed by an approved receipt. */
    @Column(name = "last_verified_at")
    private LocalDateTime lastVerifiedAt;

    /** Room whose approved receipt last set this price — an audit trail. */
    @Column(name = "verified_from_room_id")
    private Long verifiedFromRoomId;
}
