package com.smartoffice.breakfast.controller;

import com.smartoffice.breakfast.dto.BillingDtos.BillResponse;
import com.smartoffice.breakfast.dto.ReceiptDtos.ReceiptDraftResponse;
import com.smartoffice.breakfast.dto.ReceiptDtos.ReceiptEntryRequest;
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
}
