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
package dev.despical.dispatch.controller.common;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import dev.despical.dispatch.config.SecurityConfig;
import dev.despical.dispatch.repository.security.AuthSessionRepository;
import dev.despical.dispatch.security.AccessTokenFilter;
import dev.despical.dispatch.security.DispatchPrincipal;
import dev.despical.dispatch.security.JwtService;
import dev.despical.dispatch.security.PrometheusTokenFilter;
import dev.despical.dispatch.service.security.AuthService;
import dev.despical.dispatch.service.system.MonitoringService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

/**
 * @author Despical
 * <p>
 * Created at 21.09.2026
 */
@WebMvcTest(PageController.class)
@Import({SecurityConfig.class, AccessTokenFilter.class, PrometheusTokenFilter.class})
@ActiveProfiles("test")
@TestPropertySource(properties = "dispatch.security.bootstrap-trusted-address=172.27.0.1")
class PageControllerSecurityTest {

    @Autowired
    MockMvc mvc;
    @MockitoBean
    AuthService authService;
    @MockitoBean
    JwtService jwtService;
    @MockitoBean
    AuthSessionRepository sessions;
    @MockitoBean
    MonitoringService monitoring;

    @Test
    void publicPagesRenderWithoutAuthentication() throws Exception {
        mvc.perform(get("/"))
            .andExpect(status().isOk())
            .andExpect(view().name("home"))
            .andExpect(content().string(containsString("name=\"description\"")))
            .andExpect(content().string(containsString("property=\"og:title\" content=\"Dispatch\"")))
            .andExpect(content().string(containsString("property=\"og:image\" content=\"https://mail.despical.dev/images/dispatch-social.png\"")))
            .andExpect(content().string(containsString("content=\"#8b5cf6\" name=\"theme-color\"")))
            .andExpect(content().string(containsString("rel=\"canonical\"")))
            .andExpect(content().string(containsString("src=\"/analytics.js\"")))
            .andExpect(content().string(containsString("src=\"/images/dispatch-live.png\"")))
            .andExpect(content().string(containsString("href=\"/features\"")))
            .andExpect(content().string(containsString("href=\"/security\"")))
            .andExpect(content().string(containsString("href=\"/contact\"")));
        mvc.perform(get("/privacy-policy"))
            .andExpect(status().isOk())
            .andExpect(view().name("privacy-policy"))
            .andExpect(content().string(containsString("Privacy policy")))
            .andExpect(content().string(containsString("mailto:contact@despical.dev")));
        mvc.perform(get("/terms-of-service"))
            .andExpect(status().isOk())
            .andExpect(view().name("terms-of-service"))
            .andExpect(content().string(containsString("Terms of service")))
            .andExpect(content().string(containsString("mailto:contact@despical.dev")));
        mvc.perform(get("/security"))
            .andExpect(status().isOk())
            .andExpect(view().name("security"))
            .andExpect(content().string(containsString("Report a security issue")));
        mvc.perform(get("/features"))
            .andExpect(status().isOk())
            .andExpect(view().name("features"))
            .andExpect(content().string(containsString("Unified inbox")));
        mvc.perform(get("/contact"))
            .andExpect(status().isOk())
            .andExpect(view().name("contact"))
            .andExpect(content().string(containsString("mailto:contact@despical.dev")));
    }

