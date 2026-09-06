package com.smartoffice.breakfast.dto;

import com.smartoffice.breakfast.entity.Role;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Admin-facing view of a user account. Returned after admin actions that
 * change a user's state, such as promoting them to ADMIN.
 */
public class UserAdminDtos {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserResponse {
        private Long id;
        private String name;
        private String phone;
        private Role role;
    }
}