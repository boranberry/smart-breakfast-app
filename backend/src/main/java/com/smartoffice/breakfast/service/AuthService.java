package com.smartoffice.breakfast.service;

import com.smartoffice.breakfast.dto.AuthDtos.AuthResponse;
import com.smartoffice.breakfast.dto.AuthDtos.LoginRequest;
import com.smartoffice.breakfast.dto.AuthDtos.RegisterRequest;
import com.smartoffice.breakfast.entity.Role;
import com.smartoffice.breakfast.entity.User;
import com.smartoffice.breakfast.exception.DuplicateResourceException;
import com.smartoffice.breakfast.repository.UserRepository;
import com.smartoffice.breakfast.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;
    private final AuthenticationManager authenticationManager;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        // Trim before both the uniqueness check and the save: otherwise
        // "0123456789" and " 0123456789 " would be treated as different
        // accounts by existsByPhone/save but the same account by anyone
        // reading the column back, and a trailing-space phone would be
        // impossible to log back into from a form that trims on the way in.
        String name = request.getName().trim();
        String phone = request.getPhone().trim();

        if (userRepository.existsByPhone(phone)) {
            throw new DuplicateResourceException("A user with this phone number already exists");
        }

        // The very first registered user in the system becomes ADMIN automatically,
        // so there's always at least one admin able to create rooms. Everyone after is a regular USER.
        Role role = userRepository.count() == 0 ? Role.ADMIN : Role.USER;

        User user = User.builder()
                .name(name)
                .phone(phone)
                .password(request.getPassword())
                .role(role)
                .build();

        user = userRepository.save(user);

        String token = jwtUtil.generateToken(user.getId(), user.getPhone(), user.getRole().name());

        return AuthResponse.builder()
                .token(token)
                .userId(user.getId())
                .name(user.getName())
                .phone(user.getPhone())
                .role(user.getRole())
                .build();
    }

    public AuthResponse login(LoginRequest request) {
        String phone = request.getPhone().trim();
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(phone, request.getPassword()));
        } catch (org.springframework.security.core.AuthenticationException ex) {
            throw new BadCredentialsException("Invalid phone or password");
        }

        User user = userRepository.findByPhone(phone)
                .orElseThrow(() -> new BadCredentialsException("Invalid phone or password"));

        String token = jwtUtil.generateToken(user.getId(), user.getPhone(), user.getRole().name());

        return AuthResponse.builder()
                .token(token)
                .userId(user.getId())
                .name(user.getName())
                .phone(user.getPhone())
                .role(user.getRole())
                .build();
    }
}