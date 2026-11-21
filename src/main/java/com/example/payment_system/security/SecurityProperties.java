package com.example.payment_system.security;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.security")
public class SecurityProperties {

    /**
     * When true, disables authentication and authorization checks across the API.
     * Intended strictly for local development and unauthenticated scripts.
     */
    private boolean disabled = false;

    private List<String> corsAllowedOrigins = new ArrayList<>(List.of("http://localhost:5173"));

    private BootstrapAdmin bootstrapAdmin = new BootstrapAdmin();

    @Getter
    @Setter
    public static class BootstrapAdmin {
        private boolean enabled = true;
        private String name = "System Admin";
        private String email = "admin@paymentsys.local";
        private String password = "admin12345";
    }
}
