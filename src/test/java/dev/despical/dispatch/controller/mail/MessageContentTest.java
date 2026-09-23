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
package dev.despical.dispatch.controller.mail;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.despical.dispatch.entity.mail.MailMessage;
import dev.despical.dispatch.security.DispatchPrincipal;
import dev.despical.dispatch.service.mail.MessageQueryService;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * @author Despical
 * <p>
 * Created at 22.09.2026
 */
class MessageContentTest {
    final MessageQueryService query = mock(MessageQueryService.class);
    final MessageController controller = new MessageController(query, null, null, null, null, null);
    final DispatchPrincipal principal =
        new DispatchPrincipal(42L, "owner@example.test", "Owner", UUID.randomUUID());

    String content(MailMessage message) {
        when(query.get(42L, 7L)).thenReturn(message);
        return new String(
            controller.content(7L, false, false, principal).getBody(), StandardCharsets.UTF_8);
    }

    @Test
    void explainsAttachmentOnlyAndEmptyMessages() {
        MailMessage message = new MailMessage();
        message.setSanitizedHtml("<div><br>&nbsp;</div>");
        message.setHasAttachments(true);
        assertThat(content(message)).contains("It only contains attachments, shown below.");
        message.setHasAttachments(false);
        assertThat(content(message)).contains("This message has no body content.");
    }

    @Test
    void preservesImageOnlyBodiesAndEscapesTextFallback() {
        MailMessage message = new MailMessage();
        message.setSanitizedHtml("<img data-remote-src='https://example.test/image.png'>");
        assertThat(content(message)).doesNotContain("This message has no body");
        message.setSanitizedHtml("");
        message.setTextBody("<script>example()</script>");
        assertThat(content(message))
            .contains("&lt;script&gt;example()")
            .doesNotContain("<script>example()");
    }
}
