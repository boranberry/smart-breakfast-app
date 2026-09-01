package com.smartoffice.breakfast.controller;

import com.smartoffice.breakfast.dto.OrderDtos.AddOrderRequest;
import com.smartoffice.breakfast.dto.OrderDtos.OrderItemResponse;
import com.smartoffice.breakfast.dto.OrderDtos.RoomOrderSummaryResponse;
import com.smartoffice.breakfast.security.UserPrincipal;
import com.smartoffice.breakfast.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/rooms/{roomId}/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    /**
     * Fires immediately on "Add to Cart" / custom item submit, per spec 5.A:
     * every click triggers an instant save to live_orders.
     */
    @PostMapping
    public ResponseEntity<OrderItemResponse> addOrder(@PathVariable Long roomId,
                                                        @Valid @RequestBody AddOrderRequest request,
                                                        @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(orderService.addOrder(roomId, principal.getUser(), request));
    }

    @DeleteMapping("/{orderId}")
    public ResponseEntity<Void> deleteOrder(@PathVariable Long roomId,
                                             @PathVariable Long orderId,
                                             @AuthenticationPrincipal UserPrincipal principal) {
        orderService.deleteOrder(roomId, orderId, principal.getUser());
        return ResponseEntity.noContent().build();
    }

    /** Regular user: view their own personal active cart within the room. Spec 4.B. */
    @GetMapping("/me")
    public ResponseEntity<List<OrderItemResponse>> getMyCart(@PathVariable Long roomId,
                                                               @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(orderService.getUserCart(roomId, principal.getUser().getId()));
    }

    /** Admin: live aggregated summary table of all items ordered in the room. Spec 4.C. */
    @GetMapping("/summary")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<RoomOrderSummaryResponse> getRoomSummary(@PathVariable Long roomId) {
        return ResponseEntity.ok(orderService.getRoomSummary(roomId));
    }
}
