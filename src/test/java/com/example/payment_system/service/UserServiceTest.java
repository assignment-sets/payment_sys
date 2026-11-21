package com.example.payment_system.service;

import com.example.payment_system.dto.UserRequest;
import com.example.payment_system.dto.UserResponse;
import com.example.payment_system.entity.Role;
import com.example.payment_system.entity.User;
import com.example.payment_system.exception.DuplicateUserException;
import com.example.payment_system.exception.UserNotFoundException;
import com.example.payment_system.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private AccountService accountService;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private UserService userService;

    @Test
    void createUser_successWithExplicitRole_normalizesEmailEncodesPasswordAndCreatesAccount() {
        UserRequest request = new UserRequest("  Alice Smith  ", "  ALICE@Example.com ", "secret123", Role.ADMIN);
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("secret123")).thenReturn("encoded-pw");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(42L);
            return u;
        });

        UserResponse response = userService.createUser(request);

        assertThat(response.id()).isEqualTo(42L);
        assertThat(response.name()).isEqualTo("Alice Smith");
        assertThat(response.email()).isEqualTo("alice@example.com");
        assertThat(response.role()).isEqualTo(Role.ADMIN);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getPasswordHash()).isEqualTo("encoded-pw");
        assertThat(userCaptor.getValue().getEmail()).isEqualTo("alice@example.com");
        assertThat(userCaptor.getValue().getName()).isEqualTo("Alice Smith");
        verify(accountService).createAccount(userCaptor.getValue());
    }

    @Test
    void createUser_whenRoleIsNull_defaultsToRoleUser() {
        UserRequest request = new UserRequest("Bob", "bob@example.com", "secret123", null);
        when(userRepository.findByEmail("bob@example.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("secret123")).thenReturn("encoded-pw");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(7L);
            return u;
        });

        UserResponse response = userService.createUser(request);

        assertThat(response.role()).isEqualTo(Role.USER);
    }

    @Test
    void createUser_duplicateEmail_throwsDuplicateUserExceptionWithoutSavingOrCreatingAccount() {
        UserRequest request = new UserRequest("Alice", "  ALICE@Example.com ", "secret123", Role.USER);
        User existing = User.builder().id(1L).email("alice@example.com").build();
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> userService.createUser(request))
                .isInstanceOf(DuplicateUserException.class);

        verify(userRepository, never()).save(any());
        verifyNoInteractions(accountService);
        verifyNoInteractions(passwordEncoder);
    }

    @Test
    void getUserById_whenFound_returnsUserResponse() {
        OffsetDateTime now = OffsetDateTime.now();
        User user = User.builder()
                .id(1L)
                .name("Alice")
                .email("alice@example.com")
                .passwordHash("hash")
                .role(Role.USER)
                .createdAt(now)
                .build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        UserResponse response = userService.getUserById(1L);

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.name()).isEqualTo("Alice");
        assertThat(response.email()).isEqualTo("alice@example.com");
        assertThat(response.role()).isEqualTo(Role.USER);
        assertThat(response.createdAt()).isEqualTo(now);
    }

    @Test
    void getUserById_whenNotFound_throwsUserNotFoundException() {
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getUserById(999L))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void getAllUsers_returnsMappedList() {
        User u1 = User.builder().id(1L).name("Alice").email("a@example.com").role(Role.USER).createdAt(OffsetDateTime.now()).build();
        User u2 = User.builder().id(2L).name("Admin").email("admin@example.com").role(Role.ADMIN).createdAt(OffsetDateTime.now()).build();
        when(userRepository.findAll()).thenReturn(List.of(u1, u2));

        List<UserResponse> users = userService.getAllUsers();

        assertThat(users).hasSize(2);
        assertThat(users).extracting(UserResponse::email).containsExactly("a@example.com", "admin@example.com");
        assertThat(users).extracting(UserResponse::role).containsExactly(Role.USER, Role.ADMIN);
    }

    @Test
    void getAllUsers_whenEmpty_returnsEmptyList() {
        when(userRepository.findAll()).thenReturn(List.of());

        assertThat(userService.getAllUsers()).isEmpty();
    }
}
