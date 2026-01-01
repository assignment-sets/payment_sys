package com.example.payment_system.service;

import com.example.payment_system.dto.DetailedUserResponse;
import com.example.payment_system.dto.UserRequest;
import com.example.payment_system.dto.UserResponse;
import com.example.payment_system.entity.Account;
import com.example.payment_system.entity.User;
import com.example.payment_system.exception.DuplicateUserException;
import com.example.payment_system.repository.UserRepository;
import com.example.payment_system.exception.UserNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final AccountService accountService;

    @Transactional(readOnly = true)
    public List<UserResponse> getAllUsers() {
        return userRepository.findAll().stream()
                .map(UserResponse::fromEntity)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public DetailedUserResponse getUserById(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException(id));

        // Fetch the associated account
        Account account = accountService.getAccountByUserId(id);

        return DetailedUserResponse.fromEntity(user, account);
    }

    @Transactional
    public UserResponse createUser(UserRequest request) {
        userRepository.findByEmail(request.email()).ifPresent(u -> {
            throw new DuplicateUserException(request.email());
        });

        User user = User.builder()
                .name(request.name())
                .email(request.email())
                .createdAt(OffsetDateTime.now())
                .build();

        User savedUser = userRepository.save(user);

        // Create the account for the new user
        accountService.createAccount(savedUser);

        return UserResponse.fromEntity(savedUser);
    }
}