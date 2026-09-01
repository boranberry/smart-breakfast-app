package com.smartoffice.breakfast.repository;

import com.smartoffice.breakfast.entity.MenuItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MenuItemRepository extends JpaRepository<MenuItem, Long> {

    List<MenuItem> findByRestaurantIdOrderByNameAsc(Long restaurantId);

    Optional<MenuItem> findByRestaurantIdAndNameIgnoreCase(Long restaurantId, String name);
}
