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
package dev.despical.dispatch.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetup;

import dev.despical.dispatch.entity.mail.MailAccount;
import dev.despical.dispatch.entity.security.AdminUser;
import dev.despical.dispatch.repository.mail.MailAccountRepository;
import dev.despical.dispatch.repository.mail.MailMessageRepository;
import dev.despical.dispatch.repository.security.AdminUserRepository;
import dev.despical.dispatch.security.CryptoService;
import dev.despical.dispatch.service.mail.MailSyncService;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * @author Despical
 * <p>
 * Created at 21.09.2026
 */
@SpringBootTest
@ActiveProfiles("test")
class MailSyncIntegrationTest extends PostgresIntegrationTestSupport {

    static GreenMail greenMail;

    @Autowired
    MailAccountRepository accounts;
    @Autowired
    MailMessageRepository messages;
    @Autowired
    AdminUserRepository admins;
    @Autowired
    MailSyncService sync;
    @Autowired
    CryptoService crypto;

    @BeforeAll
    static void startMailServer() {
        ServerSetup smtp = new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_SMTP);
        ServerSetup imaps = new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_IMAPS);
        greenMail = new GreenMail(new ServerSetup[]{smtp, imaps});
        greenMail.setUser("manager@localhost", "manager", "mail-password");
        greenMail.start();
    }

    @AfterAll
    static void stopMailServer() {
        if (greenMail != null) greenMail.stop();
    }

    @Test
    void incrementallySynchronizesUnreadMessageWithoutMarkingItSeen() {
        GreenMailUtil.sendTextEmail(
            "manager@localhost",
            "sender@example.test",
            "GreenMail sync",
            "Unread body",
            greenMail.getSmtp().getServerSetup());
        MailAccount account = new MailAccount();
        AdminUser owner = new AdminUser();
        owner.setEmail("mail-sync-admin@example.test");
        owner.setDisplayName("Mail Sync Test");
        owner.setPasswordHash("not-used-by-this-test");
        account.setOwner(admins.saveAndFlush(owner));
        account.setDisplayName("GreenMail");
        account.setEmail("manager@localhost");
        account.setUsername("manager");
        account.setImapHost("127.0.0.1");
        account.setImapPort(greenMail.getImaps().getPort());
        account.setSmtpHost("127.0.0.1");
        account.setSmtpPort(greenMail.getSmtp().getPort());
        account.setEncryptedPassword(crypto.encrypt("mail-password"));
        account.setActive(true);
        account = accounts.saveAndFlush(account);

        sync.syncAccount(account.getId());

        var page =
            messages.findAllByOrderByReceivedAtDesc(
                org.springframework.data.domain.PageRequest.of(0, 10));
        assertThat(page).hasSize(1);
        assertThat(page.getContent().getFirst().getSubject()).isEqualTo("GreenMail sync");
        assertThat(page.getContent().getFirst().isReadFlag()).isFalse();
        sync.syncAccount(account.getId());
        assertThat(messages.count()).isEqualTo(1);
    }
}
