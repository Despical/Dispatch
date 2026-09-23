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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.despical.dispatch.mapper.MailAccountMapper;
import dev.despical.dispatch.repository.mail.MailAccountRepository;
import dev.despical.dispatch.repository.security.AdminUserRepository;
import dev.despical.dispatch.security.CryptoService;

import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * @author Despical
 * <p>
 * Created at 21.09.2026
 */
class MailAccountOwnershipTest {

    @Test
    void accountListingUsesTheSignedInOwner() {
        MailAccountRepository accounts = mock(MailAccountRepository.class);
        when(accounts.findAllByOwnerIdAndActiveTrueOrderByDisplayOrderAscDisplayNameAscIdAsc(42L))
            .thenReturn(List.of());
        MailAccountService service =
            new MailAccountService(
                accounts,
                mock(MailAccountMapper.class),
                mock(CryptoService.class),
                mock(HostPolicy.class),
                mock(MailConnectionFactory.class),
                mock(HtmlSanitizerService.class),
                mock(AdminUserRepository.class));

        assertThat(service.list(42L)).isEmpty();
        verify(accounts)
            .findAllByOwnerIdAndActiveTrueOrderByDisplayOrderAscDisplayNameAscIdAsc(42L);
    }
}
