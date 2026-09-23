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
package dev.despical.dispatch.service.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.despical.dispatch.entity.security.AdminUser;
import dev.despical.dispatch.exception.ApiException;
import dev.despical.dispatch.repository.mail.*;
import dev.despical.dispatch.repository.security.AdminUserRepository;
import dev.despical.dispatch.service.mail.*;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;

/**
 * @author Despical
 * <p>
 * Created at 22.09.2026
 */
class AdminLifecycleTest {
    final AdminUserRepository users = mock(AdminUserRepository.class);
    final OutboundMessageRepository outbox = mock(OutboundMessageRepository.class);
    final AdminLifecycleService service =
        new AdminLifecycleService(
            users,
            mock(AttachmentRepository.class),
            mock(OutboundAttachmentRepository.class),
            mock(AttachmentService.class),
            mock(OutboundAttachmentService.class),
            outbox,
            mock(SecurityEventService.class));

    @Test
    void ownAndEnabledAccountsCannotBeDeleted() {
        assertThatThrownBy(() -> service.delete(42L, 42L, "127.0.0.1"))
            .isInstanceOf(ApiException.class);
        when(users.findByIdForUpdate(7L)).thenReturn(Optional.of(new AdminUser()));
        assertThatThrownBy(() -> service.delete(7L, 42L, "127.0.0.1"))
            .isInstanceOf(ApiException.class);
        verify(users, never()).delete(any());
    }

    @Test
    void enablePreservesIncompleteEnrollment() {
        var user = new AdminUser();
        user.setEnabled(false);
        user.setPasswordChangeRequired(true);
        when(users.findByIdForUpdate(7L)).thenReturn(Optional.of(user));
        service.enable(7L, 42L, "127.0.0.1");
        assertThat(user.isEnabled()).isTrue();
        assertThat(user.isTotpEnabled()).isFalse();
        assertThat(user.isPasswordChangeRequired()).isTrue();
    }

    @Test
    void deletesDisabledAccountAndDefersFileCleanupUntilCommit() {
        var user = new AdminUser();
        user.setEnabled(false);
        when(users.findByIdForUpdate(7L)).thenReturn(Optional.of(user));
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.delete(7L, 42L, "127.0.0.1");
            verify(users).delete(user);
            assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
