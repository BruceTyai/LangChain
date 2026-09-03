package com.localmind.service;

import com.localmind.dao.entity.AppUser;
import com.localmind.dao.repository.AppUserRepository;
import com.localmind.dto.PasswordResetRequest;
import com.localmind.dto.UserCreateRequest;
import com.localmind.dto.UserResponse;
import com.localmind.dto.UserUpdateRequest;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private final AppUserRepository repository;
    private final PasswordEncoder encoder;
    private final SessionRegistry sessions;

    public UserService(AppUserRepository repository, PasswordEncoder encoder, SessionRegistry sessions) {
        this.repository = repository;
        this.encoder = encoder;
        this.sessions = sessions;
    }

    public List<UserResponse> list() {
        return repository.findAll().stream().map(UserResponse::from).toList();
    }

    @Transactional
    public UserResponse create(UserCreateRequest request) {
        if (repository.existsByUsername(request.username())) {
            throw new IllegalArgumentException("用户名已存在");
        }
        AppUser user = new AppUser();
        user.setUsername(request.username());
        user.setPassword(encoder.encode(request.password()));
        user.setRole(AppUser.Role.valueOf(request.role() == null ? "USER" : request.role()));
        try {
            return UserResponse.from(repository.saveAndFlush(user));
        } catch (DataIntegrityViolationException exception) {
            throw new IllegalArgumentException("用户名已存在", exception);
        }
    }

    @Transactional
    public UserResponse update(long id, UserUpdateRequest request, String currentUsername) {
        AppUser user = get(id);
        if (user.getUsername().equals(currentUsername)
                && (Boolean.FALSE.equals(request.enabled()) || "USER".equals(request.role()))) {
            throw new IllegalArgumentException("不能禁用当前账号或移除自己的管理员角色");
        }
        boolean authorizationChanged =
                request.role() != null && !user.getRole().name().equals(request.role())
                        || request.enabled() != null && user.isEnabled() != request.enabled();
        if (request.role() != null) {
            user.setRole(AppUser.Role.valueOf(request.role()));
        }
        if (request.enabled() != null) {
            user.setEnabled(request.enabled());
        }
        if (authorizationChanged) {
            expireSessions(user.getUsername());
        }
        return UserResponse.from(user);
    }

    @Transactional
    public void reset(long id, PasswordResetRequest request) {
        AppUser user = get(id);
        user.setPassword(encoder.encode(request.password()));
        expireSessions(user.getUsername());
    }

    @Transactional
    public void delete(long id, String currentUsername) {
        AppUser user = get(id);
        if (user.getUsername().equals(currentUsername)) {
            throw new IllegalArgumentException("不能删除当前登录账号");
        }
        expireSessions(user.getUsername());
        repository.delete(user);
    }

    private void expireSessions(String username) {
        sessions.getAllPrincipals().stream()
                .filter(principal -> username.equals(principal instanceof UserDetails details
                        ? details.getUsername()
                        : principal.toString()))
                .forEach(principal -> sessions.getAllSessions(principal, false)
                        .forEach(session -> session.expireNow()));
    }

    private AppUser get(long id) {
        return repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
    }
}