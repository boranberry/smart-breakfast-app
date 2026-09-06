package com.smartoffice.breakfast.repository;

import com.smartoffice.breakfast.entity.Room;
import com.smartoffice.breakfast.entity.RoomStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface RoomRepository extends JpaRepository<Room, Long> {

    List<Room> findByStatusOrderByCreatedAtDesc(RoomStatus status);

    List<Room> findByStatusInOrderByCreatedAtDesc(Collection<RoomStatus> statuses);

    List<Room> findByStatusAndCreatedAtBefore(RoomStatus status, LocalDateTime cutoff);

    List<Room> findAllByOrderByCreatedAtDesc();

    /**
     * A user's own room history: only rooms they actually placed at least one
     * order in (regardless of current status), most recent first. Backs the
     * Account screen's "my rooms & bills" list — deliberately narrower than
     * {@link #findAllByOrderByCreatedAtDesc()}, which is every room for
     * everyone and belongs on the shared dashboard, not a personal account view.
     */
    @Query("SELECT DISTINCT r FROM Room r JOIN LiveOrder o ON o.room = r WHERE o.user.id = :userId ORDER BY r.createdAt DESC")
    List<Room> findRoomsOrderedInByUserId(@Param("userId") Long userId);
}