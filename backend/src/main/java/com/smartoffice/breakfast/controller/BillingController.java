package com.smartoffice.breakfast.controller;

import com.smartoffice.breakfast.dto.BillingDtos.BillResponse;
import com.smartoffice.breakfast.dto.BillingDtos.MyBillResponse;
import com.smartoffice.breakfast.dto.ReceiptDtos.ReceiptDraftResponse;
import com.smartoffice.breakfast.dto.ReceiptDtos.ReceiptEntryRequest;
import com.smartoffice.breakfast.security.UserPrincipal;
import com.smartoffice.breakfast.service.BillingService;
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

@RestController
@RequestMapping("/api/rooms")
@RequiredArgsConstructor
@Tag(name = "Billing", description = "Receipt entry and bill calculation endpoints")
@SecurityRequirement(name = "bearerAuth")
public class BillingController {

    private final BillingService billingService;

    /**
     * Step 2: the food has arrived and the paper receipt is in hand. The admin
     * posts the real per-item prices and the delivery fee; the bill is split
     * from those figures and the room moves to PENDING_ADMIN_APPROVAL.
     */
    @PostMapping("/{roomId}/receipt")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Enter receipt details", description = "Posts real per-item prices and delivery fee from the paper receipt. Splits the bill and moves room to PENDING_ADMIN_APPROVAL.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Receipt entered successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid request data"),
            @ApiResponse(responseCode = "403", description = "Access denied - Admin only"),
            @ApiResponse(responseCode = "404", description = "Room not found")
    })
    public ResponseEntity<ReceiptDraftResponse> enterReceipt(@Parameter(description = "Room ID") @PathVariable Long roomId,
                                                             @Valid @RequestBody ReceiptEntryRequest request) {
        return ResponseEntity.ok(billingService.enterReceipt(roomId, request));
    }

    /**
     * Shortcut for rooms where the prices users entered already match the
     * receipt: supply only the delivery fee. Also lands in
     * PENDING_ADMIN_APPROVAL - approval is always a separate, explicit step.
     */
    @PostMapping("/{roomId}/calculate-bill")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Calculate bill with delivery fee", description = "Shortcut for rooms where user-entered prices match the receipt. Only requires the delivery fee.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Bill calculated successfully"),
            @ApiResponse(responseCode = "403", description = "Access denied - Admin only"),
            @ApiResponse(responseCode = "404", description = "Room not found")
    })
    public ResponseEntity<BillResponse> calculateBill(@Parameter(description = "Room ID") @PathVariable Long roomId,
                                                       @Parameter(description = "Total delivery fee") @RequestParam Double totalDelivery) {
        return ResponseEntity.ok(billingService.calculateBill(roomId, totalDelivery));
    }

    /**
     * Bug fix: previously every bill-computing endpoint was ADMIN-only, so a
     * regular user's own room screen had nothing it could call once the admin
     * split the bill — it just kept showing the unverified cart total forever.
     * This endpoint is intentionally open to any authenticated user (no
     * {@code @PreAuthorize}, and not under {@code /api/admin/**}) but only
     * ever returns the caller's own line from the split, never anyone else's.
     * Works as soon as a split exists — including the PENDING_ADMIN_APPROVAL
     * preview stage, not just after final APPROVED_AND_CLOSED approval — so
     * the amount appears the moment the admin enters the receipt, not only
     * after they approve it.
     */
    @GetMapping("/{roomId}/bill/me")
    @Operation(summary = "Get my split for a room", description = "Retrieves the authenticated user's own food subtotal, delivery share, and final total for a room. Available to any participant, not admin-only.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Bill retrieved successfully"),
            @ApiResponse(responseCode = "400", description = "Room has no orders yet"),
            @ApiResponse(responseCode = "404", description = "Room not found, or you have no orders in this room")
    })
    public ResponseEntity<MyBillResponse> getMyBill(@Parameter(description = "Room ID") @PathVariable Long roomId,
                                                     @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(billingService.getMyBill(roomId, principal.getUser().getId()));
    }

    /**
     * Full split, everyone's name and amount included — for a "here's how it
     * was divided" screen. Open to any authenticated user (not admin-only),
     * but {@link BillingService#getBillForParticipant} refuses anyone who
     * isn't themselves a participant in this room, so it can't be used to
     * browse other rooms' bills.
     */
    @GetMapping("/{roomId}/bill")
    @Operation(summary = "Get the full split for a room", description = "Retrieves every participant's food subtotal, delivery share, and final total for a room. Available to any participant who ordered in the room, not admin-only.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Bill retrieved successfully"),
            @ApiResponse(responseCode = "400", description = "Room has no orders yet"),
            @ApiResponse(responseCode = "404", description = "Room not found, or you have no orders in this room")
    })
    public ResponseEntity<BillResponse> getFullBill(@Parameter(description = "Room ID") @PathVariable Long roomId,
                                                     @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(billingService.getBillForParticipant(roomId, principal.getUser().getId()));
    }
}
