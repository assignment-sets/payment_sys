package com.example.payment_system.service;

import com.example.payment_system.dto.UserRequest;
import com.example.payment_system.dto.UserResponse;
import com.example.payment_system.entity.Role;
import com.example.payment_system.entity.User;
import com.example.payment_system.exception.DuplicateUserException;
import com.example.payment_system.exception.UserNotFoundException;
import com.example.payment_system.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final AccountService accountService;
    private final PasswordEncoder passwordEncoder;

    @Transactional(readOnly = true)
    public List<UserResponse> getAllUsers() {
        return userRepository.findAll().stream()
                .map(UserResponse::fromEntity)
                .collect(Collectors.toList());
    }

    @Cacheable(cacheNames = "users", key = "#id")
    @Transactional(readOnly = true)
    public UserResponse getUserById(Long id) {
        return userRepository.findById(id)
                .map(UserResponse::fromEntity)
                .orElseThrow(() -> new UserNotFoundException(id));
    }

    @Transactional
    public UserResponse createUser(UserRequest request) {
        Role role = request.role() != null ? request.role() : Role.USER;
        User saved = createUserWithAccount(request.name(), request.email(), request.password(), role);
        return UserResponse.fromEntity(saved);
    }

    @Transactional
    public User createUserWithAccount(String name, String email, String rawPassword, Role role) {
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        userRepository.findByEmail(normalizedEmail).ifPresent(u -> {
            throw new DuplicateUserException(normalizedEmail);
        });

        User user = User.builder()
                .name(name.trim())
                .email(normalizedEmail)
                .passwordHash(passwordEncoder.encode(rawPassword))
                .role(role)
                .createdAt(OffsetDateTime.now())
                .build();

        User savedUser = userRepository.save(user);
        accountService.createAccount(savedUser);
        return savedUser;
    }
}
