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

import dev.despical.dispatch.dto.mail.MailDtos.AttachmentResponse;
import dev.despical.dispatch.entity.mail.OutboundAttachment;
import dev.despical.dispatch.security.DispatchPrincipal;
import dev.despical.dispatch.service.mail.OutboundAttachmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * @author Despical
 * <p>
 * Created at 21.09.2026
 */
@RestController
@RequestMapping("/api/mail/outbound/drafts/{draftId}/attachments")
@RequiredArgsConstructor
public class OutboundAttachmentController {

    private final OutboundAttachmentService service;

    @PostMapping
    AttachmentResponse upload(
        @PathVariable UUID draftId,
        @RequestParam("file") MultipartFile file,
        @AuthenticationPrincipal DispatchPrincipal principal
    ) {
        return view(service.store(principal.adminId(), draftId, file));
    }

    @GetMapping
    List<AttachmentResponse> list(
        @PathVariable UUID draftId, @AuthenticationPrincipal DispatchPrincipal principal) {
        return service.list(principal.adminId(), draftId).stream().map(this::view).toList();
    }

    private AttachmentResponse view(OutboundAttachment item) {
        return new AttachmentResponse(
            item.getId(),
            item.getFilename(),
            item.getContentType(),
            item.getSizeBytes(),
            item.getScanStatus(),
            item.getScanDetail());
    }
}
