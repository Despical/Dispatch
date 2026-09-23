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

import dev.despical.dispatch.dto.mail.MailDtos.CannedResponseRequest;
import dev.despical.dispatch.dto.mail.MailDtos.CannedResponseView;
import dev.despical.dispatch.entity.mail.CannedResponse;
import dev.despical.dispatch.exception.ApiException;
import dev.despical.dispatch.repository.mail.CannedResponseRepository;
import dev.despical.dispatch.repository.security.AdminUserRepository;
import dev.despical.dispatch.security.DispatchPrincipal;
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
@RequestMapping("/api/mail/canned-responses")
@RequiredArgsConstructor
public class CannedResponseController {

    private final CannedResponseRepository repository;
    private final AdminUserRepository admins;

    @GetMapping
    List<CannedResponseView> list(@AuthenticationPrincipal DispatchPrincipal principal) {
        return repository.findAllByOwnerIdOrderByTitleAsc(principal.adminId()).stream()
            .map(this::view)
            .toList();
    }

    @PostMapping
    CannedResponseView create(
        @Valid @RequestBody CannedResponseRequest request,
        @AuthenticationPrincipal DispatchPrincipal principal) {
        CannedResponse response = new CannedResponse();
        response.setOwner(admins.findById(principal.adminId()).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Administrator not found.")));
        return view(repository.save(apply(response, request)));
    }

    @PutMapping("/{id}")
    CannedResponseView update(
        @PathVariable Long id,
        @Valid @RequestBody CannedResponseRequest request,
        @AuthenticationPrincipal DispatchPrincipal principal) {
        CannedResponse response = repository
            .findByIdAndOwnerId(id, principal.adminId())
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Canned response not found."));
        return view(repository.save(apply(response, request)));
    }

    @DeleteMapping("/{id}")
    void delete(@PathVariable Long id, @AuthenticationPrincipal DispatchPrincipal principal) {
        CannedResponse response = repository
            .findByIdAndOwnerId(id, principal.adminId())
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Canned response not found."));
        repository.delete(response);
    }

    private CannedResponse apply(CannedResponse target, CannedResponseRequest request) {
        target.setTitle(request.title());
        target.setBodyHtml(request.bodyHtml());
        return target;
    }

    private CannedResponseView view(CannedResponse response) {
        return new CannedResponseView(response.getId(), response.getTitle(), response.getBodyHtml());
    }
}
