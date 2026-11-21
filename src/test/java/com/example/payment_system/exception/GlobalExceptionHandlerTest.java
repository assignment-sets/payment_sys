package com.example.payment_system.exception;

import com.example.payment_system.dto.ErrorResponse;
import com.example.payment_system.entity.TransferStatus;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
    }

    @SuppressWarnings("unused")
    private void dummyTarget(String param) {
    }

    static Stream<Arguments> paymentExceptions() {
        UUID id = UUID.fromString("11111111-2222-3333-4444-555555555555");
        return Stream.of(
                Arguments.of(
                        new InsufficientBalanceException(10L),
                        HttpStatus.PAYMENT_REQUIRED,
                        "Insufficient balance for account: 10"
                ),
                Arguments.of(
                        new AccountFrozenException(20L),
                        HttpStatus.FORBIDDEN,
                        "Account is frozen and cannot send transfers: 20"
                ),
                Arguments.of(
                        new UserNotFoundException(30L),
                        HttpStatus.NOT_FOUND,
                        "User not found: 30"
                ),
                Arguments.of(
                        new AccountNotFoundException(40L),
                        HttpStatus.NOT_FOUND,
                        "Account not found: 40"
                ),
                Arguments.of(
                        AccountNotFoundException.forUser(50L),
                        HttpStatus.NOT_FOUND,
                        "Account not found for user: 50"
                ),
                Arguments.of(
                        new TransferNotFoundException(id),
                        HttpStatus.NOT_FOUND,
                        "Transfer not found for transaction: " + id
                ),
                Arguments.of(
                        new ReportNotFoundException(id),
                        HttpStatus.NOT_FOUND,
                        "Report not found: " + id
                ),
                Arguments.of(
                        new InvalidTransferStateException(TransferStatus.POSTED, TransferStatus.PENDING),
                        HttpStatus.CONFLICT,
                        "Invalid transfer state. Current: POSTED, Expected: PENDING"
                ),
                Arguments.of(
                        new ConcurrentUpdateException(),
                        HttpStatus.CONFLICT,
                        "Concurrent modification detected. Please retry the operation."
                ),
                Arguments.of(
                        new DuplicateUserException("alice@example.com"),
                        HttpStatus.CONFLICT,
                        "User with email already exists: alice@example.com"
                ),
                Arguments.of(
                        new DuplicateTransferException("idem-123"),
                        HttpStatus.CONFLICT,
                        "Duplicate transfer request with idempotency key: idem-123"
                ),
                Arguments.of(
                        new ReportNotReadyException(id, "PROCESSING"),
                        HttpStatus.CONFLICT,
                        "Report " + id + " is not ready for download (status=PROCESSING)"
                ),
                Arguments.of(
                        new InvalidReportRequestException("fromDate must be before toDate"),
                        HttpStatus.BAD_REQUEST,
                        "fromDate must be before toDate"
                )
        );
    }

    @ParameterizedTest
    @MethodSource("paymentExceptions")
    void handlePaymentException_mapsStatusAndMessage(
            PaymentException ex,
            HttpStatus expectedStatus,
            String expectedMessage
    ) {
        ResponseEntity<ErrorResponse> response = handler.handlePaymentException(ex);

        assertThat(response.getStatusCode()).isEqualTo(expectedStatus);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(expectedStatus.value());
        assertThat(response.getBody().message()).isEqualTo(expectedMessage);
        assertThat(response.getBody().error()).isNull();
        assertThat(response.getBody().fieldErrors()).isNull();
        assertThat(response.getBody().timestamp()).isNotNull();
    }

    @Test
    void handleMethodArgumentNotValid_returnsBadRequestWithFieldErrors() throws NoSuchMethodException {
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "transferRequest");
        bindingResult.addError(new FieldError("transferRequest", "amount", "must be positive"));
        bindingResult.addError(new FieldError("transferRequest", "toAccountId", "must not be null"));

        MethodParameter param = new MethodParameter(
                GlobalExceptionHandlerTest.class.getDeclaredMethod("dummyTarget", String.class), 0);
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(param, bindingResult);

        ResponseEntity<ErrorResponse> response = handler.handleMethodArgumentNotValid(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(400);
        assertThat(response.getBody().error()).isEqualTo("Validation failed");
        assertThat(response.getBody().message()).isNull();
        assertThat(response.getBody().fieldErrors())
                .containsEntry("amount", "must be positive")
                .containsEntry("toAccountId", "must not be null");
    }

    @Test
    void handleConstraintViolation_returnsBadRequestWithViolationsMap() {
        ConstraintViolation<?> violation = mock(ConstraintViolation.class);
        Path path = mock(Path.class);
        when(path.toString()).thenReturn("getAccount.accountId");
        when(violation.getPropertyPath()).thenReturn(path);
        when(violation.getMessage()).thenReturn("must be greater than 0");

        ConstraintViolationException ex = new ConstraintViolationException(Set.of(violation));

        ResponseEntity<ErrorResponse> response = handler.handleConstraintViolation(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(400);
        assertThat(response.getBody().error()).isEqualTo("Validation failed");
        assertThat(response.getBody().fieldErrors()).containsEntry("getAccount.accountId", "must be greater than 0");
    }

    @Test
    void handleTypeMismatch_returnsBadRequestWithFormattedMessage() {
        MethodArgumentTypeMismatchException ex = mock(MethodArgumentTypeMismatchException.class);
        when(ex.getName()).thenReturn("accountId");
        when(ex.getValue()).thenReturn("not-a-number");

        ResponseEntity<ErrorResponse> response = handler.handleTypeMismatch(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(400);
        assertThat(response.getBody().message())
                .isEqualTo("The value 'not-a-number' is not a valid number or is out of range.");
    }

    @Test
    void handleMessageNotReadable_returnsBadRequestWithMalformedMessage() {
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException(
                "JSON parse error",
                new MockHttpInputMessage("{invalid".getBytes(StandardCharsets.UTF_8))
        );

        ResponseEntity<ErrorResponse> response = handler.handleMessageNotReadable(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(400);
        assertThat(response.getBody().message()).isEqualTo("Malformed request body");
    }

    @Test
    void handleRateLimitExceeded_returnsTooManyRequestsWithRetryAfterHeader() {
        RateLimitExceededException ex = new RateLimitExceededException(45L);

        assertThat(ex.getRetryAfterSeconds()).isEqualTo(45L);

        ResponseEntity<ErrorResponse> response = handler.handleRateLimitExceeded(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("45");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(429);
        assertThat(response.getBody().message()).isEqualTo("Rate limit exceeded. Try again in 45 seconds.");
    }

    @Test
    void handleAuthenticationException_withBadCredentials_returnsInvalidEmailOrPassword() {
        BadCredentialsException ex = new BadCredentialsException("Bad credentials");

        ResponseEntity<ErrorResponse> response = handler.handleAuthenticationException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(401);
        assertThat(response.getBody().message()).isEqualTo("Invalid email or password");
    }

    @Test
    void handleAuthenticationException_withOtherAuthException_returnsAuthenticationRequired() {
        InsufficientAuthenticationException ex = new InsufficientAuthenticationException("Missing token");

        ResponseEntity<ErrorResponse> response = handler.handleAuthenticationException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(401);
        assertThat(response.getBody().message()).isEqualTo("Authentication is required");
    }

    @Test
    void handleAccessDenied_returnsForbidden() {
        AccessDeniedException ex = new AccessDeniedException("Access is denied");

        ResponseEntity<ErrorResponse> response = handler.handleAccessDenied(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(403);
        assertThat(response.getBody().message()).isEqualTo("You do not have permission to perform this action");
    }

    @Test
    void handleUnexpected_returnsInternalServerError() {
        Exception ex = new RuntimeException("Database connection lost");

        ResponseEntity<ErrorResponse> response = handler.handleUnexpected(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(500);
        assertThat(response.getBody().message()).isEqualTo("Unexpected error occurred");
    }
}
