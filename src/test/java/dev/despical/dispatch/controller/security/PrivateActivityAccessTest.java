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

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.despical.dispatch.config.SecurityConfig;
import dev.despical.dispatch.controller.mail.OutboundController;
import dev.despical.dispatch.repository.security.AuthSessionRepository;
import dev.despical.dispatch.security.*;
import dev.despical.dispatch.service.mail.OutboundService;
import dev.despical.dispatch.service.security.SecurityActivityService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

/**
 * @author Despical
 * <p>
 * Created at 22.09.2026
 */
@WebMvcTest({SecurityActivityController.class, OutboundController.class})
@Import({SecurityConfig.class, AccessTokenFilter.class, PrometheusTokenFilter.class})
@ActiveProfiles("test")
class PrivateActivityAccessTest {
    @Autowired
    MockMvc mvc;
    @MockitoBean
    SecurityActivityService activity;
    @MockitoBean
    OutboundService outbound;
    @MockitoBean
    RateLimitService rateLimits;
    @MockitoBean
    JwtService jwt;
    @MockitoBean
    AuthSessionRepository sessions;

    @Test
    void anonymousRequestsCannotReadActivityOrDrafts() throws Exception {
        for (String path :
            List.of(
                "/api/security/activity",
                "/api/mail/outbound/drafts",
                "/api/mail/outbound/drafts/count",
                "/api/mail/outbound/drafts/" + UUID.randomUUID())) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(activity, outbound);
    }

    @Test
    void ownerAlwaysComesFromTheSignedInPrincipal() throws Exception {
        UUID session = UUID.randomUUID(), draft = UUID.randomUUID();
        var principal = new DispatchPrincipal(42L, "owner@example.test", "Owner", session);
        var auth = new UsernamePasswordAuthenticationToken(principal, null, List.of());
        mvc.perform(get("/api/security/activity?ownerId=99&page=2").with(authentication(auth)))
            .andExpect(status().isOk());
        verify(activity).activity(42L, session, 2);
        mvc.perform(get("/api/mail/outbound/drafts/count?ownerId=99").with(authentication(auth)))
            .andExpect(status().isOk());
        verify(outbound).draftCount(42L);
        mvc.perform(
                get("/api/mail/outbound/drafts/" + draft + "?ownerId=99")
                    .with(authentication(auth)))
            .andExpect(status().isOk());
        verify(outbound).draft(42L, draft);
    }

    @TestConfiguration
    @EnableWebSecurity
    static class TestSecurityConfiguration {
    }
}
