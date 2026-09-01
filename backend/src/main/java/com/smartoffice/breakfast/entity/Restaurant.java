package com.smartoffice.breakfast.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A restaurant the office actually orders from.
 *
 * Rows are never created speculatively: a Restaurant is materialised the first
 * time an admin opens a room for that name, and it only gains a *verified*
 * menu once a receipt for one of its rooms has been approved.
 */
@Entity
@Table(name = "restaurants",
        uniqueConstraints = @UniqueConstraint(name = "uk_restaurant_name", columnNames = "name"))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Restaurant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(name = "phone", length = 20)
    private String phone;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @OneToMany(mappedBy = "restaurant", cascade = CascadeType.ALL, orphanRemoval = true)
    @ToString.Exclude
    @Builder.Default
    private List<MenuItem> menuItems = new ArrayList<>();
}
