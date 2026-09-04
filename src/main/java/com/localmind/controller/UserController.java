package com.localmind.controller;

import com.localmind.dto.*;
import com.localmind.service.AnonymousAccessService;
import com.localmind.service.UserService;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
public class UserController {
    private final UserService service;
    private final AnonymousAccessService anonymousAccess;

    public UserController(UserService s, AnonymousAccessService a) {
        service = s;
        anonymousAccess = a;
    }

    @GetMapping
    public List<UserResponse> list() {
        return service.list();
    }

    @GetMapping("/anonymous-access")
    public AnonymousAccessSetting anonymousAccess() {
        return new AnonymousAccessSetting(anonymousAccess.isAllowed());
    }

    @PutMapping("/anonymous-access")
    public AnonymousAccessSetting anonymousAccess(@Valid @RequestBody AnonymousAccessSetting q) {
        return new AnonymousAccessSetting(anonymousAccess.setAllowed(q.allowed()));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse create(@Valid @RequestBody UserCreateRequest q) {
        return service.create(q);
    }

    @PutMapping("/{id}")
    public UserResponse update(@PathVariable long id, @Valid @RequestBody UserUpdateRequest q, Principal p) {
        return service.update(id, q, p.getName());
    }

    @PutMapping("/{id}/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reset(@PathVariable long id, @Valid @RequestBody PasswordResetRequest q) {
        service.reset(id, q);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id, Principal p) {
        service.delete(id, p.getName());
    }
}
