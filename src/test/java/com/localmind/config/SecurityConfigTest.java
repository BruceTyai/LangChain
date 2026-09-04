package com.localmind.config;

import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.localmind.controller.AuthController;
import com.localmind.controller.ChatController;
import com.localmind.controller.DocumentController;
import com.localmind.controller.UserController;
import com.localmind.dao.entity.AppUser;
import com.localmind.dao.repository.AppUserRepository;
import com.localmind.dto.ChatResponse;
import com.localmind.dto.DocumentPageResponse;
import com.localmind.service.AnonymousAccessService;
import com.localmind.service.ChatService;
import com.localmind.service.DocumentService;
import com.localmind.service.UserService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@WebMvcTest(controllers = {
        AuthController.class, ChatController.class, DocumentController.class, UserController.class
})
@Import(SecurityConfig.class)
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private SessionRegistry sessionRegistry;


    @MockitoBean
    private ChatService chatService;

    @MockitoBean
    private DocumentService documentService;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private AnonymousAccessService anonymousAccessService;

    @MockitoBean
    private AppUserRepository appUserRepository;

    @BeforeEach
    void configureUser() {
        AppUser user = new AppUser();
        user.setUsername("user");
        user.setPassword(passwordEncoder.encode("test-user-password"));
        user.setRole(AppUser.Role.USER);
        user.setEnabled(true);
        when(appUserRepository.findByUsername("user")).thenReturn(Optional.of(user));
    }

    @Test
    void authenticatesDatabaseUser() throws Exception {
        mockMvc.perform(post("/login")
                        .with(csrf())
                        .param("username", "user")
                        .param("password", "test-user-password"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));
    }

    @Test
    void rejectsUnauthenticatedApiRequests() throws Exception {
        mockMvc.perform(get("/api/documents"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "reader", roles = "USER")
    void normalUserCanAskQuestions() throws Exception {
        when(chatService.ask("测试问题")).thenReturn(new ChatResponse("测试回答", List.of()));

        mockMvc.perform(post("/api/chat")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"测试问题\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("测试回答"));
    }

    @Test
    @WithMockUser(roles = "USER")
    void normalUserCannotManageDocumentsOrUsers() throws Exception {
        mockMvc.perform(get("/api/documents")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/users")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void administratorCanManageDocumentsAndUsers() throws Exception {
        when(documentService.page(0, 10))
                .thenReturn(new DocumentPageResponse(List.of(), 0, 0, 0, 0));
        when(userService.list()).thenReturn(List.of());

        mockMvc.perform(get("/api/documents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/api/users")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "reader", roles = "USER")
    void currentUserEndpointReturnsRoleAndCsrfToken() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("reader"))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.csrfHeader").isNotEmpty())
                .andExpect(jsonPath("$.csrfToken").isNotEmpty());
    }

    @Test
    void expiredApiSessionReturnsUnauthorized() throws Exception {
        MvcResult login = mockMvc.perform(post("/login")
                        .with(csrf())
                        .param("username", "user")
                        .param("password", "test-user-password"))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);

        sessionRegistry.getAllPrincipals().forEach(principal ->
                sessionRegistry.getAllSessions(principal, false)
                        .forEach(sessionInformation -> sessionInformation.expireNow()));

        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isUnauthorized());
    }
    @Test
    void anonymousCannotChatWhenDisabled() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"anonymous question\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anonymousCanOnlyUseChatWhenEnabled() throws Exception {
        when(anonymousAccessService.isAllowed()).thenReturn(true);
        when(chatService.ask("anonymous question")).thenReturn(new ChatResponse("anonymous question", List.of()));

        mockMvc.perform(post("/api/chat")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"anonymous question\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("anonymous question"));

        mockMvc.perform(get("/api/users"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anonymousSessionReturnsAnonymousRoleWhenEnabled() throws Exception {
        when(anonymousAccessService.isAllowed()).thenReturn(true);

        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("\u8bbf\u5ba2"))
                .andExpect(jsonPath("$.role").value("ANONYMOUS"))
                .andExpect(jsonPath("$.csrfToken").isNotEmpty());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void administratorCanUpdateAnonymousAccessSetting() throws Exception {
        when(anonymousAccessService.setAllowed(true)).thenReturn(true);

        mockMvc.perform(put("/api/users/anonymous-access")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allowed\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(true));
    }
    @Test
    @WithMockUser(roles = "ADMIN")
    void rejectsAnonymousAccessSettingWithoutAllowedValue() throws Exception {
        mockMvc.perform(put("/api/users/anonymous-access")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(anonymousAccessService);
    }
}
