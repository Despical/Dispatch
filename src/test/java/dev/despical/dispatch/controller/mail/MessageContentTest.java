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
import dev.despical.dispatch.service.mail.HtmlSanitizerService;
import org.jsoup.Jsoup;

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
    final HtmlSanitizerService sanitizer = new HtmlSanitizerService();
    final MessageController controller = new MessageController(query, null, null, sanitizer, null, null);
    final DispatchPrincipal principal =
        new DispatchPrincipal(42L, "owner@example.test", "Owner", UUID.randomUUID());

    String content(MailMessage message) {
        when(query.get(42L, 7L)).thenReturn(message);
        return new String(
            controller.content(7L, false, false, false, principal).getBody(), StandardCharsets.UTF_8);
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

    @Test
    void rendersSimplifiedMessageWithLightThemeColors() {
        MailMessage message = new MailMessage();
        message.setSanitizedHtml("<p>Readable message</p>");
        when(query.get(42L, 7L)).thenReturn(message);
        String page = new String(
            controller.content(7L, false, false, true, principal).getBody(), StandardCharsets.UTF_8);
        assertThat(page).contains("background:#fbfafd", "color:#32283e", "Readable message");
    }

    @Test
    void restoresRemoteImagesAndTheirOriginalAltInBothViews() {
        String original = "<img src='https://example.test/logo.png' alt='Company logo' onerror='steal()'>";
        MailMessage message = new MailMessage();
        message.setSanitizedHtml(sanitizer.sanitize(original, false));
        message.setStyledHtml(sanitizer.sanitizeStyled(original, false));
        when(query.get(42L, 7L)).thenReturn(message);

        for (boolean styled : new boolean[]{false, true}) {
            var response = controller.content(7L, true, styled, false, principal);
            String page = new String(response.getBody(), StandardCharsets.UTF_8);
            var image = Jsoup.parse(page).selectFirst("img");
            assertThat(image.attr("src")).isEqualTo("https://example.test/logo.png");
            assertThat(image.attr("alt")).isEqualTo("Company logo");
            assertThat(image.hasAttr("onerror")).isFalse();
            assertThat(page).doesNotContain("Remote image blocked", "data-remote-src", "data-remote-alt");
            assertThat(page).contains("dispatch-image-status", "naturalWidth", "imageView");
            String nonce = Jsoup.parse(page).select("script").last().attr("nonce");
            assertThat(response.getHeaders().getFirst("Content-Security-Policy"))
                .contains("script-src 'nonce-" + nonce + "'", "img-src data: https: http:");
        }
    }

    @Test
    void clearsLegacyBlockedAltAndHandlesNetworkPathImagesSafely() {
        MailMessage message = new MailMessage();
        message.setSanitizedHtml("<img data-remote-src='//example.test/logo.png' alt='Remote image blocked'>"
            + "<img data-remote-src='javascript:alert(1)' alt='Remote image blocked'>");
        when(query.get(42L, 7L)).thenReturn(message);
        var response = controller.content(7L, true, false, false, principal);
        var images = Jsoup.parse(new String(response.getBody(), StandardCharsets.UTF_8)).select("img");
        assertThat(images.get(0).attr("src")).isEqualTo("https://example.test/logo.png");
        assertThat(images.get(0).hasAttr("alt")).isFalse();
        assertThat(images.get(1).hasAttr("src")).isFalse();
    }

    @Test
    void leavesRemoteImagesBlockedUntilConsent() {
        MailMessage message = new MailMessage();
        message.setSanitizedHtml(sanitizer.sanitize("<img src='https://example.test/pixel.png'>", false));
        when(query.get(42L, 7L)).thenReturn(message);
        var response = controller.content(7L, false, false, false, principal);
        String page = new String(response.getBody(), StandardCharsets.UTF_8);
        assertThat(Jsoup.parse(page).selectFirst("img").hasAttr("src")).isFalse();
        assertThat(page).doesNotContain("dispatch-image-status");
        assertThat(response.getHeaders().getFirst("Content-Security-Policy"))
            .contains("img-src data:;").doesNotContain("img-src data: https:");
    }
}
