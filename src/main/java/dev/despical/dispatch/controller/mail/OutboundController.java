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

import dev.despical.dispatch.dto.mail.MailDtos.*;
import dev.despical.dispatch.exception.ApiException;
import dev.despical.dispatch.security.DispatchPrincipal;
import dev.despical.dispatch.security.RateLimitService;
import dev.despical.dispatch.service.mail.OutboundService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * @author Despical
 * <p>
 * Created at 21.09.2026
 */
@RestController
@RequestMapping("/api/mail/outbound")
@RequiredArgsConstructor
public class OutboundController {

    private final OutboundService outboundService;
    private final RateLimitService rateLimits;

    @PostMapping("/drafts")
    OutboundResponse createDraft(
        @AuthenticationPrincipal DispatchPrincipal principal,
        @Valid @RequestBody DraftRequest request
    ) {
        return outboundService.saveDraft(principal.adminId(), null, request);
    }

    @GetMapping("/sent")
    PageResponse<OutboundService.SentItem> sent(
        @AuthenticationPrincipal DispatchPrincipal principal,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "") String query
    ) {
        var result = outboundService.sent(principal.adminId(), page, query);

        return new PageResponse<>(
            result.getContent(),
            result.getNumber(),
            result.getSize(),
            result.getTotalElements(),
            result.getTotalPages());
    }

    @GetMapping("/sent/{id}")
    OutboundService.SentItem sentDetail(
        @PathVariable UUID id, @AuthenticationPrincipal DispatchPrincipal principal) {
        return outboundService.sentDetail(principal.adminId(), id);
    }

    @GetMapping("/drafts")
    PageResponse<DraftSummary> drafts(
        @AuthenticationPrincipal DispatchPrincipal principal,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(required = false) Long accountId,
        @RequestParam(defaultValue = "") String query
    ) {
        var result = outboundService.drafts(principal.adminId(), accountId, page, query);

        return new PageResponse<>(
            result.getContent(),
            result.getNumber(),
            result.getSize(),
            result.getTotalElements(),
            result.getTotalPages());
    }

    @GetMapping("/drafts/count")
    Map<String, Long> draftCount(
        @AuthenticationPrincipal DispatchPrincipal principal,
        @RequestParam(required = false) Long accountId) {
        return Map.of("count", outboundService.draftCount(principal.adminId(), accountId));
    }

    @GetMapping("/drafts/{id}")
    DraftDetail draft(@PathVariable UUID id, @AuthenticationPrincipal DispatchPrincipal principal) {
        return outboundService.draft(principal.adminId(), id);
    }

    @PutMapping("/drafts/{id}")
    OutboundResponse saveDraft(
        @PathVariable UUID id,
        @AuthenticationPrincipal DispatchPrincipal principal,
        @Valid @RequestBody DraftRequest request
    ) {
        return outboundService.saveDraft(principal.adminId(), id, request);
    }

    @PatchMapping("/drafts/{id}/trashed")
    void trash(
        @PathVariable UUID id,
        @AuthenticationPrincipal DispatchPrincipal principal,
        @Valid @RequestBody FlagRequest request
    ) {
        outboundService.trashDraft(principal.adminId(), id, request.value());
    }

    @PostMapping("/send")
    OutboundResponse send(
        @AuthenticationPrincipal DispatchPrincipal principal,
        @Valid @RequestBody SendRequest request,
        HttpServletRequest servletRequest
    ) {
        if (!rateLimits.allow(
            "send:" + principal.adminId() + ":" + servletRequest.getRemoteAddr(), 20, 60)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Sending rate limit reached.");
        }

        return outboundService.queue(principal.adminId(), request);
    }
}
