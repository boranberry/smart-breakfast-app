package com.smartoffice.breakfast.service;

import com.smartoffice.breakfast.dto.UserAdminDtos.UserResponse;
import com.smartoffice.breakfast.entity.Role;
import com.smartoffice.breakfast.entity.User;
import com.smartoffice.breakfast.exception.BadRequestException;
import com.smartoffice.breakfast.exception.ResourceNotFoundException;
import com.smartoffice.breakfast.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Admin-only user management. Kept separate from {@link AuthService}, which
 * only ever acts on the currently authenticated user (register/login); this
 * service is for an admin acting on someone else's account.
 */
@Service
@RequiredArgsConstructor
public class UserAdminService {

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
     */
    @Transactional
    public UserResponse demoteToUser(Long userId, Long actingAdminId) {
        if (userId.equals(actingAdminId)) {
            throw new BadRequestException("You cannot remove your own admin access");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + userId));

        if (user.getRole() != Role.USER) {
            user.setRole(Role.USER);
            user = userRepository.save(user);
        }

        return toResponse(user);
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
