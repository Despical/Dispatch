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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import dev.despical.dispatch.entity.mail.MailAccount;
import dev.despical.dispatch.entity.mail.MailMessage;
import dev.despical.dispatch.entity.mail.OutboundMessage;
import dev.despical.dispatch.entity.security.AdminUser;
import dev.despical.dispatch.repository.mail.*;

import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * @author Despical
 * <p>
 * Created at 22.09.2026
 */
class TrashServiceTest {
    @Test
    void accountTrashIsScopedToAnOwnedMailbox() {
        var messages = mock(MailMessageRepository.class);
        var drafts = mock(OutboundMessageRepository.class);
        var query = mock(TrashQueryRepository.class);
        var accounts = mock(MailAccountRepository.class);
        var access = mock(MailAccessService.class);
        var service =
            new TrashService(
                messages,
                mock(AttachmentService.class),
                drafts,
                mock(OutboundAttachmentService.class),
                query,
                mock(MessageQueryService.class),
                accounts,
                access,
                mock(GmailMessageService.class));
        when(accounts.findByIdAndOwnerId(7L, 42L))
            .thenReturn(java.util.Optional.of(new MailAccount()));
        when(query.count(42L, 42L, 7L)).thenReturn(3L);
        assertThat(service.count(42L, 7L)).isEqualTo(3);
        verify(query).count(42L, 42L, 7L);
        assertThat(service.emptyTrash(42L, 7L)).isZero();
        verify(messages).findAllByAccountIdAndAccountOwnerIdAndTrashedFlagTrue(7L, 42L);
        verify(drafts)
            .findAllByAccountIdAndCreatedByIdAndStatusAndTrashedAtIsNotNull(
                7L, 42L, OutboundMessage.Status.DRAFT);
        when(access.account(42L, 8L, MailAccessService.Action.DELETE))
            .thenThrow(
                new dev.despical.dispatch.exception.ApiException(
                    org.springframework.http.HttpStatus.NOT_FOUND,
                    "Mail account not found."));
        assertThatThrownBy(() -> service.count(42L, 8L))
            .isInstanceOf(dev.despical.dispatch.exception.ApiException.class);
        verify(query, never()).count(42L, 42L, 8L);
    }

    @Test
    void sharedTrashRequiresDeletePermissionAndUsesTheMailboxesOwner() {
        var query = mock(TrashQueryRepository.class);
        var accounts = mock(MailAccountRepository.class);
        var access = mock(MailAccessService.class);
        var service =
            new TrashService(
                mock(MailMessageRepository.class),
                mock(AttachmentService.class),
                mock(OutboundMessageRepository.class),
                mock(OutboundAttachmentService.class),
                query,
                mock(MessageQueryService.class),
                accounts,
                access,
                mock(GmailMessageService.class));
        var owner = new AdminUser();
        owner.setId(99L);
        var account = new MailAccount();
        account.setOwner(owner);
        when(access.account(42L, 8L, MailAccessService.Action.DELETE)).thenReturn(account);
        when(query.count(42L, 99L, 8L)).thenReturn(4L);
        assertThat(service.count(42L, 8L)).isEqualTo(4);
        verify(access).account(42L, 8L, MailAccessService.Action.DELETE);
        verify(query).count(42L, 99L, 8L);
    }

    @Test
    void totalAndEmptyTrashIncludeOnlyTheOwnersMessagesAndDrafts() {
        var messages = mock(MailMessageRepository.class);
        var attachments = mock(AttachmentService.class);
        var drafts = mock(OutboundMessageRepository.class);
        var draftAttachments = mock(OutboundAttachmentService.class);
        var query = mock(TrashQueryRepository.class);
        var service =
            new TrashService(
                messages,
                attachments,
                drafts,
                draftAttachments,
                query,
                mock(MessageQueryService.class),
                mock(MailAccountRepository.class),
                mock(MailAccessService.class),
                mock(GmailMessageService.class));
        var message = new MailMessage();
        message.setId(1L);
        message.setAccount(new MailAccount());
        var draft = new OutboundMessage();
        draft.setId(2L);
        when(query.count(42L, 42L, null)).thenReturn(2L);
        when(messages.findAllByAccountOwnerIdAndTrashedFlagTrue(42L)).thenReturn(List.of(message));
        when(drafts.findAllByCreatedByIdAndStatusAndTrashedAtIsNotNull(
            42L, OutboundMessage.Status.DRAFT))
            .thenReturn(List.of(draft));
        assertThat(service.count(42L, null)).isEqualTo(2);
        assertThat(service.emptyTrash(42L, null)).isEqualTo(2);
        verify(attachments).deleteFilesForMessages(List.of(1L));
        verify(draftAttachments).deleteFilesForDraft(2L);
        verify(messages).deleteAllInBatch(List.of(message));
        verify(drafts).deleteAllInBatch(List.of(draft));
        assertThat(service.emptyTrash(99L, null)).isZero();
    }
}
