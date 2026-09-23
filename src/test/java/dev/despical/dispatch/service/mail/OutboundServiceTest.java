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
import static org.mockito.Mockito.when;

import dev.despical.dispatch.entity.mail.MailAccount;
import dev.despical.dispatch.entity.mail.OutboundMessage;
import dev.despical.dispatch.repository.mail.MailAccountRepository;
import dev.despical.dispatch.repository.mail.OutboundAttachmentRepository;
import dev.despical.dispatch.repository.mail.OutboundMessageRepository;
import dev.despical.dispatch.security.RateLimitService;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.Optional;
import java.util.UUID;

/**
 * @author Despical
 * <p>
 * Created at 21.09.2026
 */
class OutboundServiceTest {

    @Test
    void marksConnectionFailureAsDefinitelyNotSent() throws Exception {
        OutboundMessageRepository outbox = mock(OutboundMessageRepository.class);
        MailConnectionFactory connections = mock(MailConnectionFactory.class);
        PlatformTransactionManager transactions = transactionManager();
        MailAccount account = new MailAccount();
        account.setId(1L);
        account.setActive(true);
        account.setEmail("from@example.test");
        account.setDisplayName("From");
        account.setOwner(new dev.despical.dispatch.entity.security.AdminUser());
        OutboundMessage outbound = new OutboundMessage();
        outbound.setId(7L);
        outbound.setPublicId(UUID.randomUUID());
        outbound.setAccount(account);
        outbound.setCreatedBy(account.getOwner());
        outbound.setStatus(OutboundMessage.Status.QUEUED);
        outbound.setRecipients("to@example.test");
        outbound.setSubject("Test");
        outbound.setBodyText("Body");
        when(outbox.findById(7L)).thenReturn(Optional.of(outbound));
        when(outbox.findForDelivery(7L)).thenReturn(Optional.of(outbound));
        OutboundAttachmentRepository attachmentRepository =
            mock(OutboundAttachmentRepository.class);
        when(attachmentRepository.findAllByOutboundMessageIdOrderByIdAsc(7L))
            .thenReturn(java.util.List.of());
        when(connections.smtpSession(account))
            .thenReturn(jakarta.mail.Session.getInstance(new java.util.Properties()));
        when(connections.openSmtp(account))
            .thenThrow(new jakarta.mail.MessagingException("connection dropped"));
        OutboundService service =
            new OutboundService(
                outbox,
                mock(MailAccountRepository.class),
                connections,
                new RateLimitService(),
                new SimpleMeterRegistry(),
                transactions,
                attachmentRepository,
                mock(OutboundAttachmentService.class),
                mock(dev.despical.dispatch.repository.mail.MailMessageRepository.class),
                mock(MailAccessService.class));

        service.send(7L);

        assertThat(outbound.getStatus()).isEqualTo(OutboundMessage.Status.FAILED);
        assertThat(outbound.getFailureReason()).contains("No message was sent");
    }

    private PlatformTransactionManager transactionManager() {
        return new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
            }

            @Override
            public void rollback(TransactionStatus status) {
            }
        };
    }
}
