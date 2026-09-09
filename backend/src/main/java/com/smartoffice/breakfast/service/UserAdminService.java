package com.smartoffice.breakfast.service;

import com.smartoffice.breakfast.dto.UserAdminDtos.BulkPromoteAllResponse;
import com.smartoffice.breakfast.dto.UserAdminDtos.UserResponse;
import com.smartoffice.breakfast.entity.Role;
import com.smartoffice.breakfast.entity.User;
import com.smartoffice.breakfast.exception.BadRequestException;
import com.smartoffice.breakfast.exception.ResourceNotFoundException;
import com.smartoffice.breakfast.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Admin-only user management. Kept separate from {@link AuthService}, which
 * only ever acts on the currently authenticated user (register/login); this
 * service is for an admin acting on someone else's account.
 */
@Service
@RequiredArgsConstructor
public class UserAdminService {

    private static final Logger log = LoggerFactory.getLogger(UserAdminService.class);

    /** Must be typed exactly (case-sensitive) to authorize a bulk promote-all. */
    private static final String BULK_PROMOTE_CONFIRMATION_PHRASE = "CONFIRM";

    private final UserRepository userRepository;


    /** Every registered user, for the admin's "make someone an admin" screen. */
    @Transactional(readOnly = true)
    public List<UserResponse> listUsers() {
        return userRepository.findAllByOrderByNameAsc().stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Promotes an existing user to ADMIN. Idempotent: promoting a user who is
     * already an admin just returns their current state instead of erroring,
     * so a retried request can't fail spuriously.
     */
    @Transactional
    public UserResponse promoteToAdmin(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + userId));

        if (user.getRole() != Role.ADMIN) {
            user.setRole(Role.ADMIN);
            user = userRepository.save(user);
        }

        return toResponse(user);
    }

    /**
     * Reverts a user to a regular USER. An admin cannot demote their own
     * account this way, so a lone admin can never accidentally lock
     * themselves out of the admin-only screens.
     *
     * On top of the self-demote guard, this also refuses to demote the last
     * remaining ADMIN in the system, even when acted on by a *different*
     * admin (e.g. two admins demoting each other in quick succession, or a
     * client that bypasses the "self" check by calling with a stale/forged
     * id). Without this, the system could end up with zero admins and every
     * {@code /api/admin/**} screen would become permanently unreachable.
     */
    @Transactional
    public UserResponse demoteToUser(Long userId, Long actingAdminId) {
        if (userId.equals(actingAdminId)) {
            throw new BadRequestException("You cannot remove your own admin access");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + userId));

        if (user.getRole() == Role.ADMIN) {
            long adminCount = userRepository.countByRole(Role.ADMIN);
            if (adminCount <= 1) {
                throw new BadRequestException(
                        "Cannot demote the last remaining admin. Promote another user to admin first.");
            }
            user.setRole(Role.USER);
            user = userRepository.save(user);
        }

        return toResponse(user);
    }

    /**
     * Promotes every non-admin user to ADMIN in one bulk operation.
     *
     * Safety measures (in order):
     *  1. Confirmation phrase must match exactly — a second line of defence
     *     behind the frontend's "type CONFIRM" modal, in case this endpoint
     *     is ever called directly.
     *  2. The acting admin's own account is excluded at the SQL level (see
     *     {@link UserRepository#promoteAllUsersToAdmin}), so this can never
     *     touch the caller's own row — irrelevant to role escalation since
     *     they're already an admin, but it keeps the audit trail honest
     *     ("promoted N *other* users") and avoids a pointless UPDATE.
     *  3. Only rows currently {@code role <> ADMIN} are touched, so existing
     *     admins are left completely alone.
     *  4. This app has no super-admin flag or disabled/locked account state
     *     today (see {@link com.smartoffice.breakfast.security.UserPrincipal},
     *     whose isEnabled()/isAccountNonLocked() are hard-coded true) — so
     *     there is nothing further to exclude on that front yet. If those
     *     fields are added later, they belong in the WHERE clause of
     *     {@code promoteAllUsersToAdmin} right alongside the id exclusion.
     *  5. Every call is logged at WARN so it stands out in the server logs,
     *     with the acting admin's id, a timestamp, and the resulting count —
     *     this is a privilege-escalation action and should always be
     *     auditable after the fact.
     */
    @Transactional
    public BulkPromoteAllResponse bulkPromoteAllToAdmin(Long actingAdminId, String confirmation) {
        if (confirmation == null || !BULK_PROMOTE_CONFIRMATION_PHRASE.equals(confirmation.trim())) {
            throw new BadRequestException("Confirmation phrase does not match. Type CONFIRM exactly to proceed.");
        }

        long totalUsers = userRepository.count();
        int promotedCount = userRepository.promoteAllUsersToAdmin(actingAdminId);

        // SECURITY AUDIT LOG: privilege escalation of every eligible account.
        log.warn("SECURITY AUDIT: admin id={} bulk-promoted {} user(s) to ADMIN at {}",
                actingAdminId, promotedCount, LocalDateTime.now());

        return BulkPromoteAllResponse.builder()
                .promotedCount(promotedCount)
                .alreadyAdminCount((int) (totalUsers - promotedCount))
                .totalUsers((int) totalUsers)
                .message(promotedCount == 0
                        ? "No eligible users to promote — everyone else is already an admin."
                        : promotedCount + " user(s) promoted to ADMIN.")
                .build();
    }

    private UserResponse toResponse(User user) {
        return UserResponse.builder()
                .id(user.getId())
                .name(user.getName())
                .phone(user.getPhone())
                .role(user.getRole())
                .build();
    }
}