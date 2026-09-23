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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.despical.dispatch.entity.mail.MailAccount;
import dev.despical.dispatch.exception.ApiException;
import dev.despical.dispatch.mapper.MailAccountMapper;
import dev.despical.dispatch.repository.mail.MailAccountRepository;
import dev.despical.dispatch.repository.security.AdminUserRepository;
import dev.despical.dispatch.security.CryptoService;

import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * @author Despical
 * <p>
 * Created at 22.09.2026
 */
class MailAccountOrderTest {
    private final MailAccountRepository accounts = mock(MailAccountRepository.class);
    private final MailAccountService service =
        new MailAccountService(
            accounts,
            mock(MailAccountMapper.class),
            mock(CryptoService.class),
            mock(HostPolicy.class),
            mock(MailConnectionFactory.class),
            mock(HtmlSanitizerService.class),
            mock(AdminUserRepository.class));

    @Test
    void assignsPositionsToTheOwnersAccountsInTheRequestedOrder() {
        MailAccount first = account(11L), second = account(12L), third = account(13L);
        when(accounts.findAllByOwnerIdAndActiveTrueOrderByDisplayOrderAscDisplayNameAscIdAsc(42L))
            .thenReturn(List.of(first, second, third));

        service.reorder(42L, List.of(13L, 11L, 12L));

        assertThat(third.getDisplayOrder()).isZero();
        assertThat(first.getDisplayOrder()).isEqualTo(1);
        assertThat(second.getDisplayOrder()).isEqualTo(2);
    }

    @Test
    void rejectsForeignMissingAndDuplicateIdsWithoutChangingAnyPositions() {
        MailAccount first = account(11L), second = account(12L);
        when(accounts.findAllByOwnerIdAndActiveTrueOrderByDisplayOrderAscDisplayNameAscIdAsc(42L))
            .thenReturn(List.of(first, second));

        for (List<Long> invalid :
            List.of(
                List.of(11L, 99L),
                List.of(11L),
                List.of(11L, 11L),
                List.of(11L, 12L, 99L))) {
            assertThatThrownBy(() -> service.reorder(42L, invalid))
                .isInstanceOf(ApiException.class);
            assertThat(first.getDisplayOrder()).isEqualTo(Integer.MAX_VALUE);
            assertThat(second.getDisplayOrder()).isEqualTo(Integer.MAX_VALUE);
        }
    }

    private MailAccount account(Long id) {
        MailAccount account = new MailAccount();
        account.setId(id);
        return account;
    }
}
