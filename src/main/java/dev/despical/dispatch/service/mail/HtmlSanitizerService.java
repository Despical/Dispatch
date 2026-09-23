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

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Service;

/**
 * @author Despical
 * <p>
 * Created at 21.09.2026
 */
@Service
public class HtmlSanitizerService {

    private final Safelist safelist = baseSafelist();
    private final Safelist styledSafelist =
        baseSafelist()
            .addTags("style")
            .addAttributes(":all", "style", "id", "dir", "lang")
            .addAttributes("table", "align", "bgcolor", "border", "cellpadding", "cellspacing",
                "width")
            .addAttributes("tr", "align", "bgcolor", "valign")
            .addAttributes("td", "align", "bgcolor", "colspan", "height", "rowspan", "valign",
                "width");

    private static Safelist baseSafelist() {
        return Safelist.relaxed()
            .addTags("table", "thead", "tbody", "tfoot", "tr", "th", "td", "blockquote")
            .addAttributes(":all", "class", "title", "aria-label", "role")
            .addAttributes("a", "target", "rel")
            .addAttributes("img", "width", "height", "alt")
            .addProtocols("a", "href", "http", "https", "mailto")
            .addProtocols("img", "src", "cid", "data", "http", "https");
    }

    public String sanitize(String unsafeHtml, boolean allowRemoteImages) {
        return sanitize(unsafeHtml, allowRemoteImages, false);
    }

    public String sanitizeStyled(String unsafeHtml, boolean allowRemoteImages) {
        return sanitize(unsafeHtml, allowRemoteImages, true);
    }

    private String sanitize(String unsafeHtml, boolean allowRemoteImages, boolean preserveStyles) {
        if (unsafeHtml == null || unsafeHtml.isBlank()) return "";
        Document.OutputSettings settings = new Document.OutputSettings().prettyPrint(false);
        String cleaned =
            Jsoup.clean(unsafeHtml, "", preserveStyles ? styledSafelist : safelist, settings);
        Document document = Jsoup.parseBodyFragment(cleaned);
        for (Element link : document.select("a[href]")) {
            link.attr("target", "_blank");
            link.attr("rel", "noopener noreferrer nofollow");
        }
        if (!allowRemoteImages) {
            for (Element image : document.select("img[src]")) {
                String source = image.attr("src").trim().toLowerCase();
                if (source.startsWith("http://") || source.startsWith("https://") ||
                    source.startsWith("//")) {
                    image.attr("data-remote-src", image.attr("src"));
                    image.removeAttr("src");
                    image.attr("alt", "Remote image blocked");
                }
            }
        }
        document
            .select(preserveStyles
                ? "form, input, button, textarea, select, option, iframe, object,"
                + " embed, script, link, meta"
                : "form, input, button, textarea, select, option, iframe, object,"
                + " embed, script, style, link, meta")
            .remove();
        if (preserveStyles) {
            document.select("[style]").forEach(element -> {
                String style = element.attr("style").toLowerCase();
                if (style.contains("javascript:") || style.contains("expression("))
                    element.removeAttr("style");
            });
            document.select("style").forEach(style -> {
                String css = style.data().toLowerCase();
                if (css.contains("javascript:") || css.contains("expression(")) style.remove();
            });
        }
        return document.body().html();
    }
}
