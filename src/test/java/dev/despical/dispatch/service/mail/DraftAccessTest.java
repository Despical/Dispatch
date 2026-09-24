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

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.despical.dispatch.dto.mail.MailDtos.DraftRequest;
import dev.despical.dispatch.entity.mail.MailAccount;
import dev.despical.dispatch.entity.mail.OutboundMessage;
import dev.despical.dispatch.exception.ApiException;
import dev.despical.dispatch.repository.mail.MailAccountRepository;
import dev.despical.dispatch.repository.mail.OutboundAttachmentRepository;
import dev.despical.dispatch.repository.mail.OutboundMessageRepository;
import dev.despical.dispatch.security.RateLimitService;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * @author Despical
 * <p>
 * Created at 22.09.2026
 */
class DraftAccessTest {
    final OutboundMessageRepository outbox = mock(OutboundMessageRepository.class);
    final OutboundAttachmentRepository attachments = mock(OutboundAttachmentRepository.class);
    final dev.despical.dispatch.repository.mail.MailMessageRepository messages =
        mock(dev.despical.dispatch.repository.mail.MailMessageRepository.class);
    final MailAccessService access = mock(MailAccessService.class);
    final OutboundService service =
        new OutboundService(
            outbox,
            mock(MailAccountRepository.class),
            mock(MailConnectionFactory.class),
            new RateLimitService(),
            new SimpleMeterRegistry(),
            mock(PlatformTransactionManager.class),
            attachments,
            mock(OutboundAttachmentService.class),
            messages,
            access);

    @Test
    void listAndCountAreRestrictedToOwnedDrafts() {
        when(outbox.findDrafts(
            eq(42L),
            eq(OutboundMessage.Status.DRAFT),
            eq("hello"),
            any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of()));
        when(outbox.countByCreatedByIdAndStatusAndTrashedAtIsNull(
            42L, OutboundMessage.Status.DRAFT))
            .thenReturn(3L);
        assertThat(service.drafts(42L, 0, " hello ").getContent()).isEmpty();
        assertThat(service.draftCount(42L)).isEqualTo(3);
        verify(outbox)
            .findDrafts(
                eq(42L),
                eq(OutboundMessage.Status.DRAFT),
                eq("hello"),
                any(Pageable.class));
    }

    @Test
    void listAndCountCanBeScopedToOneOwnedAccount() {
        when(outbox.findDraftsForAccount(
            eq(42L), eq(5L), eq(OutboundMessage.Status.DRAFT), eq("hello"), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of()));
        when(outbox.countByCreatedByIdAndAccountIdAndStatusAndTrashedAtIsNull(
            42L, 5L, OutboundMessage.Status.DRAFT)).thenReturn(2L);

        assertThat(service.drafts(42L, 5L, 0, " hello ").getContent()).isEmpty();
        assertThat(service.draftCount(42L, 5L)).isEqualTo(2);
        verify(outbox).findDraftsForAccount(
            eq(42L), eq(5L), eq(OutboundMessage.Status.DRAFT), eq("hello"), any(Pageable.class));
        verify(outbox, never()).findDrafts(eq(42L), any(), any(), any());
    }

