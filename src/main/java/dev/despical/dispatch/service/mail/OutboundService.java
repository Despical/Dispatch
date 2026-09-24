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

import dev.despical.dispatch.dto.mail.MailDtos.*;
import dev.despical.dispatch.entity.mail.*;
import dev.despical.dispatch.exception.ApiException;
import dev.despical.dispatch.repository.mail.MailAccountRepository;
import dev.despical.dispatch.repository.mail.MailMessageRepository;
import dev.despical.dispatch.repository.mail.OutboundAttachmentRepository;
import dev.despical.dispatch.repository.mail.OutboundMessageRepository;
import dev.despical.dispatch.security.RateLimitService;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * @author Despical
 * <p>
 * Created at 21.09.2026
 */
@Service
public class OutboundService {

    private final OutboundMessageRepository outbox;
    private final MailAccountRepository accounts;
    private final MailConnectionFactory connections;
    private final RateLimitService rateLimits;
    private final MeterRegistry metrics;
    private final TransactionTemplate transactions;
    private final OutboundAttachmentRepository attachments;
    private final OutboundAttachmentService attachmentService;
    private final MailMessageRepository messages;
    private final MailAccessService access;

    public OutboundService(OutboundMessageRepository outbox, MailAccountRepository accounts,
                           MailConnectionFactory connections, RateLimitService rateLimits,
                           MeterRegistry metrics, PlatformTransactionManager transactionManager,
                           OutboundAttachmentRepository attachments,
                           OutboundAttachmentService attachmentService,
                           MailMessageRepository messages, MailAccessService access) {
        this.outbox = outbox;
        this.accounts = accounts;
        this.connections = connections;
        this.rateLimits = rateLimits;
        this.metrics = metrics;
        this.transactions = new TransactionTemplate(transactionManager);
        this.attachments = attachments;
        this.attachmentService = attachmentService;
        this.messages = messages;
        this.access = access;
    }

    @PostConstruct
    public void quarantineInterruptedSends() {
        transactions.executeWithoutResult(
            status
                -> outbox
                .findTop20ByStatusInOrderByCreatedAtAsc(List.of(OutboundMessage.Status.SENDING))
                .forEach(message -> {
                    message.setStatus(OutboundMessage.Status.UNCERTAIN);
                    message.setFailureReason("Delivery result is unknown after an"
                        + " application restart; manual review"
                        + " required.");
                }));
    }

    @Transactional
    public OutboundResponse saveDraft(Long ownerId, UUID publicId, DraftRequest request) {
        OutboundMessage message =
            publicId == null
                ? new OutboundMessage()
                : outbox.findByPublicIdAndCreatedById(publicId, ownerId)
                .orElseThrow(
                    () -> new ApiException(HttpStatus.NOT_FOUND, "Draft not found."));
        if (message.getId() != null && (message.getStatus() != OutboundMessage.Status.DRAFT ||
            message.getTrashedAt() != null)) {
            throw new ApiException(HttpStatus.CONFLICT, "This message is no longer a draft.");
        }
        if (message.getId() == null) message.setPublicId(UUID.randomUUID());
        if (request.accountId() != null &&
            (message.getAccount() == null ||
                !request.accountId().equals(message.getAccount().getId()))) {
            message.setAccount(account(ownerId, request.accountId()));
            if (message.getCreatedBy() == null) message.setCreatedBy(access.actor(ownerId));
        }
        if (message.getAccount() == null)
            throw new ApiException(HttpStatus.BAD_REQUEST, "Select a sender account.");
        access.require(ownerId, message.getAccount(), MailAccessService.Action.SEND);
        if (message.getCreatedBy() == null) message.setCreatedBy(access.actor(ownerId));
        if (request.sourceMessageId() != null) {
            var source =
                access.message(ownerId, request.sourceMessageId(), MailAccessService.Action.VIEW);
            message.setSourceMessageId(source.getId());
        }
        message.setRecipients(join(request.to()));
        message.setCc(join(request.cc()));
        message.setBcc(join(request.bcc()));
        message.setSubject(request.subject() == null ? "" : request.subject());
        message.setBodyHtml(request.bodyHtml());
        message.setBodyText(request.bodyText());
        message.setInReplyTo(request.inReplyTo());
        message.setReferencesHeader(request.referencesHeader());
        message.setStatus(OutboundMessage.Status.DRAFT);
        message.setLastDraftSavedAt(Instant.now());
        return response(outbox.save(message));
    }

