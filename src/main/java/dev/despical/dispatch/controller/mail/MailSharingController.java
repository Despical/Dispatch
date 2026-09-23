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

import dev.despical.dispatch.dto.mail.MailDtos.FlagRequest;
import dev.despical.dispatch.security.DispatchPrincipal;
import dev.despical.dispatch.service.mail.MailSharingService;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * @author Despical
 * <p>
 * Created at 22.09.2026
 */
@RestController
@RequestMapping("/api/mail/sharing")
@RequiredArgsConstructor
public class MailSharingController {

    private final MailSharingService sharing;

    @GetMapping
    MailSharingService.Overview list(@AuthenticationPrincipal DispatchPrincipal principal) {
        return sharing.list(principal.adminId());
    }

    @PostMapping
    void add(
        @AuthenticationPrincipal DispatchPrincipal principal,
        @Valid @RequestBody MailSharingService.Request request
    ) {
        sharing.add(principal.adminId(), request);
    }

    @PutMapping("/{id}")
    void update(
        @AuthenticationPrincipal DispatchPrincipal principal,
        @PathVariable Long id,
        @Valid @RequestBody MailSharingService.Permissions request
    ) {
        sharing.update(principal.adminId(), id, request);
    }

    @DeleteMapping("/{id}")
    void remove(@AuthenticationPrincipal DispatchPrincipal principal, @PathVariable Long id) {
        sharing.remove(principal.adminId(), id);
    }

    @PutMapping("/{id}/visibility")
    void visibility(
        @AuthenticationPrincipal DispatchPrincipal principal,
        @PathVariable Long id,
        @Valid @RequestBody FlagRequest request
    ) {
        sharing.hide(principal.adminId(), id, !request.value());
    }
}
