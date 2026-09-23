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

import jakarta.mail.FetchProfile;
import jakarta.mail.Session;
import jakarta.mail.URLName;
import org.eclipse.angus.mail.iap.ParsingException;
import org.eclipse.angus.mail.iap.ProtocolException;
import org.eclipse.angus.mail.imap.IMAPSSLStore;
import org.eclipse.angus.mail.imap.protocol.FetchItem;
import org.eclipse.angus.mail.imap.protocol.FetchResponse;
import org.eclipse.angus.mail.imap.protocol.IMAPProtocol;
import org.eclipse.angus.mail.util.MailLogger;

import java.io.IOException;
import java.util.Properties;

/**
 * @author Despical
 * <p>
 * Created at 23.09.2026
 */
final class GmailImapStore extends IMAPSSLStore {

    private static final FetchItem GMAIL_MESSAGE_ID = new FetchItem("X-GM-MSGID",
        new FetchProfile.Item("X-GM-MSGID") { }) {
        @Override
        public Object parseItem(FetchResponse response) throws ParsingException {
            String value = response.readAtom();
            if (value == null || !value.matches("\\d+"))
                throw new ParsingException("Invalid Gmail message ID");
            return value;
        }
    };

    GmailImapStore(Session session) {
        super(session, (URLName) null);
    }

    @Override
    protected IMAPProtocol newIMAPProtocol(String host, int port)
        throws IOException, ProtocolException {
        return new GmailProtocol(name, host, port, session.getProperties(), isSSL, logger);
    }

    private static final class GmailProtocol extends IMAPProtocol {

        GmailProtocol(String name, String host, int port, Properties properties, boolean ssl,
                      MailLogger logger) throws IOException, ProtocolException {
            super(name, host, port, properties, ssl, logger);
        }

        @Override
        public FetchItem[] getFetchItems() {
            return new FetchItem[]{GMAIL_MESSAGE_ID};
        }
    }
}
