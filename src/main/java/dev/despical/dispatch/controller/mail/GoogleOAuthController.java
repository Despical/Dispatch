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
package dev.despical.dispatch.controller.mail;

import dev.despical.dispatch.security.DispatchPrincipal;
import dev.despical.dispatch.service.mail.GoogleOAuthService;
import dev.despical.dispatch.service.security.AuthService;

import jakarta.servlet.http.HttpServletResponse;

import lombok.RequiredArgsConstructor;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.io.IOException;
import java.util.Map;

/**
 * @author Despical
 * <p>
 * Created at 23.09.2026
 */
@Controller
@RequiredArgsConstructor
public class GoogleOAuthController {

    private final GoogleOAuthService google;
    private final AuthService auth;

    @GetMapping("/api/mail/accounts/google/config")
    @ResponseBody
    Map<String, Boolean> config() {
        return Map.of("available", google.configured());
    }

    @PostMapping("/api/mail/accounts/google/start")
    @ResponseBody
    Map<String, String> start(@AuthenticationPrincipal DispatchPrincipal principal) {
        auth.requireActiveSession(principal.sessionId());
        return Map.of("url", google.start(principal.adminId()));
    }

    @GetMapping("/oauth/google/callback")
    void callback(
        @RequestParam(required = false) String state,
        @RequestParam(required = false) String code,
        HttpServletResponse response
    ) throws IOException {
        try {
            Long accountId = google.complete(state, code);
            response.sendRedirect("/mail?google=connected&account=" + accountId);
        } catch (RuntimeException exception) {
            response.sendRedirect("/mail?google=error");
        }
    }
}
