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
import static org.mockito.Mockito.*;

import dev.despical.dispatch.entity.mail.*;
import dev.despical.dispatch.entity.security.AdminUser;
import dev.despical.dispatch.exception.ApiException;
import dev.despical.dispatch.repository.mail.*;
import dev.despical.dispatch.repository.security.AdminUserRepository;

import org.junit.jupiter.api.Test;

import java.util.*;

/**
 * @author Despical
 * <p>
 * Created at 22.09.2026
 */
class MailSharingAccessTest {
    final MailShareRepository shares = mock(MailShareRepository.class);
    final MailAccountRepository accounts = mock(MailAccountRepository.class);
    final MailMessageRepository messages = mock(MailMessageRepository.class);
    final AttachmentRepository attachments = mock(AttachmentRepository.class);
    final AdminUserRepository admins = mock(AdminUserRepository.class);
    final MailAccessService access =
        new MailAccessService(shares, accounts, messages, attachments, admins);
    final MailSharingService sharing =
        new MailSharingService(shares, admins, accounts, mock(MailFolderRepository.class));
    final AdminUser owner = user(1L, "owner@example.test"),
        viewer = user(2L, "viewer@example.test");
    final MailAccount account = new MailAccount();
    final MailShare grant = new MailShare();
    final MailMessage message = new MailMessage();

    MailSharingAccessTest() {
        account.setId(10L);
        account.setOwner(owner);
        account.setActive(true);
        grant.setId(20L);
        grant.setOwner(owner);
        grant.setViewer(viewer);
        grant.getAccounts().add(account);
        message.setId(30L);
        message.setAccount(account);
        when(accounts.findAllByOwnerIdAndActiveTrueOrderByDisplayOrderAscDisplayNameAscIdAsc(1L))
            .thenReturn(List.of(account));
        when(accounts.findById(10L)).thenReturn(Optional.of(account));
        when(messages.findById(30L)).thenReturn(Optional.of(message));
        when(shares.findByOwnerIdAndViewerId(1L, 2L)).thenReturn(Optional.of(grant));
    }

    static AdminUser user(Long id, String email) {
        var u = new AdminUser();
        u.setId(id);
        u.setEmail(email);
        u.setDisplayName(email);
        return u;
    }