    @Test
    void publicPagesDoNotMakeMailboxOrMailApiPublic() throws Exception {
        mvc.perform(get("/images/dispatch-live.png"))
            .andExpect(status().isOk())
            .andExpect(content().contentType("image/png"));
        mvc.perform(get("/images/dispatch-social.png"))
            .andExpect(status().isOk())
            .andExpect(content().contentType("image/png"));
        mvc.perform(get("/preferences-bootstrap.js"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("dispatch-theme")));
        mvc.perform(get("/analytics.js"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("G-27S55TMZXW")));
        mvc.perform(get("/robots.txt"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("Sitemap: https://mail.despical.dev/sitemap.xml")));
        mvc.perform(get("/sitemap.xml"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("https://mail.despical.dev/features")));
        mvc.perform(get("/llms.txt"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("# Dispatch")))
            .andExpect(content().string(containsString("[Features](https://mail.despical.dev/features)")));
        mvc.perform(get("/.well-known/ard.json"))
            .andExpect(status().isOk())
            .andExpect(content().json("{\"specVersion\":\"1.0\",\"entries\":[]}"));
        mvc.perform(get("/.well-known/ai-catalog.json"))
            .andExpect(status().isOk())
            .andExpect(content().json("{\"specVersion\":\"1.0\",\"entries\":[]}"));
        mvc.perform(get("/login"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("name=\"robots\" content=\"noindex, nofollow\"")))
            .andExpect(content().string(not(containsString("/analytics.js"))));
        mvc.perform(get("/mail"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/login"));
        mvc.perform(get("/api/mail/messages")).andExpect(status().isUnauthorized());
    }

    @Test
    void statusPanelIsAdministratorOnly() throws Exception {
        mvc.perform(get("/admin/status"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/login"));
        mvc.perform(get("/admin/users"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/login"));
        var principal =
            new DispatchPrincipal(1L, "user@example.test", "Regular User", UUID.randomUUID());
        var user =
            UsernamePasswordAuthenticationToken.authenticated(
                principal, null, List.of(() -> "ROLE_USER"));
        mvc.perform(get("/admin/status").with(authentication(user)))
            .andExpect(status().isForbidden());
        mvc.perform(get("/admin/users").with(authentication(user)))
            .andExpect(status().isForbidden());
        var admin =
            UsernamePasswordAuthenticationToken.authenticated(
                principal, null, List.of(() -> "ROLE_ADMIN"));
        when(monitoring.current())
            .thenReturn(
                new MonitoringService.Status(
                    "OPERATIONAL",
                    "UP",
                    "UP",
                    "UP",
                    List.of(),
                    java.time.Instant.now(),
                    new MonitoringService.Capacity(List.of(), "0.1", "1h"),
                    List.of()));
        mvc.perform(get("/admin/status").with(authentication(admin)))
            .andExpect(status().isOk())
            .andExpect(view().name("status"))
            .andExpect(content().string(containsString("Host capacity")));
        mvc.perform(get("/admin/users").with(authentication(admin)))
            .andExpect(status().isOk())
            .andExpect(view().name("admin-users"))
            .andExpect(content().string(containsString("Panel users")));
        mvc.perform(get("/admin").with(authentication(admin)))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/admin/status"));
    }

    @Test
    void signInPreservesFirstAdministratorSetupAndRemoteRestriction() throws Exception {
        when(authService.bootstrapAvailable()).thenReturn(true);
        mvc.perform(get("/login"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/bootstrap"));
        mvc.perform(
                get("/bootstrap")
                    .with(
                        request -> {
                            request.setRemoteAddr("203.0.113.20");
                            return request;
                        }))
            .andExpect(status().isForbidden());
        mvc.perform(get("/bootstrap").with(request -> {
            request.setRemoteAddr("172.27.0.1");
            return request;
        })).andExpect(status().isOk());
    }

    @Test
    void configuredInstallationShowsSignInAndSignedInUsersCanStillVisitHome() throws Exception {
        mvc.perform(get("/login")).andExpect(status().isOk()).andExpect(view().name("login"));

        var principal =
            new DispatchPrincipal(
                1L, "admin@example.test", "Test Administrator", UUID.randomUUID());
        var signedIn =
            UsernamePasswordAuthenticationToken.authenticated(
                principal, null, List.of(() -> "ROLE_ADMIN"));
        mvc.perform(get("/login").with(authentication(signedIn)))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/mail"));
        mvc.perform(get("/").with(authentication(signedIn)))
            .andExpect(status().isOk())
            .andExpect(view().name("home"));
    }

    @TestConfiguration
    @EnableWebSecurity
    static class TestSecurityConfiguration {
    }
}
