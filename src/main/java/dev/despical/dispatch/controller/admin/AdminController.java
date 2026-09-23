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
package dev.despical.dispatch.controller.admin;

import dev.despical.dispatch.dto.security.AuthDtos.AdminCreateRequest;
import dev.despical.dispatch.dto.security.AuthDtos.AdminView;
import dev.despical.dispatch.security.DispatchPrincipal;
import dev.despical.dispatch.service.security.AdminLifecycleService;
import dev.despical.dispatch.service.security.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * @author Despical
 * <p>
 * Created at 21.09.2026
 */
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminController {

    private final AuthService authService;
    private final AdminLifecycleService lifecycle;

    @PostMapping("/{id}/enable")
    void enable(
        @PathVariable Long id,
        @AuthenticationPrincipal DispatchPrincipal principal,
        HttpServletRequest request
    ) {
        authService.requireActiveSession(principal.sessionId());
        lifecycle.enable(id, principal.adminId(), request.getRemoteAddr());
    }

    @DeleteMapping("/{id}/permanent")
    void delete(
        @PathVariable Long id,
        @AuthenticationPrincipal DispatchPrincipal principal,
        HttpServletRequest request
    ) {
        authService.requireActiveSession(principal.sessionId());
        lifecycle.delete(id, principal.adminId(), request.getRemoteAddr());
    }

    @PostMapping
    AdminView create(
        @AuthenticationPrincipal DispatchPrincipal principal,
        @Valid @RequestBody AdminCreateRequest request,
        HttpServletRequest servletRequest
    ) {
        authService.requireActiveSession(principal.sessionId());
        return authService.createAdmin(request, servletRequest.getRemoteAddr());
    }

    @GetMapping
    List<AdminView> list() {
        return authService.listAdmins();
    }

    @DeleteMapping("/{id}")
    void disable(
        @PathVariable Long id,
        @AuthenticationPrincipal DispatchPrincipal principal,
        HttpServletRequest servletRequest
    ) {
        authService.requireActiveSession(principal.sessionId());
        authService.disableAdmin(id, principal.adminId(), servletRequest.getRemoteAddr());
    }
}
