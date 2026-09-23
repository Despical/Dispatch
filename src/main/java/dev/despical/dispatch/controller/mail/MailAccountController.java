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

import dev.despical.dispatch.dto.mail.MailDtos.AccountOrderRequest;
import dev.despical.dispatch.dto.mail.MailDtos.AccountRequest;
import dev.despical.dispatch.dto.mail.MailDtos.AccountResponse;
import dev.despical.dispatch.exception.ApiException;
import dev.despical.dispatch.security.DispatchPrincipal;
import dev.despical.dispatch.security.RateLimitService;
import dev.despical.dispatch.service.mail.GoogleOAuthService;
import dev.despical.dispatch.service.mail.MailAccountService;
import dev.despical.dispatch.service.mail.MailSyncService;
import dev.despical.dispatch.service.security.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * @author Despical
 * <p>
 * Created at 21.09.2026
 */
@RestController
@RequestMapping("/api/mail/accounts")
@RequiredArgsConstructor
public class MailAccountController {

    private final MailAccountService accountService;
    private final MailSyncService syncService;
    private final AuthService authService;
    private final RateLimitService rateLimits;
    private final GoogleOAuthService googleOAuth;

    @GetMapping
    List<AccountResponse> list(@AuthenticationPrincipal DispatchPrincipal principal) {
        return accountService.list(principal.adminId());
    }

    @PostMapping
    AccountResponse create(
        @AuthenticationPrincipal DispatchPrincipal principal,
        @Valid @RequestBody AccountRequest request,
        HttpServletRequest servletRequest
    ) {
        critical(principal, servletRequest);
        return accountService.create(principal.adminId(), request);
    }

    @PutMapping("/order")
    void reorder(
        @AuthenticationPrincipal DispatchPrincipal principal,
        @Valid @RequestBody AccountOrderRequest request
    ) {
        authService.requireActiveSession(principal.sessionId());
        accountService.reorder(principal.adminId(), request.accountIds());
    }

    @PutMapping("/{id}")
    AccountResponse update(
        @PathVariable Long id,
        @AuthenticationPrincipal DispatchPrincipal principal,
        @Valid @RequestBody AccountRequest request,
        HttpServletRequest servletRequest
    ) {
        critical(principal, servletRequest);
        return accountService.update(principal.adminId(), id, request);
    }

    @DeleteMapping("/{id}")
    void remove(
        @PathVariable Long id,
        @AuthenticationPrincipal DispatchPrincipal principal,
        HttpServletRequest servletRequest
    ) {
        critical(principal, servletRequest);

        accountService.remove(principal.adminId(), id);
        googleOAuth.forget(id);
    }

    @PostMapping("/{id}/sync")
    void sync(
        @PathVariable Long id,
        @AuthenticationPrincipal DispatchPrincipal principal,
        HttpServletRequest servletRequest
    ) {
        if (!rateLimits.allow("sync:" + servletRequest.getRemoteAddr(), 10, 60)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many synchronization requests.");
        }

        accountService.requireOwned(principal.adminId(), id);
        syncService.syncAccountFully(id);
    }

    private void critical(DispatchPrincipal principal, HttpServletRequest request) {
        authService.requireActiveSession(principal.sessionId());

        if (!rateLimits.allow("account-test:" + request.getRemoteAddr(), 8, 300)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many connection attempts.");
        }
    }
}
