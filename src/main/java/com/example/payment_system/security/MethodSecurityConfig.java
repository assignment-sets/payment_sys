package com.example.payment_system.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/**
 * Enables @PreAuthorize method security only when authentication is active.
 * When app.security.disabled=true, method security is inactive so anonymous
 * requests can reach all endpoints without triggering authorization checks.
 */
@Configuration
@EnableMethodSecurity
@ConditionalOnProperty(name = "app.security.disabled", havingValue = "false", matchIfMissing = true)
public class MethodSecurityConfig {
}
