package com.smartoffice.breakfast.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.LocalDateTime;

@Entity
@Table(name = "rooms")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Room {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Kept as a plain column so historical rooms stay readable even if the
     * restaurant row is later renamed; {@link #restaurant} is the real link.
     */
    @Column(name = "restaurant_name", nullable = false, length = 150)
    private String restaurantName;

    @Column(name = "restaurant_phone", length = 20)
    private String restaurantPhone;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private RoomStatus status = RoomStatus.OPEN;

    /**
     * The restaurant this room orders from. Drives the pre-priced menu shown
     * to users and receives the verified prices once the receipt is approved.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "restaurant_id")
    @ToString.Exclude
    private Restaurant restaurant;

    // --- Finalized bill info, populated once the paper receipt is entered ---

    @Column(name = "total_delivery_fee")
    private Double totalDeliveryFee;

    /** Grand total printed on the paper receipt, for reconciliation. */
    @Column(name = "receipt_total")
    private Double receiptTotal;

    /** When the receipt was entered and the room moved to PENDING_ADMIN_APPROVAL. */
    @Column(name = "finalized_at")
    private LocalDateTime finalizedAt;

    // --- Admin approval trail ---

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approved_by")
    @ToString.Exclude
    private User approvedBy;
}
