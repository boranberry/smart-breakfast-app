package com.smartoffice.breakfast.scheduler;

import com.smartoffice.breakfast.entity.Room;
import com.smartoffice.breakfast.entity.RoomStatus;
import com.smartoffice.breakfast.repository.RoomRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Implements spec section 5.B "Room Expiration & Lifecycle":
 * checks every minute for OPEN rooms whose created_at is older than the
 * configured duration (default 60 minutes) and flips them to CLOSED.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RoomExpirationScheduler {

    private final RoomRepository roomRepository;

    @Value("${app.room.duration-minutes:60}")
    private long roomDurationMinutes;

    @Scheduled(fixedRateString = "${app.room.scheduler.fixed-rate-ms:60000}")
    @Transactional
    public void closeExpiredRooms() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(roomDurationMinutes);

        List<Room> expiredRooms = roomRepository.findByStatusAndCreatedAtBefore(RoomStatus.OPEN, cutoff);

        if (expiredRooms.isEmpty()) {
            return;
        }

        expiredRooms.forEach(room -> room.setStatus(RoomStatus.CLOSED));
        roomRepository.saveAll(expiredRooms);

        log.info("Auto-closed {} expired room(s): {}", expiredRooms.size(),
                expiredRooms.stream().map(Room::getId).toList());
    }
}
