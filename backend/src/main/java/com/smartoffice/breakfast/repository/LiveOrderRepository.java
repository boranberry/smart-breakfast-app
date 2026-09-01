package com.smartoffice.breakfast.repository;

import com.smartoffice.breakfast.entity.LiveOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LiveOrderRepository extends JpaRepository<LiveOrder, Long> {

    List<LiveOrder> findByRoomId(Long roomId);

    List<LiveOrder> findByRoomIdAndUserId(Long roomId, Long userId);

    void deleteByIdAndUserId(Long id, Long userId);
}
