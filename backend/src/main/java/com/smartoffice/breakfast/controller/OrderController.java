package com.smartoffice.breakfast.controller;

import com.smartoffice.breakfast.dto.OrderDtos.AddOrderRequest;
import com.smartoffice.breakfast.dto.OrderDtos.OrderItemResponse;
import com.smartoffice.breakfast.dto.OrderDtos.RoomOrderSummaryResponse;
import com.smartoffice.breakfast.security.UserPrincipal;
import com.smartoffice.breakfast.service.OrderService;
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
@RequestMapping("/api/rooms/{roomId}/orders")
@RequiredArgsConstructor
@Tag(name = "Orders", description = "Breakfast order management endpoints")
@SecurityRequirement(name = "bearerAuth")
public class OrderController {

    private final OrderService orderService;

    /**
     * Fires immediately on "Add to Cart" / custom item submit, per spec 5.A:
     * every click triggers an instant save to live_orders.
     */
    @PostMapping
    @Operation(summary = "Add an order item", description = "Adds a breakfast item to the user's cart. Saves immediately to live_orders.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Order item added successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid request data"),
            @ApiResponse(responseCode = "404", description = "Room not found")
    })
    public ResponseEntity<OrderItemResponse> addOrder(@Parameter(description = "Room ID") @PathVariable Long roomId,
                                                        @Valid @RequestBody AddOrderRequest request,
                                                        @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(orderService.addOrder(roomId, principal.getUser(), request));
    }

    @DeleteMapping("/{orderId}")
    @Operation(summary = "Delete an order item", description = "Removes a specific order item from the user's cart")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "Order item deleted successfully"),
            @ApiResponse(responseCode = "404", description = "Order item not found")
    })
    public ResponseEntity<Void> deleteOrder(@Parameter(description = "Room ID") @PathVariable Long roomId,
                                             @Parameter(description = "Order ID") @PathVariable Long orderId,
                                             @AuthenticationPrincipal UserPrincipal principal) {
        orderService.deleteOrder(roomId, orderId, principal.getUser());
        return ResponseEntity.noContent().build();
    }

    /** Regular user: view their own personal active cart within the room. Spec 4.B. */
    @GetMapping("/me")
    @Operation(summary = "Get user's cart", description = "Retrieves the current user's active cart items in the room")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Cart retrieved successfully")
    })
    public ResponseEntity<List<OrderItemResponse>> getMyCart(@Parameter(description = "Room ID") @PathVariable Long roomId,
                                                               @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(orderService.getUserCart(roomId, principal.getUser().getId()));
    }

    /** Admin: live aggregated summary table of all items ordered in the room. Spec 4.C. */
    @GetMapping("/summary")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Get room order summary", description = "Retrieves a live aggregated summary of all items ordered in the room (Admin only)")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Summary retrieved successfully"),
            @ApiResponse(responseCode = "403", description = "Access denied - Admin only")
    })
    public ResponseEntity<RoomOrderSummaryResponse> getRoomSummary(@Parameter(description = "Room ID") @PathVariable Long roomId) {
        return ResponseEntity.ok(orderService.getRoomSummary(roomId));
    }
}
