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
 * Created at 21.09.2026
 */
class HtmlSanitizerServiceTest {

    private final HtmlSanitizerService sanitizer = new HtmlSanitizerService();

    @Test
    void stripsExecutableMarkupAndBlocksRemoteTrackingImages() {
        String unsafe =
            "<script>alert(1)</script><form action='https://evil.test'><input></form><a"
                + " href='javascript:alert(2)'>bad</a><img src='https://tracker.test/pixel'><p"
                + " onclick='steal()'>Safe text</p>";

        String result = sanitizer.sanitize(unsafe, false);

        assertThat(result).doesNotContain("script", "form", "input", "onclick", "javascript:");
        assertThat(result).doesNotContain("<img src=\"https://tracker.test");
        assertThat(result).contains("data-remote-src=\"https://tracker.test/pixel\"");
        assertThat(result).contains("Safe text");
    }

    @Test
    void preservesSafeEmailPresentationOnlyInStyledMode() {
        String email =
            "<style>.card{color:#123456}</style><div class='card'"
                + " style='padding:20px'>Hello</div><script>alert(1)</script><img"
                + " src='https://tracker.test/pixel'>";

        String simplified = sanitizer.sanitize(email, false);
        String styled = sanitizer.sanitizeStyled(email, false);

        assertThat(simplified).doesNotContain("<style", "style=");
        assertThat(styled).contains("<style", "style=\"padding:20px\"");
        assertThat(styled).doesNotContain("<script", "<img src=\"https://tracker.test");
        assertThat(styled).contains("data-remote-src=\"https://tracker.test/pixel\"");
    }

    @Test
    void removesDangerousCssFromStyledMode() {
        String styled =
            sanitizer.sanitizeStyled(
                "<div style=\"background:url(javascript:alert(1))\">Hello</div>", false);

        assertThat(styled).contains("Hello").doesNotContain("javascript:", "style=");
    }
}