    @Transactional(readOnly = true)
    public Page<DraftSummary> drafts(Long ownerId, int page, String query) {
        return drafts(ownerId, null, page, query);
    }

    @Transactional(readOnly = true)
    public Page<DraftSummary> drafts(Long ownerId, Long accountId, int page, String query) {
        var pageable = PageRequest.of(Math.max(0, page), 30,
            Sort.by(Sort.Direction.DESC, "lastDraftSavedAt", "id"));
        var result = accountId == null
            ? outbox.findDrafts(ownerId, OutboundMessage.Status.DRAFT, query.trim(), pageable)
            : outbox.findDraftsForAccount(
                ownerId, accountId, OutboundMessage.Status.DRAFT, query.trim(), pageable);
        return result
            .map(message -> {
                String text = draftText(message);
                return new DraftSummary(
                    message.getPublicId(), message.getRecipients(), message.getSubject(),
                    text.substring(0, Math.min(text.length(), 160)), message.getLastDraftSavedAt());
            });
    }

    @Transactional(readOnly = true)
    public long draftCount(Long ownerId) {
        return draftCount(ownerId, null);
    }

    @Transactional(readOnly = true)
    public long draftCount(Long ownerId, Long accountId) {
        return accountId == null
            ? outbox.countByCreatedByIdAndStatusAndTrashedAtIsNull(ownerId,
                OutboundMessage.Status.DRAFT)
            : outbox.countByCreatedByIdAndAccountIdAndStatusAndTrashedAtIsNull(
                ownerId, accountId, OutboundMessage.Status.DRAFT);
    }

    @Transactional(readOnly = true)
    public DraftDetail draft(Long ownerId, UUID id) {
        OutboundMessage message =
            outbox.findByPublicIdAndCreatedById(id, ownerId)
                .filter(item
                    -> item.getStatus() == OutboundMessage.Status.DRAFT &&
                    item.getTrashedAt() == null)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Draft not found."));
        MailAccount account = message.getAccount();
        Long sourceId = message.getSourceMessageId();
        if (sourceId != null) {
            try {
                sourceId = access.message(ownerId, sourceId, MailAccessService.Action.VIEW).getId();
            } catch (ApiException denied) {
                sourceId = null;
            }
        }
        if (sourceId == null && message.getInReplyTo() != null &&
            !message.getInReplyTo().isBlank()) {
            sourceId = messages
                .findFirstByAccountIdAndAccountOwnerIdAndInternetMessageIdOrderByIdDesc(
                    account.getId(), ownerId, message.getInReplyTo())
                .map(MailMessage::getId)
                .orElse(null);
        }
        var files = attachments.findAllByOutboundMessageIdOrderByIdAsc(message.getId())
            .stream()
            .map(file
                -> new AttachmentResponse(file.getId(), file.getFilename(),
                file.getContentType(), file.getSizeBytes(),
                file.getScanStatus(), file.getScanDetail()))
            .toList();
        return new DraftDetail(
            message.getPublicId(), account.getId(), account.getDisplayName(), account.getEmail(),
            canSend(ownerId, account), message.getRecipients(), message.getCc(), message.getBcc(),
            message.getSubject(), draftText(message), message.getBodyHtml(), message.getInReplyTo(),
            message.getReferencesHeader(), message.getLastDraftSavedAt(), files, sourceId);
    }

