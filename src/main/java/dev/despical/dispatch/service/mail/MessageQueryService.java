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

import dev.despical.dispatch.dto.mail.MailDtos.AttachmentResponse;
import dev.despical.dispatch.dto.mail.MailDtos.MessageDetail;
import dev.despical.dispatch.dto.mail.MailDtos.MessageSummary;
import dev.despical.dispatch.dto.mail.MailDtos.PageResponse;
import dev.despical.dispatch.entity.mail.MailMessage;
import dev.despical.dispatch.repository.mail.AttachmentRepository;
import dev.despical.dispatch.repository.mail.MailMessageRepository;

import jakarta.persistence.criteria.Predicate;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * @author Despical
 * <p>
 * Created at 21.09.2026
 */
@Service
@RequiredArgsConstructor
public class MessageQueryService {

    private final MailAccessService access;
    private final MailMessageRepository messages;
    private final AttachmentRepository attachments;

    @Transactional(readOnly = true)
    public PageResponse<MessageSummary>
    list(Long ownerId, int page, int size, Long accountId, Long folderId, String query,
         Boolean unread, Boolean starred, Boolean hasAttachments, boolean trashed) {
        final Long mailboxOwner =
            accountId == null ? ownerId
                : access.account(ownerId, accountId, MailAccessService.Action.VIEW)
                .getOwner()
                .getId();
        if (trashed && !mailboxOwner.equals(ownerId))
            access.account(ownerId, accountId, MailAccessService.Action.DELETE);
        int safeSize = Math.min(Math.max(size, 1), 100);
        Specification<MailMessage> specification = (root, ignored, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(builder.equal(root.get("account").get("owner").get("id"), mailboxOwner));
            if (accountId != null)
                predicates.add(builder.equal(root.get("account").get("id"), accountId));
            if (folderId != null)
                predicates.add(builder.equal(root.get("folder").get("id"), folderId));
            if (folderId == null)
                predicates.add(
                    builder.or(builder.notEqual(root.get("account").get("authProvider"), "GOOGLE"),
                        root.get("folder").get("specialUse").in("ALL", "JUNK", "TRASH")));
            if (unread != null) predicates.add(builder.equal(root.get("readFlag"), !unread));
            if (starred != null) predicates.add(builder.equal(root.get("starredFlag"), starred));
            if (hasAttachments != null)
                predicates.add(builder.equal(root.get("hasAttachments"), hasAttachments));
            predicates.add(builder.equal(root.get("trashedFlag"), trashed));
            if (query != null && !query.isBlank()) {
                String pattern = "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(
                    builder.or(builder.like(builder.lower(root.get("subject")), pattern),
                        builder.like(builder.lower(root.get("fromAddress")), pattern),
                        builder.like(builder.lower(root.get("textBody")), pattern)));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
        Sort sort = Sort.by(Sort.Order.desc("pinnedFlag"), Sort.Order.desc("pinnedAt"),
            Sort.Order.desc("receivedAt"), Sort.Order.desc("id"));
        var result =
            messages.findAll(specification, PageRequest.of(Math.max(page, 0), safeSize, sort));
        return new PageResponse<>(result.stream().map(this::summary).toList(), result.getNumber(),
            result.getSize(), result.getTotalElements(),
            result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public MessageDetail detail(Long ownerId, Long id) {
        MailMessage message = access.message(ownerId, id, MailAccessService.Action.VIEW);
        List<AttachmentResponse> attachmentResponses =
            attachments.findAllByMessageIdOrderByIdAsc(id)
                .stream()
                .map(item
                    -> new AttachmentResponse(item.getId(), item.getFilename(),
                    item.getContentType(), item.getSizeBytes(),
                    item.getScanStatus(), item.getScanDetail()))
                .toList();
        return new MessageDetail(
            message.getId(), message.getAccount().getId(), message.getAccount().getDisplayName(),
            message.getSubject(), message.getFromAddress(), message.getRecipients(),
            message.getTextBody(), message.getInternetMessageId(), message.getReferencesHeader(),
            message.getReceivedAt(), message.isReadFlag(), message.isStarredFlag(),
            message.isPinnedFlag(), attachmentResponses);
    }

    @Transactional(readOnly = true)
    public MailMessage get(Long ownerId, Long id) {
        return access.message(ownerId, id, MailAccessService.Action.VIEW);
    }

    @Transactional(readOnly = true)
    public Long readableOwner(Long actorId, Long accountId) {
        return access.account(actorId, accountId, MailAccessService.Action.VIEW).getOwner().getId();
    }

    MessageSummary summary(MailMessage message) {
        String body = message.getTextBody() == null
            ? ""
            : message.getTextBody().replaceAll("\\s+", " ").trim();
        String preview = body.length() <= 160 ? body : body.substring(0, 160) + "…";
        return new MessageSummary(message.getId(), message.getAccount().getId(),
            message.getAccount().getDisplayName(), message.getSubject(),
            message.getFromAddress(), preview, message.getReceivedAt(),
            message.isReadFlag(), message.isStarredFlag(),
            message.isPinnedFlag(), message.isHasAttachments());
    }
}
