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
package dev.despical.dispatch.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import dev.despical.dispatch.entity.security.AdminUser;
import dev.despical.dispatch.entity.security.AuthSession;
import dev.despical.dispatch.repository.security.AuthSessionRepository;

import io.jsonwebtoken.Claims;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * @author Despical
 * <p>
 * Created at 22.09.2026
 */
class EnrollmentAccessTest {
    @Test
    void authorityAlwaysUsesCurrentStoredRole() throws Exception {
        var jwt = mock(JwtService.class);
        var sessions = mock(AuthSessionRepository.class);
        var claims = mock(Claims.class);
        UUID id = UUID.randomUUID();
        when(jwt.parseAccessToken("token")).thenReturn(claims);
        when(claims.getId()).thenReturn(id.toString());
        when(claims.getSubject()).thenReturn("42");
        var user = new AdminUser();
        user.setId(42L);
        user.setTotpEnabled(true);
        var session = new AuthSession();
        session.setAdminUser(user);
        session.setPublicId(id);
        session.setAbsoluteExpiresAt(Instant.now().plusSeconds(60));
        when(sessions.findByPublicId(id)).thenReturn(Optional.of(session));
        var filter = new AccessTokenFilter(jwt, sessions);
        try {
            for (var role : dev.despical.dispatch.entity.security.UserRole.values()) {
                SecurityContextHolder.clearContext();
                user.setRole(role);
                var request = new MockHttpServletRequest("GET", "/api/mail/messages");
                request.setCookies(new Cookie(AccessTokenFilter.ACCESS_COOKIE, "token"));
                filter.doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));
                assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                    .extracting(
                        org.springframework.security.core.GrantedAuthority::getAuthority)
                    .containsExactly("ROLE_" + role.name());
            }
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void accessTokenCannotBypassIncompleteEnrollmentOrPasswordChange() throws Exception {
        var jwt = mock(JwtService.class);
        var sessions = mock(AuthSessionRepository.class);
        var claims = mock(Claims.class);
        UUID id = UUID.randomUUID();
        when(jwt.parseAccessToken("token")).thenReturn(claims);
        when(claims.getId()).thenReturn(id.toString());
        when(claims.getSubject()).thenReturn("42");
        var user = new AdminUser();
        user.setId(42L);
        var session = new AuthSession();
        session.setAdminUser(user);
        session.setAbsoluteExpiresAt(Instant.now().plusSeconds(60));
        when(sessions.findByPublicId(id)).thenReturn(Optional.of(session));
        var filter = new AccessTokenFilter(jwt, sessions);
        try {
            for (int phase = 0; phase < 3; phase++) {
                SecurityContextHolder.clearContext();
                user.setTotpEnabled(phase > 0);
                user.setPasswordChangeRequired(phase < 2);
                var request = new MockHttpServletRequest("GET", "/api/mail/messages");
                request.setCookies(new Cookie(AccessTokenFilter.ACCESS_COOKIE, "token"));
                filter.doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));
                assertThat(SecurityContextHolder.getContext().getAuthentication() != null)
                    .isEqualTo(phase == 2);
            }
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
