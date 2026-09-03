package com.localmind.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.localmind.dao.entity.AppUser;
import com.localmind.dao.repository.AppUserRepository;
import com.localmind.dto.UserCreateRequest;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.crypto.password.PasswordEncoder;

class UserServiceTest {

    @Test
    void translatesConcurrentDuplicateUsernameToBusinessError() {
        AppUserRepository repository = org.mockito.Mockito.mock(AppUserRepository.class);
        PasswordEncoder encoder = org.mockito.Mockito.mock(PasswordEncoder.class);
        SessionRegistry sessions = org.mockito.Mockito.mock(SessionRegistry.class);
        UserService service = new UserService(repository, encoder, sessions);

        when(repository.existsByUsername("duplicate")).thenReturn(false);
        when(encoder.encode("123456")).thenReturn("encoded");
        when(repository.saveAndFlush(any(AppUser.class)))
                .thenThrow(new DataIntegrityViolationException("database constraint details"));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.create(new UserCreateRequest("duplicate", "123456", "USER")));

        assertEquals("用户名已存在", exception.getMessage());
    }
}