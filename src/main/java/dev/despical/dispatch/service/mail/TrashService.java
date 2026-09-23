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

import dev.despical.dispatch.dto.mail.MailDtos.DraftSummary;
import dev.despical.dispatch.dto.mail.MailDtos.PageResponse;
import dev.despical.dispatch.dto.mail.MailDtos.TrashItem;
import dev.despical.dispatch.entity.mail.MailMessage;
import dev.despical.dispatch.entity.mail.OutboundMessage;
import dev.despical.dispatch.repository.mail.MailAccountRepository;
import dev.despical.dispatch.repository.mail.MailMessageRepository;
import dev.despical.dispatch.repository.mail.OutboundMessageRepository;
import dev.despical.dispatch.repository.mail.TrashQueryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * @author Despical
 * <p>
 * Created at 21.09.2026
 */
@Service
@RequiredArgsConstructor
public class TrashService {

    private static final Duration RETENTION = Duration.ofDays(90);

    private final MailMessageRepository messages;
    private final AttachmentService attachments;
    private final OutboundMessageRepository outbox;
    private final OutboundAttachmentService outboundAttachments;
    private final TrashQueryRepository trashQuery;
    private final MessageQueryService messageQuery;
    private final MailAccountRepository accounts;
    private final MailAccessService access;
    private final GmailMessageService gmail;

    @Transactional(readOnly = true)
    public long count(Long ownerId, Long accountId) {
        Long mailboxOwner = mailboxOwner(ownerId, accountId);
        return trashQuery.count(ownerId, mailboxOwner, accountId);
    }

    @Transactional(readOnly = true)
    public PageResponse<TrashItem> list(Long ownerId, Long accountId, int page, String filter,
                                        String query) {
        Long mailboxOwner = mailboxOwner(ownerId, accountId);
        var result = trashQuery.list(ownerId, mailboxOwner, accountId, filter, query.trim(),
            PageRequest.of(Math.max(0, page), 30));
        var items =
            result.stream()
                .map(row -> {
                    if (!row.draft())
                        return new TrashItem(
                            messageQuery.summary(messageQuery.get(ownerId, row.id())), null);
                    var draft = outbox.findById(row.id()).orElseThrow();
                    String body = draft.getBodyText() == null ? "" : draft.getBodyText();
                    return new TrashItem(
                        null, new DraftSummary(draft.getPublicId(), draft.getRecipients(),
                        draft.getSubject(),
                        body.substring(0, Math.min(body.length(), 160)),
                        draft.getLastDraftSavedAt()));
                })
                .toList();
        return new PageResponse<>(items, result.getNumber(), result.getSize(),
            result.getTotalElements(), result.getTotalPages());
    }

    @Transactional
    public int emptyTrash(Long ownerId, Long accountId) {
        Long mailboxOwner = mailboxOwner(ownerId, accountId);
        return permanentlyDelete(
            accountId == null
                ? messages.findAllByAccountOwnerIdAndTrashedFlagTrue(ownerId)
                : messages.findAllByAccountIdAndAccountOwnerIdAndTrashedFlagTrue(
                accountId, mailboxOwner)) +
            permanentlyDeleteDrafts(
                accountId == null
                    ? outbox.findAllByCreatedByIdAndStatusAndTrashedAtIsNotNull(
                    ownerId, OutboundMessage.Status.DRAFT)
                    : outbox.findAllByAccountIdAndCreatedByIdAndStatusAndTrashedAtIsNotNull(
                    accountId, ownerId, OutboundMessage.Status.DRAFT));
    }

    private Long mailboxOwner(Long ownerId, Long accountId) {
        if (accountId == null) return ownerId;
        if (accounts.findByIdAndOwnerId(accountId, ownerId).isPresent()) return ownerId;
        return access.account(ownerId, accountId, MailAccessService.Action.DELETE)
            .getOwner()
            .getId();
    }

    @Scheduled(initialDelay = 60_000, fixedDelay = 86_400_000)
    @Transactional
    public void purgeExpiredTrash() {
        permanentlyDelete(
            messages.findAllByTrashedFlagTrueAndTrashedAtBefore(Instant.now().minus(RETENTION)));
        permanentlyDeleteDrafts(outbox.findAllByStatusAndTrashedAtBefore(
            OutboundMessage.Status.DRAFT, Instant.now().minus(RETENTION)));
    }

    private int permanentlyDeleteDrafts(List<OutboundMessage> drafts) {
        if (drafts.isEmpty()) return 0;
        drafts.forEach(draft -> outboundAttachments.deleteFilesForDraft(draft.getId()));
        outbox.deleteAllInBatch(drafts);
        return drafts.size();
    }

    private int permanentlyDelete(List<MailMessage> targets) {
        if (targets.isEmpty()) return 0;
        List<MailMessage> confirmed = new ArrayList<>();
        for (MailMessage message : targets) {
            if ("GOOGLE".equals(message.getAccount().getAuthProvider())) {
                GmailMessageService.MoveResult state = gmail.deleteIfTrashed(message);
                if (!state.missing() && !state.trashed()) {
                    message.setGmailMessageId(state.gmailId());
                    message.setTrashedFlag(false);
                    message.setTrashedAt(null);
                    message.setTrashOriginLabels(null);
                    continue;
                }
            }
            confirmed.add(message);
        }
        if (confirmed.isEmpty()) return 0;
        List<Long> ids = confirmed.stream().map(MailMessage::getId).toList();
        attachments.deleteFilesForMessages(ids);
        messages.deleteAllInBatch(confirmed);
        return confirmed.size();
    }
}