    @Test
    void foreignOrNonDraftMessagesCannotBeReopenedOrOverwritten() {
        UUID id = UUID.randomUUID();
        when(outbox.findByPublicIdAndCreatedById(id, 42L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.draft(42L, id)).isInstanceOf(ApiException.class);
        OutboundMessage sent = new OutboundMessage();
        sent.setId(1L);
        sent.setStatus(OutboundMessage.Status.SENT);
        when(outbox.findByPublicIdAndCreatedById(id, 42L)).thenReturn(Optional.of(sent));
        assertThatThrownBy(() -> service.draft(42L, id)).isInstanceOf(ApiException.class);
        assertThatThrownBy(
            () ->
                service.saveDraft(
                    42L,
                    id,
                    new DraftRequest(
                        null, List.of(), List.of(), List.of(), "changed",
                        "", "", null, null, null)))
            .isInstanceOf(ApiException.class);
        assertThat(sent.getStatus()).isEqualTo(OutboundMessage.Status.SENT);
        verify(outbox, never()).save(any());
    }

    @Test
    void reopeningAndSavingKeepsTheSameDraftAndReplyHeaders() {
        UUID id = UUID.randomUUID();
        MailAccount account = new MailAccount();
        account.setId(5L);
        account.setEmail("from@example.test");
        account.setDisplayName("Work");
        OutboundMessage draft = new OutboundMessage();
        draft.setId(7L);
        draft.setPublicId(id);
        draft.setStatus(OutboundMessage.Status.DRAFT);
        draft.setAccount(account);
        draft.setRecipients("to@example.test");
        draft.setCc("cc@example.test");
        draft.setBcc("bcc@example.test");
        draft.setBodyText("Original body");
        draft.setInReplyTo("<reply>");
        draft.setReferencesHeader("<thread>");
        when(outbox.findByPublicIdAndCreatedById(id, 42L)).thenReturn(Optional.of(draft));
        when(attachments.findAllByOutboundMessageIdOrderByIdAsc(7L)).thenReturn(List.of());
        when(outbox.save(draft)).thenReturn(draft);
        var detail = service.draft(42L, id);
        assertThat(detail.cc()).isEqualTo("cc@example.test");
        assertThat(detail.bcc()).isEqualTo("bcc@example.test");
        assertThat(detail.inReplyTo()).isEqualTo("<reply>");
        var result =
            service.saveDraft(
                42L,
                id,
                new DraftRequest(
                    5L,
                    List.of("to@example.test"),
                    List.of("cc@example.test"),
                    List.of("bcc@example.test"),
                    "Subject",
                    "",
                    "Edited body",
                    detail.inReplyTo(),
                    detail.referencesHeader(),
                    null));
        assertThat(result.id()).isEqualTo(id);
        assertThat(draft.getBodyText()).isEqualTo("Edited body");
        assertThat(draft.getReferencesHeader()).isEqualTo("<thread>");
    }

    @Test
    void trashPreventsReopeningSavingAndSendingAndRestorePreservesDraft() {
        UUID id = UUID.randomUUID();
        OutboundMessage draft = new OutboundMessage();
        draft.setId(7L);
        draft.setPublicId(id);
        draft.setStatus(OutboundMessage.Status.DRAFT);
        when(outbox.findByPublicIdAndCreatedById(id, 42L)).thenReturn(Optional.of(draft));
        service.trashDraft(42L, id, true);
        assertThat(draft.getTrashedAt()).isNotNull();
        assertThatThrownBy(() -> service.draft(42L, id)).isInstanceOf(ApiException.class);
        assertThatThrownBy(
            () ->
                service.saveDraft(
                    42L,
                    id,
                    new DraftRequest(
                        null, List.of(), List.of(), List.of(), "", "", "",
                        null, null, null)))
            .isInstanceOf(ApiException.class);
        assertThatThrownBy(
            () ->
                service.queue(
                    42L,
                    new dev.despical.dispatch.dto.mail.MailDtos.SendRequest(
                        id,
                        5L,
                        List.of("to@example.test"),
                        List.of(),
                        List.of(),
                        "",
                        "",
                        "",
                        null,
                        null,
                        "key")))
            .isInstanceOf(ApiException.class);
        service.trashDraft(42L, id, false);
        assertThat(draft.getTrashedAt()).isNull();
        assertThat(draft.getStatus()).isEqualTo(OutboundMessage.Status.DRAFT);
        assertThatThrownBy(() -> service.trashDraft(99L, id, true))
            .isInstanceOf(ApiException.class);
    }

    @Test
    void sourceMessageMustBelongToDraftOwner() {
        var draft = new OutboundMessage();
        draft.setId(7L);
        draft.setPublicId(UUID.randomUUID());
        draft.setStatus(OutboundMessage.Status.DRAFT);
        var account = new MailAccount();
        account.setId(5L);
        draft.setAccount(account);
        when(outbox.findByPublicIdAndCreatedById(draft.getPublicId(), 42L))
            .thenReturn(Optional.of(draft));
        var request =
            new DraftRequest(5L, List.of(), List.of(), List.of(), "", "", "", null, null, 101L);
        when(access.message(42L, 101L, MailAccessService.Action.VIEW))
            .thenThrow(
                new ApiException(
                    org.springframework.http.HttpStatus.NOT_FOUND,
                    "Message not found."));
        assertThatThrownBy(() -> service.saveDraft(42L, draft.getPublicId(), request))
            .isInstanceOf(ApiException.class);
        var source = new dev.despical.dispatch.entity.mail.MailMessage();
        source.setId(101L);
        doReturn(source).when(access).message(42L, 101L, MailAccessService.Action.VIEW);
        when(outbox.save(draft)).thenReturn(draft);
        service.saveDraft(42L, draft.getPublicId(), request);
        assertThat(service.draft(42L, draft.getPublicId()).sourceMessageId()).isEqualTo(101L);
    }
}