    @Transactional
    public void trashDraft(Long ownerId, UUID id, boolean trashed) {
        var draft =
            outbox.findByPublicIdAndCreatedById(id, ownerId)
                .filter(item -> item.getStatus() == OutboundMessage.Status.DRAFT)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Draft not found."));
        if (!trashed) draft.setTrashedAt(null);
        else if (draft.getTrashedAt() == null) draft.setTrashedAt(Instant.now());
    }

    private String draftText(OutboundMessage message) {
        if (message.getBodyText() != null && !message.getBodyText().isBlank())
            return message.getBodyText();
        return message.getBodyHtml() == null ? ""
            : org.jsoup.Jsoup.parse(message.getBodyHtml()).text();
    }

    @Transactional
    public OutboundResponse queue(Long ownerId, SendRequest request) {
        OutboundMessage existing =
            outbox.findByIdempotencyKeyAndCreatedById(request.idempotencyKey(), ownerId)
                .orElse(null);
        if (existing != null) return response(existing);
        OutboundMessage message =
            request.draftId() == null
                ? new OutboundMessage()
                : outbox.findByPublicIdAndCreatedById(request.draftId(), ownerId)
                .orElseThrow(
                    () -> new ApiException(HttpStatus.NOT_FOUND, "Draft not found."));
        if (message.getId() != null && (message.getStatus() != OutboundMessage.Status.DRAFT ||
            message.getTrashedAt() != null)) {
            throw new ApiException(HttpStatus.CONFLICT, "This draft can no longer be sent.");
        }
        if (message.getId() == null) message.setPublicId(UUID.randomUUID());
        message.setIdempotencyKey(request.idempotencyKey());
        message.setAccount(account(ownerId, request.accountId()));
        if (message.getCreatedBy() == null) message.setCreatedBy(access.actor(ownerId));
        message.setRecipients(join(request.to()));
        message.setCc(join(request.cc()));
        message.setBcc(join(request.bcc()));
        message.setSubject(request.subject() == null ? "" : request.subject());
        message.setBodyHtml(request.bodyHtml());
        message.setBodyText(request.bodyText());
        message.setInReplyTo(request.inReplyTo());
        message.setReferencesHeader(request.referencesHeader());
        if (message.getId() != null &&
            attachments.findAllByOutboundMessageIdOrderByIdAsc(message.getId())
                .stream()
                .anyMatch(item -> item.getScanStatus() != Attachment.ScanStatus.CLEAN)) {
            throw new ApiException(
                HttpStatus.CONFLICT,
                "Every attachment must pass scanning before the message can be queued.");
        }
        message.setStatus(OutboundMessage.Status.QUEUED);
        return response(outbox.save(message));
    }

    @Scheduled(fixedDelay = 2_000)
    public void drain() {
        for (OutboundMessage candidate : outbox.findTop20ByStatusInOrderByCreatedAtAsc(
            List.of(OutboundMessage.Status.QUEUED))) {
            if (rateLimits.allow("smtp:" + candidate.getAccount().getId(), 30, 60))
                send(candidate.getId());
        }
    }