    @Test
    void viewIsTheOnlyDefaultPermission() {
        assertThat(access.message(2L, 30L, MailAccessService.Action.VIEW)).isSameAs(message);
        for (var action :
            List.of(
                MailAccessService.Action.SEND,
                MailAccessService.Action.ORGANIZE,
                MailAccessService.Action.DELETE))
            assertThatThrownBy(() -> access.account(2L, 10L, action))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void eachPermissionIsIndependent() {
        grant.setCanSend(true);
        access.account(2L, 10L, MailAccessService.Action.SEND);
        assertThatThrownBy(() -> access.message(2L, 30L, MailAccessService.Action.DELETE))
            .isInstanceOf(ApiException.class);
        grant.setCanOrganize(true);
        access.message(2L, 30L, MailAccessService.Action.ORGANIZE);
        grant.setCanDelete(true);
        access.message(2L, 30L, MailAccessService.Action.DELETE);
    }

    @Test
    void revokedHiddenDisabledAndRemovedAccountsCannotBeRead() {
        grant.setHidden(true);
        assertThatThrownBy(() -> access.message(2L, 30L, MailAccessService.Action.VIEW))
            .isInstanceOf(ApiException.class);
        grant.setHidden(false);
        owner.setEnabled(false);
        assertThatThrownBy(() -> access.message(2L, 30L, MailAccessService.Action.VIEW))
            .isInstanceOf(ApiException.class);
        owner.setEnabled(true);
        account.setActive(false);
        assertThatThrownBy(() -> access.message(2L, 30L, MailAccessService.Action.VIEW))
            .isInstanceOf(ApiException.class);
        account.setActive(true);
        when(shares.findByOwnerIdAndViewerId(1L, 2L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> access.message(2L, 30L, MailAccessService.Action.VIEW))
            .isInstanceOf(ApiException.class);
    }

    @Test
    void unrelatedUserAndAttachmentIdsDoNotBypassTheGrant() {
        var file = new Attachment();
        file.setMessage(message);
        when(attachments.findById(40L)).thenReturn(Optional.of(file));
        assertThat(access.attachment(2L, 40L)).isSameAs(file);
        assertThatThrownBy(() -> access.attachment(3L, 40L)).isInstanceOf(ApiException.class);
    }

    @Test
    void sharedTrashNeedsDeletePermissionEvenToRead() {
        message.setTrashedFlag(true);
        assertThatThrownBy(() -> access.message(2L, 30L, MailAccessService.Action.VIEW))
            .isInstanceOf(ApiException.class);
        grant.setCanDelete(true);
        assertThat(access.message(2L, 30L, MailAccessService.Action.VIEW)).isSameAs(message);
        access.message(2L, 30L, MailAccessService.Action.DELETE);
    }

    @Test
    void ownerKeepsAccessWithoutAnyGrant() {
        for (var action : MailAccessService.Action.values()) access.account(1L, 10L, action);
        verify(shares, never()).findByOwnerIdAndViewerId(any(), any());
    }

    @Test
    void addingRequiresActiveExistingOtherUserAndPreventsDuplicates() {
        when(admins.findByIdForUpdate(1L)).thenReturn(Optional.of(owner));
        when(admins.findByEmailIgnoreCase(viewer.getEmail())).thenReturn(Optional.of(viewer));
        var request =
            new MailSharingService.Request(
                viewer.getEmail(), true, false, false, false, List.of(10L));
        assertThatThrownBy(() -> sharing.add(1L, request)).isInstanceOf(ApiException.class);
        when(shares.findByOwnerIdAndViewerId(1L, 2L)).thenReturn(Optional.empty());
        sharing.add(1L, request);
        verify(shares)
            .save(
                argThat(
                    s ->
                        s.getOwner() == owner
                            && s.getViewer() == viewer
                            && !s.isCanSend()
                            && !s.isCanOrganize()
                            && !s.isCanDelete()));
        when(admins.findByEmailIgnoreCase(owner.getEmail())).thenReturn(Optional.of(owner));
        assertThatThrownBy(
            () ->
                sharing.add(
                    1L,
                    new MailSharingService.Request(
                        owner.getEmail(),
                        true,
                        false,
                        false,
                        false,
                        List.of(10L))))
            .isInstanceOf(ApiException.class);
        viewer.setEnabled(false);
        assertThatThrownBy(() -> sharing.add(1L, request)).isInstanceOf(ApiException.class);
    }

    @Test
    void onlyOwnerCanChangeGrantsAndOnlyRecipientCanHide() {
        assertThatThrownBy(
            () ->
                sharing.update(
                    3L,
                    20L,
                    new MailSharingService.Permissions(
                        true, true, true, true, List.of(10L))))
            .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> sharing.remove(3L, 20L)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> sharing.hide(1L, 20L, true)).isInstanceOf(ApiException.class);
        when(shares.findByIdAndViewerId(20L, 2L)).thenReturn(Optional.of(grant));
        sharing.hide(2L, 20L, true);
        assertThat(grant.isHidden()).isTrue();
        when(shares.findByIdAndOwnerId(20L, 1L)).thenReturn(Optional.of(grant));
        sharing.update(
            1L, 20L, new MailSharingService.Permissions(true, true, false, true, List.of(10L)));
        assertThat(grant.isHidden()).isTrue();
        assertThat(grant.isCanSend()).isTrue();
        assertThat(grant.isCanOrganize()).isFalse();
        sharing.remove(1L, 20L);
        verify(shares).delete(grant);
    }

    @Test
    void delegatedSendingStoresTheComposingUserAndRequiresSendPermission() {
        var outbox = mock(OutboundMessageRepository.class);
        when(outbox.save(any())).thenAnswer(call -> call.getArgument(0));
        when(admins.findById(2L)).thenReturn(Optional.of(viewer));
        var outbound =
            new OutboundService(
                outbox,
                accounts,
                mock(MailConnectionFactory.class),
                new dev.despical.dispatch.security.RateLimitService(),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                mock(org.springframework.transaction.PlatformTransactionManager.class),
                mock(OutboundAttachmentRepository.class),
                mock(OutboundAttachmentService.class),
                messages,
                access);
        var request =
            new dev.despical.dispatch.dto.mail.MailDtos.SendRequest(
                null,
                10L,
                List.of("recipient@example.test"),
                List.of(),
                List.of(),
                "Subject",
                "",
                "Body",
                null,
                null,
                "delegated-send-test");
        assertThatThrownBy(() -> outbound.queue(2L, request)).isInstanceOf(ApiException.class);
        grant.setCanSend(true);
        outbound.queue(2L, request);
        verify(outbox)
            .save(
                argThat(
                    m ->
                        m.getAccount() == account
                            && m.getCreatedBy() == viewer
                            && m.getStatus() == OutboundMessage.Status.QUEUED));
        verify(outbox, times(2)).findByIdempotencyKeyAndCreatedById("delegated-send-test", 2L);
    }

    @Test
    void viewingCanBeRemovedWithoutGrantingAccessThroughOtherActions() {
        grant.setCanView(false);
        grant.setCanSend(true);
        grant.setCanOrganize(true);
        grant.setCanDelete(true);
        for (var action :
            List.of(
                MailAccessService.Action.VIEW,
                MailAccessService.Action.ORGANIZE,
                MailAccessService.Action.DELETE))
            assertThatThrownBy(() -> access.message(2L, 30L, action))
                .isInstanceOf(ApiException.class);
        access.account(2L, 10L, MailAccessService.Action.SEND);
        var file = new Attachment();
        file.setMessage(message);
        when(attachments.findById(40L)).thenReturn(Optional.of(file));
        assertThatThrownBy(() -> access.attachment(2L, 40L)).isInstanceOf(ApiException.class);
    }

    @Test
    void unselectedMailboxesCannotBeReadSentFromOrAddedViaForgedIds() {
        var other = new MailAccount();
        other.setId(11L);
        other.setOwner(owner);
        other.setActive(true);
        when(accounts.findById(11L)).thenReturn(Optional.of(other));
        grant.setCanSend(true);
        assertThatThrownBy(() -> access.account(2L, 11L, MailAccessService.Action.VIEW))
            .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> access.account(2L, 11L, MailAccessService.Action.SEND))
            .isInstanceOf(ApiException.class);
        when(shares.findByIdAndOwnerId(20L, 1L)).thenReturn(Optional.of(grant));
        assertThatThrownBy(
            () ->
                sharing.update(
                    1L,
                    20L,
                    new MailSharingService.Permissions(
                        true, false, false, false, List.of(999L))))
            .isInstanceOf(ApiException.class);
        assertThatThrownBy(
            () ->
                sharing.update(
                    1L,
                    20L,
                    new MailSharingService.Permissions(
                        true, false, false, false, List.of())))
            .isInstanceOf(ApiException.class);
        assertThat(grant.getAccounts()).containsExactly(account);
    }

    @Test
    void mailboxSpecificPermissionOverridesTheGrantWideDefaults() {
        var permissionStore = mock(MailSharePermissionStore.class);
        var scoped =
            new MailAccessService(
                shares, accounts, messages, attachments, admins, permissionStore);
        grant.setCanView(true);
        grant.setCanSend(true);
        when(permissionStore.get(20L, 10L))
            .thenReturn(
                new MailSharePermissionStore.AccountPermission(
                    10L, true, false, false, false));
        assertThat(scoped.account(2L, 10L, MailAccessService.Action.VIEW)).isSameAs(account);
        assertThatThrownBy(() -> scoped.account(2L, 10L, MailAccessService.Action.SEND))
            .isInstanceOf(ApiException.class);
        when(permissionStore.get(20L, 10L)).thenReturn(null);
        assertThatThrownBy(() -> scoped.account(2L, 10L, MailAccessService.Action.VIEW))
            .isInstanceOf(ApiException.class);
    }
}
