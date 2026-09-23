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

import dev.despical.dispatch.config.DispatchProperties;
import dev.despical.dispatch.controller.security.AuthController;
import dev.despical.dispatch.security.AccessTokenFilter;
import dev.despical.dispatch.security.DispatchPrincipal;
import dev.despical.dispatch.service.security.AuthService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.server.ResponseStatusException;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;

/**
 * @author Despical
 * <p>
 * Created at 21.09.2026
 */
@Controller
@RequiredArgsConstructor
public class PageController {

    private final AuthService authService;
    private final DispatchProperties properties;

    @GetMapping("/")
    String root() {
        return "home";
    }

    @ModelAttribute
    void publicProfile(
        Model model,
        @AuthenticationPrincipal DispatchPrincipal principal,
        Authentication authentication,
        HttpServletRequest request
    ) {
        boolean signedIn = principal != null;
        model.addAttribute("publicSignedIn", signedIn);
        model.addAttribute(
            "publicAuthPending",
            !signedIn
                && AccessTokenFilter.cookie(request, AccessTokenFilter.REFRESH_COOKIE)
                != null);

        if (signedIn) {
            boolean administrator = authentication != null
                && authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));

            model.addAttribute("publicName", principal.displayName());
            model.addAttribute("publicInitials", initials(principal.displayName()));
            model.addAttribute("publicRoleLabel", administrator ? "Administrator" : "User");
            model.addAttribute("publicAdminId", principal.adminId());
        }
    }

    @GetMapping("/features")
    String features() {
        return "features";
    }

    @GetMapping("/security")
    String security() {
        return "security";
    }

    @GetMapping("/contact")
    String contact() {
        return "contact";
    }

    @GetMapping("/admin/status")
    String status() {
        return "status";
    }

    @GetMapping("/admin/users")
    String adminUsers() {
        return "admin-users";
    }

    @GetMapping("/admin")
    String admin() {
        return "redirect:/admin/status";
    }

    @GetMapping("/privacy-policy")
    String privacyPolicy() {
        return "privacy-policy";
    }

    @GetMapping("/terms-of-service")
    String termsOfService() {
        return "terms-of-service";
    }

    @GetMapping("/login")
    String login(@AuthenticationPrincipal DispatchPrincipal principal) {
        if (principal != null) return "redirect:/mail";
        return authService.bootstrapAvailable() ? "redirect:/bootstrap" : "login";
    }

    @GetMapping("/bootstrap")
    String bootstrap(
        @AuthenticationPrincipal DispatchPrincipal principal,
        HttpServletRequest request,
        HttpServletResponse response
    ) {
        if (principal != null) return "redirect:/mail";
        if (!authService.bootstrapAvailable()) return "redirect:/login";

        if (!isLoopback(request.getRemoteAddr())) {
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Bootstrap setup is available only from the server itself. Use an SSH tunnel"
                    + " for a remote server.");
        }

        ResponseCookie cookie =
            ResponseCookie.from(
                    AuthController.BOOTSTRAP_COOKIE,
                    properties.security().bootstrapToken())
                .httpOnly(true)
                .secure(properties.security().cookieSecure())
                .sameSite(properties.security().cookieSameSite())
                .path("/api/auth/bootstrap")
                .maxAge(Duration.ofMinutes(10))
                .build();

        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        return "bootstrap";
    }

    @GetMapping({
        "/mail",
        "/mail/trash",
        "/mail/drafts",
        "/mail/contacts",
        "/mail/sent",
        "/mail/accounts/{accountId:\\d+}"
    })
    String mail(
        @AuthenticationPrincipal DispatchPrincipal principal,
        Model model,
        Authentication authentication
    ) {
        boolean administrator =
            authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));

        model.addAttribute("isAdministrator", administrator);
        model.addAttribute("userRoleLabel", administrator ? "Administrator" : "User");
        model.addAttribute("adminId", principal.adminId());
        model.addAttribute("adminEmail", principal.email());
        model.addAttribute("adminName", principal.displayName());
        model.addAttribute("adminInitials", initials(principal.displayName()));
        return "mail";
    }

    private String initials(String name) {
        String[] parts = name == null ? new String[0] : name.trim().split("\\s+");
        if (parts.length == 0 || parts[0].isBlank()) return "A";

        String value = parts[0].substring(0, 1);
        if (parts.length > 1) value += parts[parts.length - 1].substring(0, 1);

        return value.toUpperCase(java.util.Locale.ROOT);
    }

    private boolean isLoopback(String address) {
        try {
            return InetAddress.getByName(address).isLoopbackAddress();
        } catch (UnknownHostException exception) {
            return false;
        }
    }
}
