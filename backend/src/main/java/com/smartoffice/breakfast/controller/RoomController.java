package com.smartoffice.breakfast.controller;

import com.smartoffice.breakfast.dto.RoomDtos.CreateRoomRequest;
import com.smartoffice.breakfast.dto.RoomDtos.RoomResponse;
import com.smartoffice.breakfast.security.UserPrincipal;
import com.smartoffice.breakfast.service.RoomService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/rooms")
@RequiredArgsConstructor
public class RoomController {

    private final RoomService roomService;

    /** Admin: create a new room with a 60-minute expiration countdown. Spec 4.C. */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<RoomResponse> createRoom(@Valid @RequestBody CreateRoomRequest request,
                                                    @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(roomService.createRoom(request, principal.getUser()));
    }

    /** Any authenticated user: view active OPEN rooms on the dashboard. Spec 4.B. */
    @GetMapping
    public ResponseEntity<List<RoomResponse>> listRooms(
            @RequestParam(name = "status", required = false) String status) {
        if ("all".equalsIgnoreCase(status)) {
            return ResponseEntity.ok(roomService.getAllRooms());
        }
        return ResponseEntity.ok(roomService.getOpenRooms());
    }

    @GetMapping("/{roomId}")
    public ResponseEntity<RoomResponse> getRoom(@PathVariable Long roomId) {
        return ResponseEntity.ok(roomService.getRoomById(roomId));
    }

    /** Admin: manually close a room before the 60 minutes expire. Spec 4.C. */
    @PostMapping("/{roomId}/close")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<RoomResponse> closeRoom(@PathVariable Long roomId) {
        return ResponseEntity.ok(roomService.closeRoom(roomId));
    }
}
