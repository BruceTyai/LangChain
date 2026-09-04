package com.localmind.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.localmind.dao.entity.AppUser;
import com.localmind.dao.repository.AppUserRepository;
import com.localmind.dto.UserCreateRequest;
import com.localmind.dto.UserUpdateRequest;
import java.util.Optional;
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

    @Test
    void currentAdminCannotDisableOrDeleteOwnAccount() {
        AppUserRepository repository = org.mockito.Mockito.mock(AppUserRepository.class);
        PasswordEncoder encoder = org.mockito.Mockito.mock(PasswordEncoder.class);
        SessionRegistry sessions = org.mockito.Mockito.mock(SessionRegistry.class);
        UserService service = new UserService(repository, encoder, sessions);
        AppUser admin = new AppUser();
        admin.setUsername("admin");
        admin.setRole(AppUser.Role.ADMIN);
        when(repository.findById(1L)).thenReturn(Optional.of(admin));

        IllegalArgumentException disableException = assertThrows(IllegalArgumentException.class,
                () -> service.update(1L, new UserUpdateRequest("ADMIN", false), "admin"));
        IllegalArgumentException deleteException = assertThrows(IllegalArgumentException.class,
                () -> service.delete(1L, "admin"));

        assertEquals("不能禁用当前账号或移除自己的管理员角色", disableException.getMessage());
        assertEquals("不能删除当前登录账号", deleteException.getMessage());
    }
}
