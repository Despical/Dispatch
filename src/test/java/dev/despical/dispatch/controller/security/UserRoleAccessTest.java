/*
 * Dispatch - A private webmail application.
 * Copyright (C) 2026 Berke Akçen
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package dev.despical.dispatch.controller.security;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.despical.dispatch.config.SecurityConfig;
import dev.despical.dispatch.controller.admin.AdminController;
import dev.despical.dispatch.controller.common.PageController;
import dev.despical.dispatch.entity.security.*;
import dev.despical.dispatch.repository.security.AuthSessionRepository;
import dev.despical.dispatch.security.*;
import dev.despical.dispatch.service.security.*;
import dev.despical.dispatch.service.system.MonitoringService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

/**
 * @author Despical
 * <p>
 * Created at 22.09.2026
 */
@WebMvcTest({AdminController.class, SecurityActivityController.class, PageController.class})
@Import({SecurityConfig.class, AccessTokenFilter.class, PrometheusTokenFilter.class})
@ActiveProfiles("test")
class UserRoleAccessTest {
    @Autowired
    MockMvc mvc;
    @MockitoBean
    AuthService auth;
    @MockitoBean
    AdminLifecycleService lifecycle;
    @MockitoBean
    SecurityActivityService activity;
    @MockitoBean
    JwtService jwt;
    @MockitoBean
    AuthSessionRepository sessions;
    @MockitoBean
    MonitoringService monitoring;

    private org.springframework.test.web.servlet.request.RequestPostProcessor signIn(
        UserRole role) {
        var principal =
            new DispatchPrincipal(42L, "user@example.test", "Test User", UUID.randomUUID());
        var authentication =
            org.springframework.security.authentication.UsernamePasswordAuthenticationToken
                .authenticated(
                    principal,
                    null,
                    java.util.List.of(
                        new org.springframework.security.core.authority
                            .SimpleGrantedAuthority("ROLE_" + role.name())));
        return org.springframework.security.test.web.servlet.request
            .SecurityMockMvcRequestPostProcessors.authentication(authentication);
    }

    @Test
    void normalUserCannotManageUsersEvenWhenRequestingAdminRole() throws Exception {
        var cookie = signIn(UserRole.USER);
        mvc.perform(get("/api/admin/users").with(cookie)).andExpect(status().isForbidden());
        mvc.perform(
                post("/api/admin/users")
                    .with(cookie)
                    .with(csrf())
                    .contentType("application/json")
                    .content(
                        "{\"displayName\":\"Admin\",\"email\":\"admin@example.test\",\"password\":\"temporary-password\",\"role\":\"ADMIN\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(delete("/api/admin/users/9").with(cookie).with(csrf()))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/users/9/enable").with(cookie).with(csrf()))
            .andExpect(status().isForbidden());
        mvc.perform(delete("/api/admin/users/9/permanent").with(cookie).with(csrf()))
            .andExpect(status().isForbidden());
        verifyNoInteractions(auth, lifecycle);
        mvc.perform(get("/api/security/activity").with(cookie)).andExpect(status().isOk());
        verify(activity).activity(eq(42L), any(UUID.class), eq(0));
    }

    @Test
    void profileMenuOnlyOffersUserManagementToAdministrators() throws Exception {
        mvc.perform(get("/mail").with(signIn(UserRole.USER)))
            .andExpect(status().isOk())
            .andExpect(content().string(not(containsString("<span>Admin panel</span>"))))
            .andExpect(content().string(not(containsString("data-system-status"))))
            .andExpect(content().string(containsString("<small>User</small>")));
        mvc.perform(get("/mail").with(signIn(UserRole.ADMIN)))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("<span>Admin panel</span>")))
            .andExpect(content().string(containsString("<small>Administrator</small>")));
    }

    @Test
    void bookmarkedMailViewsRenderTheProtectedWorkspace() throws Exception {
        for (String path :
            java.util.List.of(
                "/mail/trash?filter=unread",
                "/mail/drafts",
                "/mail/contacts",
                "/mail/sent",
                "/mail/accounts/7")) {
            mvc.perform(get(path)).andExpect(status().is3xxRedirection());
            mvc.perform(get(path).with(signIn(UserRole.USER)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-page=\"mail\"")));
        }
    }

    @Test
    void administratorCanListAndCreateUsersButRoleIsRequiredAndValidated() throws Exception {
        var cookie = signIn(UserRole.ADMIN);
        mvc.perform(get("/api/admin/users").with(cookie)).andExpect(status().isOk());
        for (String role : new String[]{"USER", "ADMIN"}) {
            mvc.perform(
                    post("/api/admin/users")
                        .with(cookie)
                        .with(csrf())
                        .contentType("application/json")
                        .content(
                            "{\"displayName\":\"Person\",\"email\":\"person@example.test\",\"password\":\"temporary-password\",\"role\":\""
                                + role
                                + "\"}"))
                .andExpect(status().isOk());
        }
        for (String role : new String[]{"null", "\"SUPERADMIN\""}) {
            mvc.perform(
                    post("/api/admin/users")
                        .with(cookie)
                        .with(csrf())
                        .contentType("application/json")
                        .content(
                            "{\"displayName\":\"Person\",\"email\":\"person@example.test\",\"password\":\"temporary-password\",\"role\":"
                                + role
                                + "}"))
                .andExpect(status().isBadRequest());
        }
        verify(auth, times(2)).createAdmin(any(), anyString());
    }

    @TestConfiguration
    @EnableWebSecurity
    static class TestSecurityConfiguration {
    }
}
