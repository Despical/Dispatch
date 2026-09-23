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
package dev.despical.dispatch.dto.mail;

import dev.despical.dispatch.entity.mail.Attachment;
import dev.despical.dispatch.entity.mail.OutboundMessage;
import jakarta.validation.constraints.*;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * @author Despical
 * <p>
 * Created at 21.09.2026
 */
@NoArgsConstructor
public final class MailDtos {

    public record AccountRequest(
        @NotBlank @Size(max = 120) String displayName,
        @Email @NotBlank String email,
        @NotBlank String imapHost,
        @Min(1) @Max(65535) int imapPort,
        @NotBlank String smtpHost,
        @Min(1) @Max(65535) int smtpPort,
        @NotBlank String username,
        @NotBlank String password,
        @Size(max = 20000) String signatureHtml
    ) {
    }

    public record AccountResponse(
        Long id,
        String displayName,
        String email,
        String authProvider,
        String syncStatus,
        String syncError,
        Instant lastSyncAt,
        boolean active
    ) {
    }

    public record AccountOrderRequest(@NotNull List<@NotNull Long> accountIds) {
    }

    public record FolderResponse(Long id, Long accountId, String name, int unreadCount) {
    }

    public record MessageSummary(
        Long id,
        Long accountId,
        String accountName,
        String subject,
        String fromAddress,
        String preview,
        Instant receivedAt,
        boolean read,
        boolean starred,
        boolean pinned,
        boolean hasAttachments
    ) {
    }

    public record AttachmentResponse(
        Long id,
        String filename,
        String contentType,
        long sizeBytes,
        Attachment.ScanStatus scanStatus,
        String scanDetail
    ) {
    }

    public record MessageDetail(
        Long id,
        Long accountId,
        String accountName,
        String subject,
        String fromAddress,
        String recipients,
        String textBody,
        String internetMessageId,
        String referencesHeader,
        Instant receivedAt,
        boolean read,
        boolean starred,
        boolean pinned,
        List<AttachmentResponse> attachments
    ) {
    }

    public record PageResponse<T>(
        List<T> content, int page, int size, long totalElements, int totalPages) {
    }

    public record StorageUsage(long usedBytes, long totalBytes, int usedPercent) {
    }

    public record FlagRequest(@NotNull Boolean value) {
    }

    public record DraftRequest(
        Long accountId,
        List<String> to,
        List<String> cc,
        List<String> bcc,
        @Size(max = 1000) String subject,
        @Size(max = 2_000_000) String bodyHtml,
        String bodyText,
        String inReplyTo,
        String referencesHeader,
        Long sourceMessageId
    ) {
    }

    public record DraftSummary(
        UUID id,
        String recipients,
        String subject, String
        preview,
        Instant savedAt
    ) {
    }

    public record DraftDetail(
        UUID id,
        Long accountId,
        String accountName,
        String accountEmail,
        boolean accountActive,
        String to,
        String cc,
        String bcc,
        String subject,
        String bodyText,
        String bodyHtml,
        String inReplyTo,
        String referencesHeader,
        Instant savedAt,
        List<AttachmentResponse> attachments,
        Long sourceMessageId
    ) {
    }

    public record TrashItem(MessageSummary message, DraftSummary draft) {
    }

    public record SendRequest(
        UUID draftId,
        @NotNull Long accountId,
        @NotNull @Size(min = 1, max = 50) List<@Email String> to,
        @Size(max = 50) List<@Email String> cc,
        @Size(max = 50) List<@Email String> bcc,
        @Size(max = 1000) String subject,
        @Size(max = 2_000_000) String bodyHtml,
        String bodyText,
        String inReplyTo,
        String referencesHeader,
        @NotBlank @Size(max = 100) String idempotencyKey
    ) {
    }

    public record OutboundResponse(
        UUID id, OutboundMessage.Status status, String failureReason, Instant sentAt) {
    }

    public record CannedResponseRequest(
        @NotBlank @Size(max = 120) String title,
        @NotBlank @Size(max = 100_000) String bodyHtml) {
    }

    public record CannedResponseView(Long id, String title, String bodyHtml) {
    }
}
