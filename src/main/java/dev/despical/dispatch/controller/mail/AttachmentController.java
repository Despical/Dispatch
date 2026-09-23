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

import dev.despical.dispatch.entity.mail.Attachment;
import dev.despical.dispatch.exception.ApiException;
import dev.despical.dispatch.security.DispatchPrincipal;
import dev.despical.dispatch.service.mail.AttachmentPreview;
import dev.despical.dispatch.service.mail.AttachmentService;
import dev.despical.dispatch.service.mail.MailAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * @author Despical
 * <p>
 * Created at 21.09.2026
 */
@RestController
@RequestMapping("/api/mail/attachments")
@RequiredArgsConstructor
public class AttachmentController {

    private final AttachmentService attachmentService;
    private final MailAccessService access;

    @GetMapping("/{id}")
    ResponseEntity<FileSystemResource> download(
        @PathVariable Long id,
        @AuthenticationPrincipal DispatchPrincipal principal
    ) {
        Attachment attachment = access.attachment(principal.adminId(), id);
        if (attachment.getScanStatus() != Attachment.ScanStatus.CLEAN) {
            throw new ApiException(HttpStatus.LOCKED, "This attachment is not available because it has not passed scanning.");
        }

        Path path = attachmentService.resolveForDownload(attachment);
        if (!Files.isRegularFile(path))
            throw new ApiException(HttpStatus.NOT_FOUND, "Attachment file is missing.");

        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .cacheControl(CacheControl.noStore())
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment()
                    .filename(attachment.getFilename(), StandardCharsets.UTF_8)
                    .build()
                    .toString())
            .header("X-Content-Type-Options", "nosniff")
            .body(new FileSystemResource(path));
    }

    @PostMapping("/{id}/scan")
    Map<String, String> retryScan(
        @PathVariable Long id,
        @AuthenticationPrincipal DispatchPrincipal principal
    ) {
        Attachment attachment = attachmentService.retryScan(id, principal.adminId());

        return Map.of(
            "scanStatus",
            attachment.getScanStatus().name(),
            "scanDetail",
            attachment.getScanDetail() == null ? "" : attachment.getScanDetail());
    }

    @GetMapping("/{id}/preview")
    ResponseEntity<AttachmentPreview.Content> preview(
        @PathVariable Long id,
        @AuthenticationPrincipal DispatchPrincipal principal
    ) throws IOException {
        Attachment attachment = access.attachment(principal.adminId(), id);
        if (attachment.getScanStatus() != Attachment.ScanStatus.CLEAN) {
            throw new ApiException(HttpStatus.LOCKED, "Preview is unavailable until this attachment passes scanning.");
        }

        Path path = attachmentService.resolveForDownload(attachment);
        if (!Files.isRegularFile(path)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Attachment file is missing.");
        }

        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(AttachmentPreview.read(path, attachment.getFilename()));
    }
}
