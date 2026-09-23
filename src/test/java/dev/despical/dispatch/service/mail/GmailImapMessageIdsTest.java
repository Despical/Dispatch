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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import jakarta.mail.Session;
import jakarta.mail.Store;
import org.eclipse.angus.mail.imap.IMAPFolder;
import java.util.Properties;

/**
 * @author Despical
 * <p>
 * Created at 23.09.2026
 */
class GmailImapMessageIdsTest {

    @Test
    void diagnoseLiveTrashFetch() throws Exception {
        String email = System.getenv("DISPATCH_DIAG_EMAIL");
        String token = System.getenv("DISPATCH_DIAG_ACCESS");
        Assumptions.assumeTrue(email != null && token != null);
        Properties config = new Properties();
        config.put("mail.imaps.auth.mechanisms", "XOAUTH2");
        try (Store store = new GmailImapStore(Session.getInstance(config))) {
            store.connect("imap.gmail.com", 993, email, token);
            IMAPFolder trash = null;
            for (jakarta.mail.Folder folder : store.getDefaultFolder().list("*")) {
                if (folder instanceof IMAPFolder imap &&
                    java.util.Arrays.stream(imap.getAttributes())
                        .anyMatch(attribute -> "\\Trash".equalsIgnoreCase(attribute))) {
                    trash = imap;
                    break;
                }
            }
            assertThat(trash).isNotNull();
            trash.open(jakarta.mail.Folder.READ_ONLY);
            try {
                long first = trash.getUID(trash.getMessage(Math.max(1, trash.getMessageCount() - 9)));
                long last = trash.getUID(trash.getMessage(trash.getMessageCount()));
                assertThat(GmailImapMessageIds.fetch(trash, first, last))
                    .as("Gmail IDs in a live Trash UID range")
                    .isNotEmpty();
            } finally {
                trash.close(false);
            }
        }
    }

    @Test
    void convertsUnsignedImapIdentityToGmailApiIdentity() {
        assertThat(GmailImapMessageIds.parse(
            "* 12 FETCH (X-GM-MSGID 18446744073709551615 UID 42)"))
            .isEqualTo(new GmailImapMessageIds.Entry(42L, "ffffffffffffffff"));
        assertThat(GmailImapMessageIds.parse("* 12 FETCH (UID 42 X-GM-MSGID 0)"))
            .isNull();
        assertThat(GmailImapMessageIds.parse("A1 OK FETCH completed"))
            .isNull();
    }
}