    void send(Long id) {
        OutboundMessage message = transactions.execute(status -> {
            OutboundMessage managed = outbox.findForDelivery(id).orElseThrow();
            if (managed.getStatus() != OutboundMessage.Status.QUEUED) return null;
            if (!managed.getAccount().isActive() || !managed.getAccount().getOwner().isEnabled()) {
                managed.setStatus(OutboundMessage.Status.FAILED);
                managed.setFailureReason("The sender account or its administrator is disabled. No"
                    + " message was sent.");
                return null;
            }
            if (!managed.getCreatedBy().isEnabled() ||
                !canSend(managed.getCreatedBy().getId(), managed.getAccount())) {
                managed.setStatus(OutboundMessage.Status.FAILED);
                managed.setFailureReason(
                    "Sending permission was removed before delivery. No message"
                        + " was sent.");
                return null;
            }
            managed.setStatus(OutboundMessage.Status.SENDING);
            managed.setSendStartedAt(Instant.now());
            return managed;
        });
        if (message == null) return;
        boolean submitted = false;
        boolean accepted = false;
        try {
            MimeMessage mime = createMime(message);
            try (Transport transport = connections.openSmtp(message.getAccount())) {
                submitted = true;
                transport.sendMessage(mime, mime.getAllRecipients());
                accepted = true;
            }
            transactions.executeWithoutResult(status -> {
                OutboundMessage managed = outbox.findById(id).orElseThrow();
                managed.setStatus(OutboundMessage.Status.SENT);
                managed.setSentAt(Instant.now());
                managed.setFailureReason(null);
            });
            metrics.counter("dispatch.outbox.send", "result", "sent").increment();
        } catch (Exception exception) {
            final boolean deliveryAttempted = submitted, deliveryAccepted = accepted;
            transactions.executeWithoutResult(status -> {
                OutboundMessage managed = outbox.findById(id).orElseThrow();
                managed.setStatus(deliveryAccepted ? OutboundMessage.Status.SENT
                    : deliveryAttempted ? OutboundMessage.Status.UNCERTAIN
                    : OutboundMessage.Status.FAILED);
                if (deliveryAccepted) {
                    managed.setSentAt(Instant.now());
                    managed.setFailureReason(null);
                } else
                    managed.setFailureReason(
                        deliveryAttempted ? "SMTP delivery result is uncertain; automatic retry"
                            + " is disabled. Check the recipient before"
                            + " sending again."
                            : exception instanceof jakarta.mail.AuthenticationFailedException
                            ? "SMTP sign-in was rejected. Check the mail"
                            + " account credentials. No message was"
                            + " sent."
                            : "The message could not be prepared or the"
                            + " SMTP connection could not be opened."
                            + " No message was sent. Check the sender"
                            + " account settings.");
            });
            metrics
                .counter("dispatch.outbox.send", "result",
                    accepted ? "sent"
                        : submitted ? "uncertain"
                        : "failed")
                .increment();
        }
    }

    private MimeMessage createMime(OutboundMessage outbound) throws Exception {
        MailAccount account = outbound.getAccount();
        MimeMessage message = new MimeMessage(connections.smtpSession(account));
        message.setFrom(new InternetAddress(account.getEmail(), account.getDisplayName(),
            StandardCharsets.UTF_8.name()));
        addRecipients(message, Message.RecipientType.TO, outbound.getRecipients());
        addRecipients(message, Message.RecipientType.CC, outbound.getCc());
        addRecipients(message, Message.RecipientType.BCC, outbound.getBcc());
        message.setSubject(outbound.getSubject(), StandardCharsets.UTF_8.name());
        String html = null;
        if ((outbound.getBodyHtml() != null && !outbound.getBodyHtml().isBlank()) ||
            (account.getSignatureHtml() != null && !account.getSignatureHtml().isBlank())) {
            html = outbound.getBodyHtml();
            if (html == null || html.isBlank()) {
                html = new org.jsoup.nodes.Element("div")
                    .attr("style", "white-space:pre-wrap")
                    .text(outbound.getBodyText() == null ? "" : outbound.getBodyText())
                    .outerHtml();
            }
            if (account.getSignatureHtml() != null && !account.getSignatureHtml().isBlank()) {
                html += "<br><div class=\"dispatch-signature\">" + account.getSignatureHtml() +
                    "</div>";
            }
        }
        List<OutboundAttachment> files =
            attachments.findAllByOutboundMessageIdOrderByIdAsc(outbound.getId());
        if (files.isEmpty()) {
            if (html != null) message.setContent(html, "text/html; charset=UTF-8");
            else
                message.setText(outbound.getBodyText() == null ? "" : outbound.getBodyText(),
                    StandardCharsets.UTF_8.name());
        } else {
            MimeMultipart multipart = new MimeMultipart("mixed");
            MimeBodyPart body = new MimeBodyPart();
            if (html != null) body.setContent(html, "text/html; charset=UTF-8");
            else
                body.setText(outbound.getBodyText() == null ? "" : outbound.getBodyText(),
                    StandardCharsets.UTF_8.name());
            multipart.addBodyPart(body);
            for (OutboundAttachment file : files) {
                if (file.getScanStatus() != Attachment.ScanStatus.CLEAN) {
                    throw new MessagingException("Attachment is not cleared for sending");
                }
                MimeBodyPart part = new MimeBodyPart();
                part.attachFile(attachmentService.resolve(file).toFile());
                part.setFileName(file.getFilename());
                multipart.addBodyPart(part);
            }
            message.setContent(multipart);
        }
        if (outbound.getInReplyTo() != null)
            message.setHeader("In-Reply-To", outbound.getInReplyTo());
        if (outbound.getReferencesHeader() != null)
            message.setHeader("References", outbound.getReferencesHeader());
        message.setSentDate(java.util.Date.from(Instant.now()));
        message.saveChanges();
        message.setHeader("Message-ID", "<dispatch-" + outbound.getPublicId() + "@local.invalid>");
        return message;
    }

