package com.smartoffice.breakfast.controller;

import com.smartoffice.breakfast.dto.ApprovalDtos.ApprovalResponse;
import com.smartoffice.breakfast.dto.ApproveReceiptRequest;
import com.smartoffice.breakfast.dto.BillingDtos.BillResponse;
import com.smartoffice.breakfast.dto.RoomDtos.RoomResponse;
import com.smartoffice.breakfast.security.UserPrincipal;
import com.smartoffice.breakfast.service.AdminApprovalService;
import com.smartoffice.breakfast.service.BillingService;
import com.smartoffice.breakfast.service.RoomService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Admin-only surface for the post-delivery approval workflow.
 *
 * Locked down twice over: by URL in SecurityConfig (/api/admin/**) and by
 * {@code @PreAuthorize} here, so a future routing change cannot quietly
 * expose it.
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin Approval", description = "Admin-only post-delivery approval workflow endpoints")
@SecurityRequirement(name = "bearerAuth")
public class AdminApprovalController {

    private final AdminApprovalService adminApprovalService;
    private final BillingService billingService;
    private final RoomService roomService;

    /**
     * The approval queue: rooms whose receipt has been entered and split but
     * not yet signed off.
     */
    @GetMapping("/rooms/pending-approval")
    @Operation(summary = "List rooms pending approval", description = "Retrieves rooms whose receipt has been entered but not yet approved")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Pending rooms retrieved successfully")
    })
    public ResponseEntity<List<RoomResponse>> listPendingApproval() {
        return ResponseEntity.ok(adminApprovalService.getRoomsAwaitingApproval());
    }

    /** Rooms the admin still owes something on: closed, or awaiting approval. */
    @GetMapping("/rooms/unapproved")
    @Operation(summary = "List unapproved rooms", description = "Retrieves rooms that are closed or awaiting approval")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Unapproved rooms retrieved successfully")
    })
    public ResponseEntity<List<RoomResponse>> listUnapproved() {
        return ResponseEntity.ok(roomService.getUnapprovedRooms());
    }

    /**
     * The split as it currently stands, so the admin can check the numbers
     * before committing. Read-only: does not advance the room's status.
     */
    @GetMapping("/rooms/{roomId}/bill-preview")
    @Operation(summary = "Preview bill", description = "Previews the bill split before final approval. Read-only operation.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Bill preview retrieved successfully"),
            @ApiResponse(responseCode = "404", description = "Room not found")
    })
    public ResponseEntity<BillResponse> previewBill(@Parameter(description = "Room ID") @PathVariable Long roomId,
                                                    @Parameter(description = "Total delivery fee") @RequestParam(required = false) Double totalDelivery) {
        return ResponseEntity.ok(billingService.previewBill(roomId, totalDelivery));
    }

    /**
     * Approve the finalized receipt: confirm the splits, write the verified
     * prices into the restaurant's menu, and close the room for good.
     */
    @PostMapping("/rooms/{roomId}/approve")
    @Operation(summary = "Approve room receipt", description = "Approves the finalized receipt, confirms splits, writes verified prices to restaurant menu, and closes the room")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Room approved successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid request data"),
            @ApiResponse(responseCode = "404", description = "Room not found")
    })
    public ResponseEntity<ApprovalResponse> approve(@Parameter(description = "Room ID") @PathVariable Long roomId,
                                                    @Valid @RequestBody ApproveReceiptRequest request,
                                                    @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(adminApprovalService.approveRoom(roomId, request, principal.getUser()));
    }
}
