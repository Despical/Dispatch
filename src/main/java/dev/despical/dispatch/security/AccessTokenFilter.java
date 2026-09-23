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

import dev.despical.dispatch.entity.security.AuthSession;
import dev.despical.dispatch.repository.security.AuthSessionRepository;

import io.jsonwebtoken.Claims;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import lombok.RequiredArgsConstructor;

import org.jspecify.annotations.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * @author Despical
 * <p>
 * Created at 21.09.2026
 */
@Component
@RequiredArgsConstructor
public class AccessTokenFilter extends OncePerRequestFilter {

    public static final String ACCESS_COOKIE = "DISPATCH_ACCESS";
    public static final String REFRESH_COOKIE = "DISPATCH_REFRESH";

    private final JwtService jwtService;
    private final AuthSessionRepository sessions;

    public static String cookie(HttpServletRequest request, String name) {
        if (request.getCookies() == null) return null;

        for (Cookie cookie : request.getCookies()) {
            if (name.equals(cookie.getName())) return cookie.getValue();
        }

        return null;
    }

    @Override
    protected void doFilterInternal(
        @NonNull HttpServletRequest request,
        @NonNull HttpServletResponse response,
        @NonNull FilterChain chain
    ) throws ServletException, IOException {
        String token = cookie(request, ACCESS_COOKIE);

        if (token != null) {
            try {
                Claims claims = jwtService.parseAccessToken(token);
                UUID sessionId = UUID.fromString(claims.getId());
                AuthSession session = sessions.findByPublicId(sessionId).orElse(null);

                if (session != null
                    && session.isActiveAt(Instant.now())
                    && session.getAdminUser().isEnabled()
                    && session.getAdminUser().isTotpEnabled()
                    && !session.getAdminUser().isPasswordChangeRequired()
                    && claims.getSubject().equals(session.getAdminUser().getId().toString())
                ) {
                    DispatchPrincipal principal = new DispatchPrincipal(
                        session.getAdminUser().getId(),
                        session.getAdminUser().getEmail(),
                        session.getAdminUser().getDisplayName(),
                        sessionId);
                    var authentication = UsernamePasswordAuthenticationToken.authenticated(
                        principal,
                        token,
                        List.of(new SimpleGrantedAuthority("ROLE_" + session.getAdminUser().getRole().name())));

                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            } catch (RuntimeException _) {
                SecurityContextHolder.clearContext();
            }
        }

        chain.doFilter(request, response);
    }
}
