package com.smartoffice.breakfast.dto;

import com.smartoffice.breakfast.entity.Role;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

public class AuthDtos {

    /**
     * Real Egyptian mobile numbers only, in one of three equivalent shapes:
     *  - local:            01[0125]XXXXXXXX          (Exactly 11 digits)
     *  - with +20:         +201[0125]XXXXXXXX        (Exactly 13 characters)
     *  - with 0020:        00201[0125]XXXXXXXX       (Exactly 14 digits)
     */
    public static final String EGYPT_MOBILE_PATTERN =
            "^(01[0125][0-9]{8}|\\+201[0125][0-9]{8}|00201[0125][0-9]{8})$";

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RegisterRequest {
        @NotBlank(message = "Name is required")
        @Size(min = 2, max = 100, message = "Name must be between 2 and 100 characters")
        private String name;

        @NotBlank(message = "Phone is required")
        // إضافة فحص الطول أولاً لضمان عدم قبول أي رقم أطول أو أقصر من الحدود المسموحة بالصيغ الثلاث (11 إلى 14)
        @Size(min = 11, max = 14, message = "Egyptian mobile number must be exactly 11 digits (or 13-14 with country code)")
        @Pattern(regexp = EGYPT_MOBILE_PATTERN,
                message = "Enter a valid Egyptian mobile number (11 digits starting with 010, 011, 012, or 015)")
        private String phone;

        @NotBlank(message = "Password is required")
        @Size(min = 6, max = 72, message = "Password must be between 6 and 72 characters")
        private String password;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LoginRequest {
        @NotBlank(message = "Phone is required")
        @Size(max = 20, message = "Phone number is too long")
        private String phone;

        @NotBlank(message = "Password is required")
        @Size(max = 72, message = "Password is too long")
        private String password;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AuthResponse {
        private String token;
        private Long userId;
        private String name;
        private String phone;
        private Role role;
    }
}