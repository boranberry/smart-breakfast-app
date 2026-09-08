package com.smartoffice.breakfast.dto;

import com.smartoffice.breakfast.entity.Role;
import jakarta.validation.constraints.NotBlank;
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

    /**
     * Body for POST /admin/users/make-all-admin. The confirmation phrase is a
     * server-side belt-and-braces check that mirrors the frontend's "type
     * CONFIRM" modal — a raw POST with no body (a replayed/forged request, or
     * a future button that forgets the confirm step) is rejected even though
     * the endpoint is otherwise a simple no-path-variable action.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BulkPromoteAllRequest {
        @NotBlank(message = "Confirmation phrase is required")
        private String confirmation;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BulkPromoteAllResponse {
        private int promotedCount;
        private int alreadyAdminCount;
        private int totalUsers;
        private String message;
    }
}