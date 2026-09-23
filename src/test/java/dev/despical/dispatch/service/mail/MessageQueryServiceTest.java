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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.despical.dispatch.repository.mail.AttachmentRepository;
import dev.despical.dispatch.repository.mail.MailMessageRepository;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;

/**
 * @author Despical
 * <p>
 * Created at 21.09.2026
 */
class MessageQueryServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void newestPinIsSortedBeforeOlderPinsAndRegularMessages() {
        MailMessageRepository messages = mock(MailMessageRepository.class);
        when(messages.findAll(any(Specification.class), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of()));
        MessageQueryService service =
            new MessageQueryService(
                mock(MailAccessService.class), messages, mock(AttachmentRepository.class));

        service.list(1L, 0, 30, null, null, null, null, null, null, false);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(messages).findAll(any(Specification.class), pageable.capture());
        assertThat(
            pageable.getValue().getSort().stream()
                .map(order -> order.getProperty())
                .toList())
            .containsExactly("pinnedFlag", "pinnedAt", "receivedAt", "id");
        assertThat(
            pageable.getValue().getSort().stream()
                .allMatch(order -> order.getDirection().isDescending()))
            .isTrue();
    }
}
