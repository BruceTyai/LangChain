package com.localmind.config;

import com.localmind.service.AnonymousAccessService;
import com.localmind.dao.entity.AppUser;
import com.localmind.dao.repository.AppUserRepository;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

@Configuration
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserDetailsService userDetailsService(AppUserRepository repository) {
        return username -> {
            AppUser user = repository.findByUsername(username)
                    .orElseThrow(() -> new UsernameNotFoundException("用户不存在"));
            return User.withUsername(user.getUsername())
                    .password(user.getPassword())
                    .roles(user.getRole().name())
                    .disabled(!user.isEnabled())
                    .build();
        };
    }

    @Bean
    ApplicationRunner initializeAdmin(AppUserRepository repository, PasswordEncoder encoder) {
        return args -> {
            if (!repository.existsByUsername("admin")) {
                AppUser user = new AppUser();
                user.setUsername("admin");
                user.setPassword(encoder.encode("123456"));
                user.setRole(AppUser.Role.ADMIN);
                repository.save(user);
            }
        };
    }

    @Bean
    SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    @Bean
    ServletListenerRegistrationBean<HttpSessionEventPublisher> sessionEventPublisher() {
        return new ServletListenerRegistrationBean<>(new HttpSessionEventPublisher());
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, SessionRegistry sessions,
            AnonymousAccessService anonymousAccess) throws Exception {
        http.authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/login", "/login.css", "/error").permitAll()
                        .requestMatchers("/api/documents/**", "/api/users/**").hasRole("ADMIN")
                        .requestMatchers("/", "/index.html", "/app.js", "/style.css", "/favicon.ico",
                                "/api/chat/**", "/api/auth/me")
                        .access((authentication, context) -> {
                            var current = authentication.get();
                            boolean signedIn = current != null && current.isAuthenticated()
                                    && !(current instanceof AnonymousAuthenticationToken);
                            return new AuthorizationDecision(signedIn || anonymousAccess.isAllowed());
                        })
                        .anyRequest().authenticated())
                .formLogin(form -> form
                        .loginPage("/login")
                        .defaultSuccessUrl("/", true)
                        .failureUrl("/login?error")
                        .permitAll())
                .logout(logout -> logout.logoutSuccessUrl("/login?logout").permitAll())
                .sessionManagement(session -> session
                        .maximumSessions(-1)
                        .sessionRegistry(sessions)
                        .expiredSessionStrategy(event -> {
                            String contextPath = event.getRequest().getContextPath();
                            if (event.getRequest().getRequestURI().startsWith(contextPath + "/api/")) {
                                event.getResponse().setStatus(HttpStatus.UNAUTHORIZED.value());
                            } else {
                                event.getResponse().sendRedirect(contextPath + "/login?expired");
                            }
                        }))
                .exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                        new AntPathRequestMatcher("/api/**")));
        return http.build();
    }
}