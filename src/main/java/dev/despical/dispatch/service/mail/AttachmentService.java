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
import dev.despical.dispatch.entity.mail.MailMessage;
import dev.despical.dispatch.exception.ApiException;
import dev.despical.dispatch.repository.mail.AttachmentRepository;
import jakarta.mail.Part;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.*;

/**
 * @author Despical
 * <p>
 * Created at 21.09.2026
 */
@Service
public class AttachmentService {

    private final AttachmentRepository attachments;
    private final ArchiveSafetyService archiveSafety;
    private final ClamAvService clamAv;
    private final DispatchProperties properties;
    private final Path root;

    public AttachmentService(
        AttachmentRepository attachments,
        ArchiveSafetyService archiveSafety,
        ClamAvService clamAv,
        DispatchProperties properties
    ) {
        this.attachments = attachments;
        this.archiveSafety = archiveSafety;
        this.clamAv = clamAv;
        this.properties = properties;
        this.root = Path.of(properties.mail().storagePath()).toAbsolutePath().normalize();
    }

    @Transactional
    public Attachment store(MailMessage message, Part part) throws Exception {
        long max = properties.mail().attachmentMaxBytes();

        if (part.getSize() > max) {
            return saveRejected(
                message,
                part.getFileName(),
                part.getContentType(),
                part.getSize(),
                "Attachment exceeds the configured size limit");
        }

        byte[] content;
        try (var input = part.getInputStream()) {
            content = input.readNBytes(Math.toIntExact(Math.min(Integer.MAX_VALUE - 1L, max + 1)));
        }

        if (content.length > max) {
            return saveRejected(
                message,
                part.getFileName(),
                part.getContentType(),
                content.length,
                "Attachment exceeds the configured size limit");
        }

        String safeName = safeFilename(part.getFileName());
        Path directory = root.resolve(message.getAccount().getId().toString())
            .resolve(message.getId().toString())
            .normalize();

        if (!directory.startsWith(root)) {
            throw new IOException("Invalid storage path");
        }

        Files.createDirectories(directory);
        Path target = directory.resolve(UUID.randomUUID() + "-" + safeName).normalize();
        Files.write(target, content, StandardOpenOption.CREATE_NEW);

        Attachment attachment = new Attachment();
        attachment.setMessage(message);
        attachment.setFilename(safeName);
        attachment.setContentType(part.getContentType() == null ? "application/octet-stream" : part.getContentType());
        attachment.setSizeBytes(content.length);
        attachment.setStoragePath(root.relativize(target).toString());
        attachment.setSha256(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)));
        attachment.setScanStatus(Attachment.ScanStatus.SCANNING);
        attachments.save(attachment);

        try {
            archiveSafety.inspect(safeName, new ByteArrayInputStream(content));

            ClamAvService.ScanResult result = clamAv.scan(target);
            attachment.setScanStatus(result.status());
            attachment.setScanDetail(result.detail());
        } catch (IOException unsafeArchive) {
            attachment.setScanStatus(Attachment.ScanStatus.SUSPICIOUS);
            attachment.setScanDetail("Archive safety limit exceeded");
        }

        return attachment;
    }

    private Attachment saveRejected(
        MailMessage message, String filename, String contentType, long size, String reason) {
        Attachment attachment = new Attachment();
        attachment.setMessage(message);
        attachment.setFilename(safeFilename(filename));
        attachment.setContentType(contentType == null ? "application/octet-stream" : contentType);
        attachment.setSizeBytes(size);
        attachment.setStoragePath("rejected");
        attachment.setSha256("0".repeat(64));
        attachment.setScanStatus(Attachment.ScanStatus.QUARANTINED);
        attachment.setScanDetail(reason);
        return attachments.save(attachment);
    }

    public Path resolveForDownload(Attachment attachment) {
        Path resolved = root.resolve(attachment.getStoragePath()).normalize();

        if (!resolved.startsWith(root)) {
            throw new IllegalStateException("Invalid attachment path");
        }
        return resolved;
    }

    @Transactional
    public Attachment retryScan(Long id, Long ownerId) {
        Attachment attachment = attachments
            .findByIdAndMessageAccountOwnerId(id, ownerId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Attachment not found."));

        if (attachment.getScanStatus() != Attachment.ScanStatus.UNAVAILABLE) {
            return attachment;
        }

        Path path = resolveForDownload(attachment);
        if (!Files.isRegularFile(path)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Attachment file is missing.");
        }

        ClamAvService.ScanResult result = clamAv.scan(path);
        attachment.setScanStatus(result.status());
        attachment.setScanDetail(result.detail());
        return attachment;
    }

    public void deleteFilesForMessages(Collection<Long> messageIds) {
        if (messageIds.isEmpty()) return;
        Set<Path> messageDirectories = new HashSet<>();

        for (Attachment attachment : attachments.findAllByMessageIdIn(messageIds)) {
            if ("rejected".equals(attachment.getStoragePath())) continue;

            Path resolved = root.resolve(attachment.getStoragePath()).normalize();
            if (!resolved.startsWith(root)) {
                continue;
            }

            try {
                Files.deleteIfExists(resolved);

                if (resolved.getParent() != null && !resolved.getParent().equals(root)) {
                    messageDirectories.add(resolved.getParent());
                }
            } catch (IOException _) {
            }
        }

        for (Path directory : messageDirectories) {
            try {
                Files.deleteIfExists(directory);
            } catch (IOException _) {
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
