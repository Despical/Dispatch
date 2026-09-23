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

/**
 * @author Despical
 * <p>
 * Created at 23.09.2026
 */
class GmailImapMessageIdsTest {

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
