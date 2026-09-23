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
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.despical.dispatch.entity.mail.MailAccount;
import dev.despical.dispatch.entity.mail.MailFolder;
import dev.despical.dispatch.entity.mail.MailMessage;
import dev.despical.dispatch.repository.mail.MailFolderRepository;
import dev.despical.dispatch.repository.mail.MailMessageRepository;

import jakarta.mail.Folder;
import jakarta.mail.Message;

import org.eclipse.angus.mail.imap.AppendUID;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.eclipse.angus.mail.imap.IMAPStore;
import org.junit.jupiter.api.Test;

import java.util.Optional;

/**
 * @author Despical
 * <p>
 * Created at 21.09.2026
 */
class MessageFlagServiceTest {

    private static final long OWNER_ID = 1L;

    private final MailMessageRepository messages = mock(MailMessageRepository.class);
    private final MailAccessService access = mock(MailAccessService.class);
    private final MailConnectionFactory connections = mock(MailConnectionFactory.class);
    private final MailFolderRepository folders = mock(MailFolderRepository.class);
    private final GmailMessageService gmail = mock(GmailMessageService.class);
    private final MessageFlagService service =
        new MessageFlagService(access, messages, connections, folders, gmail);

    @Test
    void gmailTrashUsesProviderIdentityAndKeepsLocalMessageUntilSync() {
        var account = new MailAccount();
        account.setId(5L);
        account.setAuthProvider("GOOGLE");
        var inbox = new MailFolder();
        inbox.setFullName("[Gmail]/All Mail");
        var message = new MailMessage();
        message.setAccount(account);
        message.setFolder(inbox);
        message.setUidValidity(10L);
        message.setImapUid(22L);
        when(access.message(eq(OWNER_ID), eq(50L), any())).thenReturn(message);
        when(gmail.move(message, true)).thenReturn(
            new GmailMessageService.MoveResult("abc123", true, "INBOX,CATEGORY_SOCIAL", false));

        service.setTrashed(OWNER_ID, 50L, true);

        verify(gmail).move(message, true);
        assertThat(message.isTrashedFlag()).isTrue();
        assertThat(message.getTrashOriginLabels()).isEqualTo("INBOX,CATEGORY_SOCIAL");
        assertThat(message.getFolder()).isSameAs(inbox);
        assertThat(message.getGmailMessageId()).isEqualTo("abc123");
    }

    @Test
    void restoringAlreadyRestoredGmailTrashReconcilesWithoutImapMove() {
        var account = new MailAccount();
        account.setId(5L);
        account.setAuthProvider("GOOGLE");
        var trash = new MailFolder();
        trash.setFullName("[Gmail]/Trash");
        trash.setSpecialUse("TRASH");
        var message = new MailMessage();
        message.setAccount(account);
        message.setFolder(trash);
        message.setUidValidity(30L);
        message.setImapUid(99L);
        message.setTrashedFlag(true);
        message.setTrashOriginFolder("[Gmail]/All Mail");
        when(access.message(eq(OWNER_ID), eq(50L), any())).thenReturn(message);
        when(gmail.move(message, false)).thenReturn(
            new GmailMessageService.MoveResult("abc123", false, "INBOX", false));

        service.setTrashed(OWNER_ID, 50L, false);

        verify(gmail).move(message, false);
        assertThat(message.isTrashedFlag()).isFalse();
        assertThat(message.getFolder()).isSameAs(trash);
        assertThat(message.getTrashOriginLabels()).isNull();
    }

    @Test
    void pinningAndTrashAreIndependentLocalStates() throws Exception {
        MailMessage message = new MailMessage();
        var account = new MailAccount();
        account.setAuthProvider("PASSWORD");
        message.setAccount(account);
        var folder = new MailFolder();
        folder.setFullName("INBOX");
        message.setFolder(folder);
        var store = mock(IMAPStore.class);
        when(connections.openImap(account)).thenReturn(store);
        when(access.message(
            org.mockito.ArgumentMatchers.eq(OWNER_ID),
            org.mockito.ArgumentMatchers.eq(7L),
            org.mockito.ArgumentMatchers.any()))
            .thenReturn(message);

        service.setPinned(OWNER_ID, 7L, true);
        service.setTrashed(OWNER_ID, 7L, true);

        assertThat(message.isPinnedFlag()).isTrue();
        assertThat(message.getPinnedAt()).isNotNull();
        assertThat(message.isTrashedFlag()).isTrue();
        assertThat(message.isStarredFlag()).isFalse();
    }

    @Test
    void starringPersistsLocallyWhenRemoteImapIsUnavailable() {
        MailMessage message = new MailMessage();
        when(access.message(
            org.mockito.ArgumentMatchers.eq(OWNER_ID),
            org.mockito.ArgumentMatchers.eq(8L),
            org.mockito.ArgumentMatchers.any()))
            .thenReturn(message);

        service.setStarred(OWNER_ID, 8L, true);

        assertThat(message.isStarredFlag()).isTrue();
    }

    @Test
    void readStatePersistsLocallyWhenRemoteImapIsUnavailable() {
        MailMessage message = new MailMessage();
        when(access.message(
            org.mockito.ArgumentMatchers.eq(OWNER_ID),
            org.mockito.ArgumentMatchers.eq(11L),
            org.mockito.ArgumentMatchers.any()))
            .thenReturn(message);

        service.setRead(OWNER_ID, 11L, true);

        assertThat(message.isReadFlag()).isTrue();
    }

    @Test
    void restoringFromTrashKeepsThePinAndStarStateUntouched() {
        MailMessage message = new MailMessage();
        message.setAccount(new MailAccount());
        var folder = new MailFolder();
        folder.setFullName("INBOX");
        message.setFolder(folder);
        message.setPinnedFlag(true);
        message.setStarredFlag(true);
        message.setTrashedFlag(true);
        when(access.message(
            org.mockito.ArgumentMatchers.eq(OWNER_ID),
            org.mockito.ArgumentMatchers.eq(9L),
            org.mockito.ArgumentMatchers.any()))
            .thenReturn(message);

        service.setTrashed(OWNER_ID, 9L, false);

        assertThat(message.isTrashedFlag()).isFalse();
        assertThat(message.isPinnedFlag()).isTrue();
        assertThat(message.isStarredFlag()).isTrue();
    }

    @Test
    void unpinningClearsThePinTimestamp() {
        MailMessage message = new MailMessage();
        message.setPinnedFlag(true);
        message.setPinnedAt(java.time.Instant.parse("2026-09-21T12:00:00Z"));
        when(access.message(
            org.mockito.ArgumentMatchers.eq(OWNER_ID),
            org.mockito.ArgumentMatchers.eq(10L),
            org.mockito.ArgumentMatchers.any()))
            .thenReturn(message);

        service.setPinned(OWNER_ID, 10L, false);

        assertThat(message.isPinnedFlag()).isFalse();
        assertThat(message.getPinnedAt()).isNull();
    }

    @Test
    void anotherOwnersMessageIsNotAddressable() {
        when(access.message(99L, 11L, MailAccessService.Action.ORGANIZE))
            .thenThrow(
                new dev.despical.dispatch.exception.ApiException(
                    org.springframework.http.HttpStatus.NOT_FOUND,
                    "Message not found."));
        assertThatThrownBy(() -> service.setRead(99L, 11L, true))
            .isInstanceOf(dev.despical.dispatch.exception.ApiException.class)
            .hasMessage("Message not found.");
    }
}
