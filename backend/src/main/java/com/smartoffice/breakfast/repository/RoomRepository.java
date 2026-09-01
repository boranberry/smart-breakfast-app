package com.smartoffice.breakfast.repository;

import com.smartoffice.breakfast.entity.Room;
import com.smartoffice.breakfast.entity.RoomStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface RoomRepository extends JpaRepository<Room, Long> {

    List<Room> findByStatusOrderByCreatedAtDesc(RoomStatus status);

    List<Room> findByStatusInOrderByCreatedAtDesc(Collection<RoomStatus> statuses);

    List<Room> findByStatusAndCreatedAtBefore(RoomStatus status, LocalDateTime cutoff);

    List<Room> findAllByOrderByCreatedAtDesc();
}
