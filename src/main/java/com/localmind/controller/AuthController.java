package com.localmind.controller;

import java.util.Map;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class AuthController {

    @GetMapping("/login")
    String login() {
        return "login";
    }

    @GetMapping("/api/auth/me")
    @ResponseBody
    Map<String, String> currentUser(Authentication authentication, CsrfToken csrfToken) {
        boolean anonymous = authentication == null || authentication instanceof AnonymousAuthenticationToken;
        boolean admin = !anonymous && authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN"));
        return Map.of(
                "username", anonymous ? "\u8bbf\u5ba2" : authentication.getName(),
                "role", anonymous ? "ANONYMOUS" : admin ? "ADMIN" : "USER",
                "csrfHeader", csrfToken.getHeaderName(),
                "csrfToken", csrfToken.getToken());
    }
}