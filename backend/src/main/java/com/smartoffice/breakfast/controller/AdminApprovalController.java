package com.smartoffice.breakfast.controller;

import com.smartoffice.breakfast.dto.ApprovalDtos.ApprovalResponse;
import com.smartoffice.breakfast.dto.ApproveReceiptRequest;
import com.smartoffice.breakfast.dto.BillingDtos.BillResponse;
import com.smartoffice.breakfast.dto.RoomDtos.RoomResponse;
import com.smartoffice.breakfast.security.UserPrincipal;
import com.smartoffice.breakfast.service.AdminApprovalService;
import com.smartoffice.breakfast.service.BillingService;
import com.smartoffice.breakfast.service.RoomService;
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
public class AdminApprovalController {

    private final AdminApprovalService adminApprovalService;
    private final BillingService billingService;
    private final RoomService roomService;

    /**
     * The approval queue: rooms whose receipt has been entered and split but
     * not yet signed off.
     */
    @GetMapping("/rooms/pending-approval")
    public ResponseEntity<List<RoomResponse>> listPendingApproval() {
        return ResponseEntity.ok(adminApprovalService.getRoomsAwaitingApproval());
    }

    /** Rooms the admin still owes something on: closed, or awaiting approval. */
    @GetMapping("/rooms/unapproved")
    public ResponseEntity<List<RoomResponse>> listUnapproved() {
        return ResponseEntity.ok(roomService.getUnapprovedRooms());
    }

    /**
     * The split as it currently stands, so the admin can check the numbers
     * before committing. Read-only: does not advance the room's status.
     */
    @GetMapping("/rooms/{roomId}/bill-preview")
    public ResponseEntity<BillResponse> previewBill(@PathVariable Long roomId,
                                                    @RequestParam(required = false) Double totalDelivery) {
        return ResponseEntity.ok(billingService.previewBill(roomId, totalDelivery));
    }

    /**
     * Approve the finalized receipt: confirm the splits, write the verified
     * prices into the restaurant's menu, and close the room for good.
     */
    @PostMapping("/rooms/{roomId}/approve")
    public ResponseEntity<ApprovalResponse> approve(@PathVariable Long roomId,
                                                    @Valid @RequestBody ApproveReceiptRequest request,
                                                    @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(adminApprovalService.approveRoom(roomId, request, principal.getUser()));
    }
}