    private void addRecipients(MimeMessage message, Message.RecipientType type, String csv)
        throws MessagingException {
        if (csv != null && !csv.isBlank())
            message.setRecipients(type, InternetAddress.parse(csv, true));
    }

    private MailAccount account(Long ownerId, Long id) {
        MailAccount account = access.account(ownerId, id, MailAccessService.Action.SEND);
        if (!account.isActive())
            throw new ApiException(HttpStatus.CONFLICT, "Sender account is inactive.");
        return account;
    }

    private boolean canSend(Long actorId, MailAccount account) {
        try {
            access.require(actorId, account, MailAccessService.Action.SEND);
            return account.isActive();
        } catch (ApiException denied) {
            return false;
        }
    }

    private String join(Collection<String> values) {
        return values == null ? "" : String.join(",", values);
    }

    private OutboundResponse response(OutboundMessage message) {
        return new OutboundResponse(message.getPublicId(), message.getStatus(),
            message.getFailureReason(), message.getSentAt());
    }

    public long pendingCount() {
        return outbox.countByStatusIn(List.of(OutboundMessage.Status.QUEUED,
            OutboundMessage.Status.SENDING,
            OutboundMessage.Status.UNCERTAIN));
    }

    @Transactional(readOnly = true)
    public Page<SentItem> sent(Long ownerId, int page, String query) {
        return outbox
            .findSent(ownerId, query.trim(),
                PageRequest.of(Math.max(0, page), 30,
                    Sort.by(Sort.Direction.DESC, "createdAt", "id")))
            .map(this::sentView);
    }

    @Transactional(readOnly = true)
    public SentItem sentDetail(Long ownerId, UUID id) {
        return sentView(
            outbox.findByPublicIdAndCreatedById(id, ownerId)
                .filter(item -> item.getStatus() != OutboundMessage.Status.DRAFT)
                .orElseThrow(
                    () -> new ApiException(HttpStatus.NOT_FOUND, "Sent message not found.")));
    }

    private SentItem sentView(OutboundMessage message) {
        return new SentItem(message.getPublicId(), message.getAccount().getDisplayName(),
            message.getAccount().getEmail(), message.getRecipients(),
            message.getCc(), message.getBcc(), message.getSubject(),
            draftText(message), message.getStatus(), message.getFailureReason(),
            message.getCreatedAt(), message.getSentAt());
    }

    public record SentItem(UUID id, String accountName, String from, String to, String cc,
                           String bcc, String subject, String bodyText,
                           OutboundMessage.Status status, String failureReason, Instant createdAt,
                           Instant sentAt) {
    }
}
