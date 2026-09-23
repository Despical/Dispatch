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

import dev.despical.dispatch.security.DispatchPrincipal;
import dev.despical.dispatch.service.security.SecurityActivityService;

import lombok.RequiredArgsConstructor;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author Despical
 * <p>
 * Created at 22.09.2026
 */
@RestController
@RequiredArgsConstructor
public class SecurityActivityController {
    private final SecurityActivityService service;

    @GetMapping("/api/security/activity")
    SecurityActivityService.Activity activity(
        @AuthenticationPrincipal DispatchPrincipal principal,
        @RequestParam(defaultValue = "0") int page
    ) {
        return service.activity(principal.adminId(), principal.sessionId(), page);
    }
}
