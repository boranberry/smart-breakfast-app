package com.smartoffice.breakfast.controller;

import com.smartoffice.breakfast.dto.RoomDtos.CreateRoomRequest;
import com.smartoffice.breakfast.dto.RoomDtos.RoomResponse;
import com.smartoffice.breakfast.security.UserPrincipal;
import com.smartoffice.breakfast.service.RoomService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Rooms", description = "Breakfast room management endpoints")
@SecurityRequirement(name = "bearerAuth")
public class RoomController {

    private final RoomService roomService;

    /** Admin: create a new room with a 60-minute expiration countdown. Spec 4.C. */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Create a new room", description = "Creates a new breakfast ordering room with a 60-minute expiration (Admin only)")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Room created successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid request data"),
            @ApiResponse(responseCode = "403", description = "Access denied - Admin only")
    })
    public ResponseEntity<RoomResponse> createRoom(@Valid @RequestBody CreateRoomRequest request,
                                                    @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(roomService.createRoom(request, principal.getUser()));
    }

    /** Any authenticated user: view active OPEN rooms on the dashboard. Spec 4.B. */
    @GetMapping
    @Operation(summary = "List rooms", description = "Retrieves a list of rooms. Use status=all to see all rooms, otherwise only open rooms are returned")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Rooms retrieved successfully")
    })
    public ResponseEntity<List<RoomResponse>> listRooms(
            @Parameter(description = "Filter by status (all for all rooms, otherwise open rooms only)") @RequestParam(name = "status", required = false) String status) {
        if ("all".equalsIgnoreCase(status)) {
            return ResponseEntity.ok(roomService.getAllRooms());
        }
        return ResponseEntity.ok(roomService.getOpenRooms());
    }

    @GetMapping("/{roomId}")
    @Operation(summary = "Get room details", description = "Retrieves detailed information about a specific room")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Room details retrieved successfully"),
            @ApiResponse(responseCode = "404", description = "Room not found")
    })
    public ResponseEntity<RoomResponse> getRoom(@Parameter(description = "Room ID") @PathVariable Long roomId) {
        return ResponseEntity.ok(roomService.getRoomById(roomId));
    }

    /** Admin: manually close a room before the 60 minutes expire. Spec 4.C. */
    @PostMapping("/{roomId}/close")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Close a room", description = "Manually closes a room before its automatic expiration (Admin only)")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Room closed successfully"),
            @ApiResponse(responseCode = "404", description = "Room not found"),
            @ApiResponse(responseCode = "403", description = "Access denied - Admin only")
    })
    public ResponseEntity<RoomResponse> closeRoom(@Parameter(description = "Room ID") @PathVariable Long roomId) {
        return ResponseEntity.ok(roomService.closeRoom(roomId));
    }
}
