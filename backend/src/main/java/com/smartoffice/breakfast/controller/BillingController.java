package com.smartoffice.breakfast.controller;

import com.smartoffice.breakfast.dto.BillingDtos.BillResponse;
import com.smartoffice.breakfast.dto.ReceiptDtos.ReceiptDraftResponse;
import com.smartoffice.breakfast.dto.ReceiptDtos.ReceiptEntryRequest;
import com.smartoffice.breakfast.service.BillingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/rooms")
@RequiredArgsConstructor
public class BillingController {

    private final BillingService billingService;

    /**
     * Step 2: the food has arrived and the paper receipt is in hand. The admin
     * posts the real per-item prices and the delivery fee; the bill is split
     * from those figures and the room moves to PENDING_ADMIN_APPROVAL.
     */
    @PostMapping("/{roomId}/receipt")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ReceiptDraftResponse> enterReceipt(@PathVariable Long roomId,
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
    public ResponseEntity<BillResponse> calculateBill(@PathVariable Long roomId,
                                                       @RequestParam Double totalDelivery) {
        return ResponseEntity.ok(billingService.calculateBill(roomId, totalDelivery));
    }
}
