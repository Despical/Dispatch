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

import dev.despical.dispatch.config.DispatchProperties;
import dev.despical.dispatch.dto.security.AuthDtos.*;
import dev.despical.dispatch.exception.ApiException;
import dev.despical.dispatch.security.AccessTokenFilter;
import dev.despical.dispatch.security.DispatchPrincipal;
import dev.despical.dispatch.security.RateLimitService;
import dev.despical.dispatch.service.security.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;

/**
 * @author Despical
 * <p>
 * Created at 21.09.2026
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    public static final String BOOTSTRAP_COOKIE = "DISPATCH_BOOTSTRAP";

    private final AuthService authService;
    private final RateLimitService rateLimits;
    private final DispatchProperties properties;

    @GetMapping("/csrf")
    Map<String, String> csrf(CsrfToken token) {
        return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
    }

    @GetMapping("/bootstrap-status")
    Map<String, Boolean> bootstrapStatus() {
        return Map.of("available", authService.bootstrapAvailable());
    }

    @PostMapping("/bootstrap")
    BootstrapResponse bootstrap(
        @Valid @RequestBody BootstrapRequest request,
        HttpServletRequest servletRequest,
        HttpServletResponse response
    ) {
        enforce(servletRequest, "bootstrap", 5, 3600);

        String bootstrapToken = AccessTokenFilter.cookie(servletRequest, BOOTSTRAP_COOKIE);
        if (bootstrapToken == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Open the bootstrap page locally on the server before creating the first administrator.");
        }

        BootstrapResponse result = authService.bootstrap(request, bootstrapToken, servletRequest.getRemoteAddr());
        clearCookie(response);
        return result;
    }

    @PostMapping("/login")
    ChallengeResponse login(
        @Valid @RequestBody LoginRequest request, HttpServletRequest servletRequest) {
        enforce(servletRequest, "login", 30, 300);
        String email = request.email().trim().toLowerCase(Locale.ROOT);

        if (!rateLimits.allowLogin(email)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many attempts. Try again later.");
        }

        ChallengeResponse challenge = authService.login(email, request.password(), servletRequest.getRemoteAddr());
        rateLimits.loginSucceeded(email);
        return challenge;
    }

    @PostMapping("/verify")
    CompleteAuthResponse verify(
        @Valid @RequestBody TotpRequest request,
        HttpServletRequest servletRequest,
        HttpServletResponse response
    ) {
        enforce(servletRequest, "2fa", 10, 300);

        var result = authService.completeChallenge(
            request.challengeId(),
            request.code(),
            servletRequest.getHeader("User-Agent"),
            servletRequest.getRemoteAddr(),
            request.newPassword());
        writeCookies(response, result);
        return result.response();
    }

    @PostMapping("/recover-authenticator")
    ChallengeResponse recoverAuthenticator(
        @Valid @RequestBody AuthenticatorRecoveryRequest request,
        HttpServletRequest servletRequest
    ) {
        enforce(servletRequest, "2fa-recovery", 5, 3600);

        return authService.recoverAuthenticator(request.challengeId(), request.recoveryCode(), servletRequest.getRemoteAddr());
    }

    @PostMapping("/recover-password")
    Map<String, Boolean> recoverPassword(
        @Valid @RequestBody PasswordRecoveryRequest request,
        HttpServletRequest servletRequest
    ) {
        enforce(servletRequest, "password-recovery", 5, 3600);
        String email = request.email().trim().toLowerCase(Locale.ROOT);

        if (!rateLimits.allow("password-recovery-email:" + email, 5, 3600)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many attempts. Try again later.");
        }

        authService.recoverPassword(
            email,
            request.authenticatorCode(),
            request.recoveryCode(),
            request.newPassword(),
            servletRequest.getRemoteAddr());
        rateLimits.loginSucceeded(email);
        return Map.of("reset", true);
    }

    @PostMapping("/refresh")
    CompleteAuthResponse refresh(HttpServletRequest request, HttpServletResponse response) {
        enforce(request, "refresh", 30, 60);

        String token = AccessTokenFilter.cookie(request, AccessTokenFilter.REFRESH_COOKIE);
        if (token == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Refresh cookie is missing.");
        }

        var result = authService.refresh(token, request.getHeader("User-Agent"), request.getRemoteAddr());
        writeCookies(response, result);
        return result.response();
    }

    @PostMapping("/reauthenticate")
    Map<String, Boolean> reauthenticate(
        @AuthenticationPrincipal DispatchPrincipal principal,
        @Valid @RequestBody ReauthenticateRequest request,
        HttpServletRequest servletRequest
    ) {
        if (principal == null) throw new ApiException(HttpStatus.UNAUTHORIZED, "Sign in first.");
        enforce(servletRequest, "reauth", 6, 300);

        authService.reauthenticate(
            principal.adminId(),
            principal.sessionId(),
            request.password(),
            request.code(),
            servletRequest.getRemoteAddr());
        return Map.of("recentlyAuthenticated", true);
    }

    @PostMapping("/logout")
    void logout(
        @AuthenticationPrincipal DispatchPrincipal principal,
        @RequestParam(defaultValue = "false") boolean allDevices,
        HttpServletResponse response,
        HttpServletRequest request
    ) {
        if (principal != null) {
            authService.logout(principal.sessionId(), allDevices, request.getRemoteAddr());
        }

        clearCookies(response);
    }

    @GetMapping("/me")
    ProfileView me(@AuthenticationPrincipal DispatchPrincipal principal) {
        if (principal == null) throw new ApiException(HttpStatus.UNAUTHORIZED, "Not signed in.");
        return authService.profile(principal.adminId());
    }

    @PatchMapping("/profile")
    ProfileView updateProfile(
        @AuthenticationPrincipal DispatchPrincipal principal,
        @Valid @RequestBody ProfileUpdateRequest request,
        HttpServletRequest servletRequest
    ) {
        if (principal == null) throw new ApiException(HttpStatus.UNAUTHORIZED, "Not signed in.");
        return authService.updateProfile(principal.adminId(), request, servletRequest.getRemoteAddr());
    }

    private void enforce(HttpServletRequest request, String action, int capacity, long seconds) {
        if (!rateLimits.allow(action + ":" + request.getRemoteAddr(), capacity, seconds)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many attempts. Try again later.");
        }
    }

    private void writeCookies(
        HttpServletResponse response, AuthService.AuthenticationResult result) {
        Duration refreshAge = Duration.between(Instant.now(), result.session().getAbsoluteExpiresAt());

        addCookie(
            response,
            AccessTokenFilter.ACCESS_COOKIE,
            result.accessToken(),
            Duration.ofMinutes(properties.security().accessMinutes()));
        addCookie(response, AccessTokenFilter.REFRESH_COOKIE, result.refreshToken(), refreshAge);
    }

    private void addCookie(
        HttpServletResponse response, String name, String value, Duration maxAge) {
        ResponseCookie cookie =
            ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(properties.security().cookieSecure())
                .sameSite(properties.security().cookieSameSite())
                .path("/")
                .maxAge(maxAge.isNegative() ? Duration.ZERO : maxAge)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void clearCookies(HttpServletResponse response) {
        addCookie(response, AccessTokenFilter.ACCESS_COOKIE, "", Duration.ZERO);
        addCookie(response, AccessTokenFilter.REFRESH_COOKIE, "", Duration.ZERO);
    }

    private void clearCookie(HttpServletResponse response) {
        ResponseCookie cookie =
            ResponseCookie.from(AuthController.BOOTSTRAP_COOKIE, "")
                .httpOnly(true)
                .secure(properties.security().cookieSecure())
                .sameSite(properties.security().cookieSameSite())
                .path("/api/auth/bootstrap")
                .maxAge(Duration.ZERO)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
