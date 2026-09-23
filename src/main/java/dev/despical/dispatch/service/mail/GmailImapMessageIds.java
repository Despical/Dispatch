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

import org.eclipse.angus.mail.iap.Response;
import org.eclipse.angus.mail.imap.IMAPFolder;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author Despical
 * <p>
 * Created at 23.09.2026
 */
final class GmailImapMessageIds {

    private static final Pattern UID = Pattern.compile("(?i)\\bUID\\s+(\\d+)");
    private static final Pattern GMAIL_ID = Pattern.compile("(?i)\\bX-GM-MSGID\\s+(\\d+)");

    private GmailImapMessageIds() {
    }

    static Map<Long, String> fetch(IMAPFolder folder, long firstUid, long lastUid) throws Exception {
        if (lastUid < firstUid) return Map.of();
        Response[] replies = (Response[]) folder.doCommand(protocol ->
            protocol.command("UID FETCH " + firstUid + ":" + lastUid + " (UID X-GM-MSGID)", null));
        Map<Long, String> ids = new HashMap<>();
        for (Response reply : replies) {
            Entry entry = parse(reply.toString());
            if (entry != null && entry.uid() >= firstUid && entry.uid() <= lastUid) {
                ids.put(entry.uid(), entry.gmailId());
            }
        }
        return ids;
    }

    static Entry parse(String response) {
        if (response == null || !response.startsWith("* ") || !response.contains("FETCH")) return null;
        Matcher uid = UID.matcher(response);
        Matcher gmailId = GMAIL_ID.matcher(response);
        if (!uid.find() || !gmailId.find()) return null;
        try {
            BigInteger identifier = new BigInteger(gmailId.group(1));
            if (identifier.signum() <= 0 || identifier.bitLength() > 64) return null;
            return new Entry(Long.parseLong(uid.group(1)), identifier.toString(16));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    record Entry(long uid, String gmailId) {
    }
}
