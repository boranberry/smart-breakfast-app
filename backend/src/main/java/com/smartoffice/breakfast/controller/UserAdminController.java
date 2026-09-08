package com.smartoffice.breakfast.controller;

import com.smartoffice.breakfast.dto.UserAdminDtos.BulkPromoteAllRequest;
import com.smartoffice.breakfast.dto.UserAdminDtos.BulkPromoteAllResponse;
import com.smartoffice.breakfast.dto.UserAdminDtos.UserResponse;
import com.smartoffice.breakfast.security.UserPrincipal;
import com.smartoffice.breakfast.service.UserAdminService;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Admin-only user management surface.
 *
 * Locked down twice over, same pattern as {@link AdminApprovalController}:
 * by URL in SecurityConfig ({@code /api/admin/**} requires ROLE_ADMIN) and by
 * {@code @PreAuthorize} here, so a future routing change can't quietly expose it.
 */
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin User Management", description = "Admin-only user role management endpoints")
@SecurityRequirement(name = "bearerAuth")
public class UserAdminController {

    private final UserAdminService userAdminService;

    /**
     * Every registered user, so the admin has something to pick from when
     * deciding who to promote or demote. Previously there was no way to list
     * users at all, which made {@code /promote} unusable from the UI — an
     * admin would have had to already know the target's numeric id.
     */
    @GetMapping
    @Operation(summary = "List users", description = "Retrieves every registered user with their current role. Admin-only.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Users retrieved successfully")
    })
    public ResponseEntity<List<UserResponse>> listUsers() {
        return ResponseEntity.ok(userAdminService.listUsers());
    }

    /** Upgrades an existing user's role to ADMIN. */
    @PostMapping("/{userId}/promote")
    @Operation(summary = "Promote user to admin", description = "Upgrades an existing registered user's role to ADMIN. Admin-only.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "User promoted successfully"),
            @ApiResponse(responseCode = "404", description = "User not found")
    })
    public ResponseEntity<UserResponse> promoteToAdmin(@Parameter(description = "User ID") @PathVariable Long userId) {
        return ResponseEntity.ok(userAdminService.promoteToAdmin(userId));
    }

    /**
     * Reverts an admin back to a regular user. An admin can't demote their
     * own account (enforced in the service layer) so nobody can lock
     * themselves out of the admin screens by mistake.
     */
    @PostMapping("/{userId}/demote")
    @Operation(summary = "Demote admin to user", description = "Reverts an existing admin's role to USER. An admin cannot demote themselves. Admin-only.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "User demoted successfully"),
            @ApiResponse(responseCode = "400", description = "Cannot demote your own account"),
            @ApiResponse(responseCode = "404", description = "User not found")
    })
    public ResponseEntity<UserResponse> demoteToUser(@Parameter(description = "User ID") @PathVariable Long userId,
                                                     @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(userAdminService.demoteToUser(userId, principal.getUser().getId()));
    }

    /**
     * Promotes every non-admin user to ADMIN in one shot. Deliberately the
     * most locked-down endpoint in this controller:
     *  - class-level {@code @PreAuthorize("hasRole('ADMIN')")} plus the
     *    {@code /api/admin/**} URL-level rule in SecurityConfig, same as
     *    every other method here;
     *  - additionally requires an exact "CONFIRM" phrase in the request body
     *    (validated in the service), so it can't be triggered by an empty
     *    POST alone;
     *  - the acting admin's own row is excluded at the SQL level, and
     *    existing admins are left untouched;
     *  - every call is logged server-side at WARN with the admin's id and a
     *    timestamp for audit purposes (see UserAdminService).
     */
    @PostMapping("/make-all-admin")
    @Operation(summary = "Promote all users to admin", description = "Bulk-promotes every non-admin user to ADMIN. Requires an exact \"CONFIRM\" phrase in the body. Admin-only, and logged for audit.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Bulk promotion completed"),
            @ApiResponse(responseCode = "400", description = "Missing or incorrect confirmation phrase")
    })
    public ResponseEntity<BulkPromoteAllResponse> makeAllUsersAdmin(@Valid @RequestBody BulkPromoteAllRequest request,
                                                                    @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(userAdminService.bulkPromoteAllToAdmin(principal.getUser().getId(), request.getConfirmation()));
    }
}