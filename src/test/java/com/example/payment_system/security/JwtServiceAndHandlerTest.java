package com.example.payment_system.security;

import com.example.payment_system.entity.Role;
import com.example.payment_system.entity.User;
import com.example.payment_system.repository.UserRepository;
import com.example.payment_system.service.AccountService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.security.SignatureException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class JwtServiceAndHandlerTest {

    private static final String VALID_SECRET = "0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String OTHER_SECRET = "fedcba9876543210fedcba9876543210fedcba9876543210";

    private JwtService createJwtService(String secret, long expirationMs) {
        JwtProperties props = new JwtProperties();
        props.setSecret(secret);
        props.setExpirationMs(expirationMs);
        return new JwtService(props);
    }

    @Test
    void jwtService_rejectsNullOrShortSecret() {
        assertThatThrownBy(() -> createJwtService(null, 60_000L))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> createJwtService("too-short-secret", 60_000L))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void jwtService_generateAndParseToken_roundTripsClaims() {
        JwtService jwtService = createJwtService(VALID_SECRET, 3_600_000L);
        AuthenticatedUser user = new AuthenticatedUser(42L, "alice@example.com", "ignored", Role.ADMIN);

        String token = jwtService.generateToken(user);
        AuthenticatedUser parsed = jwtService.parseToken(token);

        assertThat(jwtService.getExpirationMs()).isEqualTo(3_600_000L);
        assertThat(parsed.id()).isEqualTo(42L);
        assertThat(parsed.email()).isEqualTo("alice@example.com");
        assertThat(parsed.role()).isEqualTo(Role.ADMIN);
        assertThat(parsed.passwordHash()).isEmpty();
    }

    @Test
    void jwtService_expiredToken_throwsExpiredJwtException() {
        JwtService jwtService = createJwtService(VALID_SECRET, -1_000L);
        AuthenticatedUser user = new AuthenticatedUser(1L, "user@example.com", "", Role.USER);

        String expiredToken = jwtService.generateToken(user);

        assertThatThrownBy(() -> jwtService.parseToken(expiredToken))
                .isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void jwtService_tamperedSignature_throwsSignatureException() {
        JwtService issuer = createJwtService(VALID_SECRET, 60_000L);
        JwtService verifier = createJwtService(OTHER_SECRET, 60_000L);
        AuthenticatedUser user = new AuthenticatedUser(1L, "user@example.com", "", Role.USER);

        String token = issuer.generateToken(user);

        assertThatThrownBy(() -> verifier.parseToken(token))
                .isInstanceOf(SignatureException.class);
    }

    @Test
    void jwtService_malformedToken_throwsJwtException() {
        JwtService jwtService = createJwtService(VALID_SECRET, 60_000L);

        assertThatThrownBy(() -> jwtService.parseToken("not.a.valid.jwt"))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void restAccessDeniedHandler_writes403JsonResponse() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        RestAccessDeniedHandler handler = new RestAccessDeniedHandler(mapper);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(request, response, new AccessDeniedException("Forbidden"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).isEqualTo("application/json");
        JsonNode body = mapper.readTree(response.getContentAsByteArray());
        assertThat(body.get("status").asInt()).isEqualTo(403);
        assertThat(body.get("message").asText()).isEqualTo("You do not have permission to perform this action");
    }

    @Test
    void restAuthenticationEntryPoint_writes401JsonResponse() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        RestAuthenticationEntryPoint entryPoint = new RestAuthenticationEntryPoint(mapper);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        entryPoint.commence(request, response, new InsufficientAuthenticationException("Unauthenticated"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).isEqualTo("application/json");
        JsonNode body = mapper.readTree(response.getContentAsByteArray());
        assertThat(body.get("status").asInt()).isEqualTo(401);
        assertThat(body.get("message").asText()).isEqualTo("Authentication is required");
    }

    @Test
    void adminBootstrapRunner_whenAdminMissing_createsAdminAndAccount() {
        SecurityProperties props = new SecurityProperties();
        props.getBootstrapAdmin().setName("Root Admin");
        props.getBootstrapAdmin().setEmail("  ADMIN@Example.com ");
        props.getBootstrapAdmin().setPassword("rawpass123");

        UserRepository userRepository = mock(UserRepository.class);
        AccountService accountService = mock(AccountService.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);

        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("rawpass123")).thenReturn("encoded-admin-pw");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(1L);
            return u;
        });

        AdminBootstrapRunner runner = new AdminBootstrapRunner(props, userRepository, accountService, passwordEncoder);
        runner.run(new DefaultApplicationArguments());

        verify(userRepository).save(argThat(user ->
                "Root Admin".equals(user.getName())
                        && "admin@example.com".equals(user.getEmail())
                        && "encoded-admin-pw".equals(user.getPasswordHash())
                        && user.getRole() == Role.ADMIN
        ));
        verify(accountService).createAccount(any(User.class));
    }

    @Test
    void adminBootstrapRunner_whenAdminAlreadyExists_skipsCreation() {
        SecurityProperties props = new SecurityProperties();
        UserRepository userRepository = mock(UserRepository.class);
        AccountService accountService = mock(AccountService.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);

        when(userRepository.findByEmail("admin@paymentsys.local"))
                .thenReturn(Optional.of(User.builder().id(1L).email("admin@paymentsys.local").role(Role.ADMIN).build()));

        AdminBootstrapRunner runner = new AdminBootstrapRunner(props, userRepository, accountService, passwordEncoder);
        runner.run(new DefaultApplicationArguments());

        verify(userRepository, never()).save(any());
        verifyNoInteractions(accountService);
        verifyNoInteractions(passwordEncoder);
    }

    @Test
    void adminBootstrapRunner_whenDisabledByProperty_isNotRegisteredInContext() {
        ApplicationContextRunner contextRunner = new ApplicationContextRunner()
                .withUserConfiguration(AdminBootstrapRunner.class)
                .withBean(SecurityProperties.class, SecurityProperties::new)
                .withBean(UserRepository.class, () -> mock(UserRepository.class))
                .withBean(AccountService.class, () -> mock(AccountService.class))
                .withBean(PasswordEncoder.class, () -> mock(PasswordEncoder.class));

        contextRunner
                .withPropertyValues("app.security.bootstrap-admin.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(AdminBootstrapRunner.class);
                });

        contextRunner
                .withPropertyValues("app.security.bootstrap-admin.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AdminBootstrapRunner.class);
                });
    }
}
