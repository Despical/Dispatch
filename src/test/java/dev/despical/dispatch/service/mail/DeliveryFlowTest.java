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

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;

import dev.despical.dispatch.entity.mail.*;
import dev.despical.dispatch.entity.security.AdminUser;
import dev.despical.dispatch.repository.mail.*;
import dev.despical.dispatch.security.RateLimitService;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import jakarta.mail.*;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.*;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.*;

/**
 * @author Despical
 * <p>
 * Created at 22.09.2026
 */
class DeliveryFlowTest {
    final OutboundMessageRepository outbox = mock(OutboundMessageRepository.class);
    final MailAccessService access = mock(MailAccessService.class);
    final MailConnectionFactory connections = mock(MailConnectionFactory.class);
    final OutboundAttachmentRepository attachments = mock(OutboundAttachmentRepository.class);
    final MailAccount account = new MailAccount();
    final OutboundMessage message = new OutboundMessage();
    final OutboundService service;

    DeliveryFlowTest() {
        account.setOwner(new AdminUser());
        account.setId(1L);
        account.setActive(true);
        account.setEmail("from@example.test");
        account.setDisplayName("Sender");
        message.setId(7L);
        message.setPublicId(UUID.randomUUID());
        message.setAccount(account);
        message.setCreatedBy(account.getOwner());
        message.setStatus(OutboundMessage.Status.QUEUED);
        message.setRecipients("to@example.test");
        message.setSubject("Local delivery check");
        message.setBodyText("Example body");
        when(outbox.findForDelivery(7L)).thenReturn(Optional.of(message));
        when(outbox.findById(7L)).thenReturn(Optional.of(message));
        when(attachments.findAllByOutboundMessageIdOrderByIdAsc(7L)).thenReturn(List.of());
        when(connections.smtpSession(account)).thenReturn(Session.getInstance(new Properties()));
        PlatformTransactionManager transactions =
            new PlatformTransactionManager() {
                public TransactionStatus getTransaction(TransactionDefinition definition) {
                    return new SimpleTransactionStatus();
                }

                public void commit(TransactionStatus status) {
                }

                public void rollback(TransactionStatus status) {
                }
            };
        service =
            new OutboundService(
                outbox,
                mock(MailAccountRepository.class),
                connections,
                new RateLimitService(),
                new SimpleMeterRegistry(),
                transactions,
                attachments,
                mock(OutboundAttachmentService.class),
                mock(MailMessageRepository.class),
                access);
    }

    @Test
    void failureDuringSubmissionIsUncertainAndNeverAutomaticallyRetried() throws Exception {
        Transport transport = mock(Transport.class);
        when(connections.openSmtp(account)).thenReturn(transport);
        doThrow(new MessagingException("lost acknowledgement"))
            .when(transport)
            .sendMessage(any(), any());
        service.send(7L);
        service.send(7L);
        assertThat(message.getStatus()).isEqualTo(OutboundMessage.Status.UNCERTAIN);
        verify(transport, times(1)).sendMessage(any(), any());
    }

    @Test
    void acceptedMailStaysSentEvenIfClosingConnectionFails() throws Exception {
        Transport transport = mock(Transport.class);
        when(connections.openSmtp(account)).thenReturn(transport);
        doThrow(new MessagingException("close failed")).when(transport).close();
        service.send(7L);
        assertThat(message.getStatus()).isEqualTo(OutboundMessage.Status.SENT);
        assertThat(message.getSentAt()).isNotNull();
    }

    @Test
    void revokedSendingPermissionFailsBeforeSmtp() {
        doThrow(
            new dev.despical.dispatch.exception.ApiException(
                org.springframework.http.HttpStatus.NOT_FOUND, "Access removed"))
            .when(access)
            .require(any(), eq(account), eq(MailAccessService.Action.SEND));
        service.send(7L);
        assertThat(message.getStatus()).isEqualTo(OutboundMessage.Status.FAILED);
        assertThat(message.getFailureReason()).contains("permission was removed");
        verifyNoInteractions(connections);
    }

    @Test
    void disabledOwnerCannotSendQueuedMail() {
        account.getOwner().setEnabled(false);
        service.send(7L);
        assertThat(message.getStatus()).isEqualTo(OutboundMessage.Status.FAILED);
        verifyNoInteractions(connections);
    }

    @Test
    void deliversToAnIsolatedLocalSmtpServer() throws Exception {
        GreenMail server = new GreenMail(new ServerSetup(0, "127.0.0.1", "smtp"));
        server.start();
        try {
            when(connections.openSmtp(account))
                .thenAnswer(
                    invocation -> {
                        Transport transport =
                            Session.getInstance(new Properties()).getTransport("smtp");
                        transport.connect(
                            "127.0.0.1", server.getSmtp().getPort(), null, null);
                        return transport;
                    });
            service.send(7L);
            assertThat(message.getStatus()).isEqualTo(OutboundMessage.Status.SENT);
            assertThat(server.waitForIncomingEmail(2000, 1)).isTrue();
            assertThat(server.getReceivedMessages()[0].getSubject())
                .isEqualTo("Local delivery check");
            assertThat(server.getReceivedMessages()[0].getContent().toString())
                .contains("Example body");
        } finally {
            server.stop();
        }
    }

    @Test
    void sentDetailsAreOwnerScopedAndExcludeDrafts() {
        UUID id = message.getPublicId();
        when(outbox.findByPublicIdAndCreatedById(id, 42L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.sentDetail(42L, id))
            .isInstanceOf(dev.despical.dispatch.exception.ApiException.class);
        when(outbox.findByPublicIdAndCreatedById(id, 42L)).thenReturn(Optional.of(message));
        message.setStatus(OutboundMessage.Status.DRAFT);
        assertThatThrownBy(() -> service.sentDetail(42L, id))
            .isInstanceOf(dev.despical.dispatch.exception.ApiException.class);
    }
}
