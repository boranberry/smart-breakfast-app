package com.smartoffice.breakfast.repository;

import com.smartoffice.breakfast.entity.Restaurant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RestaurantRepository extends JpaRepository<Restaurant, Long> {

    /** Names are matched case-insensitively so "Abu Ali" and "abu ali" stay one restaurant. */
    Optional<Restaurant> findByNameIgnoreCase(String name);

    List<Restaurant> findAllByOrderByNameAsc();
}
