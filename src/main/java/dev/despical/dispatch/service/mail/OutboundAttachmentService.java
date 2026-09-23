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
package dev.despical.dispatch.service.mail;

import dev.despical.dispatch.config.DispatchProperties;
import dev.despical.dispatch.entity.mail.Attachment;
import dev.despical.dispatch.entity.mail.OutboundAttachment;
import dev.despical.dispatch.entity.mail.OutboundMessage;
import dev.despical.dispatch.exception.ApiException;
import dev.despical.dispatch.repository.mail.OutboundAttachmentRepository;
import dev.despical.dispatch.repository.mail.OutboundMessageRepository;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * @author Despical
 * <p>
 * Created at 21.09.2026
 */
@Service
public class OutboundAttachmentService {

    private final OutboundMessageRepository outbox;
    private final OutboundAttachmentRepository attachments;
    private final ArchiveSafetyService archiveSafety;
    private final ClamAvService clamAv;
    private final DispatchProperties properties;
    private final Path root;

    public OutboundAttachmentService(OutboundMessageRepository outbox,
                                     OutboundAttachmentRepository attachments,
                                     ArchiveSafetyService archiveSafety, ClamAvService clamAv,
                                     DispatchProperties properties) {
        this.outbox = outbox;
        this.attachments = attachments;
        this.archiveSafety = archiveSafety;
        this.clamAv = clamAv;
        this.properties = properties;
        this.root = Path.of(properties.mail().storagePath())
            .toAbsolutePath()
            .normalize()
            .resolve("outbound");
    }

    @Transactional
    public OutboundAttachment store(Long ownerId, UUID draftId, MultipartFile file) {
        OutboundMessage draft =
            outbox.findByPublicIdAndCreatedById(draftId, ownerId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Draft not found."));
        if (draft.getStatus() != OutboundMessage.Status.DRAFT || draft.getTrashedAt() != null) {
            throw new ApiException(HttpStatus.CONFLICT,
                "Attachments can only be added to a draft.");
        }
        if (file.isEmpty() || file.getSize() > properties.mail().attachmentMaxBytes()) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE,
                "Attachment exceeds the configured size limit.");
        }
        try {
            byte[] content = file.getBytes();
            String filename = safeFilename(file.getOriginalFilename());
            Path directory = root.resolve(draft.getPublicId().toString()).normalize();
            if (!directory.startsWith(root))
                throw new IllegalStateException("Invalid attachment path");
            Files.createDirectories(directory);
            Path target = directory.resolve(UUID.randomUUID() + "-" + filename).normalize();
            Files.write(target, content);
            OutboundAttachment attachment = new OutboundAttachment();
            attachment.setOutboundMessage(draft);
            attachment.setFilename(filename);
            attachment.setContentType(file.getContentType() == null ? "application/octet-stream"
                : file.getContentType());
            attachment.setSizeBytes(content.length);
            attachment.setStoragePath(root.relativize(target).toString());
            attachment.setSha256(
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)));
            attachment.setScanStatus(Attachment.ScanStatus.SCANNING);
            attachments.save(attachment);
            try {
                archiveSafety.inspect(filename, new ByteArrayInputStream(content));
                var result = clamAv.scan(target);
                attachment.setScanStatus(result.status());
                attachment.setScanDetail(result.detail());
            } catch (Exception unsafe) {
                attachment.setScanStatus(Attachment.ScanStatus.SUSPICIOUS);
                attachment.setScanDetail("Archive safety limit exceeded");
            }
            return attachment;
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                "Attachment could not be stored safely.");
        }
    }

    @Transactional(readOnly = true)
    public List<OutboundAttachment> list(Long ownerId, UUID draftId) {
        OutboundMessage draft =
            outbox.findByPublicIdAndCreatedById(draftId, ownerId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Draft not found."));
        return attachments.findAllByOutboundMessageIdOrderByIdAsc(draft.getId());
    }

    public Path resolve(OutboundAttachment attachment) {
        Path path = root.resolve(attachment.getStoragePath()).normalize();
        if (!path.startsWith(root)) throw new IllegalStateException("Invalid attachment path");
        return path;
    }

    public void deleteFilesForDraft(Long id) {
        for (var file : attachments.findAllByOutboundMessageIdOrderByIdAsc(id)) {
            try {
                Path path = resolve(file);
                Files.deleteIfExists(path);
                if (!path.getParent().equals(root)) {
                    try {
                        Files.deleteIfExists(path.getParent());
                    } catch (java.io.IOException ignored) {
                    }
                }
            } catch (java.io.IOException ignored) {
                // Keep database cleanup moving if an attachment is temporarily locked.
            }
        }
    }

    private String safeFilename(String filename) {
        if (filename == null || filename.isBlank()) return "attachment.bin";
        String safe = Path.of(filename.replace('\\', '/'))
            .getFileName()
            .toString()
            .replaceAll("[\\p{Cntrl}]", "_")
            .replaceAll("[^a-zA-Z0-9._() -]", "_");
        return safe.length() <= 180 ? safe : safe.substring(safe.length() - 180);
    }
}
