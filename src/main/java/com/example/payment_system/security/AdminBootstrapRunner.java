package com.example.payment_system.security;

import com.example.payment_system.entity.Role;
import com.example.payment_system.entity.User;
import com.example.payment_system.repository.UserRepository;
import com.example.payment_system.service.AccountService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

@Slf4j
@Component
@ConditionalOnProperty(name = "app.security.bootstrap-admin.enabled", havingValue = "true")
@RequiredArgsConstructor
public class AdminBootstrapRunner implements ApplicationRunner {

    private final SecurityProperties securityProperties;
    private final UserRepository userRepository;
    private final AccountService accountService;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        var adminConfig = securityProperties.getBootstrapAdmin();
        String email = adminConfig.getEmail().trim().toLowerCase(java.util.Locale.ROOT);

        if (userRepository.findByEmail(email).isPresent()) {
            return;
        }

        User admin = User.builder()
                .name(adminConfig.getName())
                .email(email)
                .passwordHash(passwordEncoder.encode(adminConfig.getPassword()))
                .role(Role.ADMIN)
                .createdAt(OffsetDateTime.now())
                .build();

        User saved = userRepository.save(admin);
        accountService.createAccount(saved);
        log.info("Bootstrapped initial ADMIN user: email={}, id={}", saved.getEmail(), saved.getId());
    }
}
