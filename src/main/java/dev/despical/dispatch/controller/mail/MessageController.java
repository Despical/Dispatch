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

import dev.despical.dispatch.dto.mail.MailDtos.*;
import dev.despical.dispatch.entity.mail.MailMessage;
import dev.despical.dispatch.exception.ApiException;
import dev.despical.dispatch.repository.mail.MailFolderRepository;
import dev.despical.dispatch.security.DispatchPrincipal;
import dev.despical.dispatch.security.RateLimitService;
import dev.despical.dispatch.service.mail.HtmlSanitizerService;
import dev.despical.dispatch.service.mail.MessageFlagService;
import dev.despical.dispatch.service.mail.MessageQueryService;
import dev.despical.dispatch.service.mail.TrashService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.jsoup.Jsoup;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * @author Despical
 * <p>
 * Created at 21.09.2026
 */
@RestController
@RequestMapping("/api/mail")
@RequiredArgsConstructor
public class MessageController {

    private final MessageQueryService queryService;
    private final MessageFlagService flagService;
    private final MailFolderRepository folders;
    private final HtmlSanitizerService sanitizer;
    private final RateLimitService rateLimits;
    private final TrashService trashService;

    @GetMapping("/folders")
    @Transactional(readOnly = true)
    List<FolderResponse> folders(
        @RequestParam Long accountId,
        @AuthenticationPrincipal DispatchPrincipal principal
    ) {
        return folders
            .findAllByAccountIdAndAccountOwnerIdOrderByDisplayNameAsc(
                accountId, queryService.readableOwner(principal.adminId(), accountId))
            .stream()
            .filter(folder ->
                !"GOOGLE".equals(folder.getAccount().getAuthProvider())
                    || "ALL".equals(folder.getSpecialUse())
                    || "JUNK".equals(folder.getSpecialUse())
                    || "TRASH".equals(folder.getSpecialUse()))
            .map(folder ->
                new FolderResponse(
                    folder.getId(),
                    folder.getAccount().getId(),
                    folder.getDisplayName(),
                    folder.getUnreadCount()))
            .toList();
    }

    @GetMapping("/messages")
    PageResponse<MessageSummary> messages(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "30") int size,
        @RequestParam(required = false) Long accountId,
        @RequestParam(required = false) Long folderId,
        @RequestParam(required = false) String query,
        @RequestParam(required = false) Boolean unread,
        @RequestParam(required = false) Boolean starred,
        @RequestParam(required = false) Boolean attachments,
        @RequestParam(defaultValue = "false") boolean trashed,
        @AuthenticationPrincipal DispatchPrincipal principal,
        HttpServletRequest servletRequest
    ) {
        if (!rateLimits.allow(
            "message-query:" + principal.adminId() + ":" + servletRequest.getRemoteAddr(),
            120,
            60)
        ) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Message query rate limit reached.");
        }

        return queryService.list(
            principal.adminId(),
            page,
            size,
            accountId,
            folderId,
            query,
            unread,
            starred,
            attachments,
            trashed
        );
    }

    @GetMapping("/messages/{id}")
    MessageDetail message(
        @PathVariable Long id, @AuthenticationPrincipal DispatchPrincipal principal) {
        return queryService.detail(principal.adminId(), id);
    }

    @PatchMapping("/messages/{id}/read")
    void read(
        @PathVariable Long id,
        @Valid @RequestBody FlagRequest request,
        @AuthenticationPrincipal DispatchPrincipal principal
    ) {
        flagService.setRead(principal.adminId(), id, request.value());
    }

    @PatchMapping("/messages/{id}/starred")
    void starred(
        @PathVariable Long id,
        @Valid @RequestBody FlagRequest request,
        @AuthenticationPrincipal DispatchPrincipal principal
    ) {
        flagService.setStarred(principal.adminId(), id, request.value());
    }

    @PatchMapping("/messages/{id}/pinned")
    void pinned(
        @PathVariable Long id,
        @Valid @RequestBody FlagRequest request,
        @AuthenticationPrincipal DispatchPrincipal principal
    ) {
        flagService.setPinned(principal.adminId(), id, request.value());
    }

    @PatchMapping("/messages/{id}/trashed")
    void trashed(
        @PathVariable Long id,
        @Valid @RequestBody FlagRequest request,
        @AuthenticationPrincipal DispatchPrincipal principal
    ) {
        flagService.setTrashed(principal.adminId(), id, request.value());
    }

    @DeleteMapping("/messages/{id}")
    void delete(@PathVariable Long id, @AuthenticationPrincipal DispatchPrincipal principal) {
        flagService.setTrashed(principal.adminId(), id, true);
    }

    @DeleteMapping("/messages/trash")
    Map<String, Integer> emptyTrash(
        @RequestParam(required = false) Long accountId,
        @AuthenticationPrincipal DispatchPrincipal principal
    ) {
        return Map.of("deleted", trashService.emptyTrash(principal.adminId(), accountId));
    }

    @GetMapping("/trash/count")
    Map<String, Long> trashCount(
        @RequestParam(required = false) Long accountId,
        @AuthenticationPrincipal DispatchPrincipal principal
    ) {
        return Map.of("count", trashService.count(principal.adminId(), accountId));
    }

    @GetMapping("/trash")
    PageResponse<TrashItem> trash(
        @AuthenticationPrincipal DispatchPrincipal principal,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "all") String filter,
        @RequestParam(defaultValue = "") String query,
        @RequestParam(required = false) Long accountId
    ) {
        return trashService.list(principal.adminId(), accountId, page, filter, query);
    }

    @GetMapping(value = "/messages/{id}/content", produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<byte[]> content(
        @PathVariable Long id,
        @RequestParam(defaultValue = "false") boolean externalImages,
        @RequestParam(defaultValue = "false") boolean original,
        @AuthenticationPrincipal DispatchPrincipal principal
    ) {
        MailMessage message = queryService.get(principal.adminId(), id);
        String selectedHtml = original && message.getStyledHtml() != null && !message.getStyledHtml().isBlank()
            ? message.getStyledHtml()
            : message.getSanitizedHtml();

        String content = selectedHtml == null ? "" : selectedHtml;

        if (externalImages) {
            var document = Jsoup.parseBodyFragment(content);
            document.select("img[data-remote-src]")
                .forEach(image -> {
                    String source = image.attr("data-remote-src");

                    if (source.startsWith("https://") || source.startsWith("http://")) {
                        image.attr("src", source);
                    }
                });
            content = original
                ? sanitizer.sanitizeStyled(document.body().html(), true)
                : sanitizer.sanitize(document.body().html(), true);
        }

        var guardedDocument = Jsoup.parseBodyFragment(content);

        if (guardedDocument.body().text().isBlank()
            && guardedDocument.select("img,video,audio").isEmpty()
        ) {
            if (message.getTextBody() != null && !message.getTextBody().isBlank()) {
                guardedDocument.body().empty().appendElement("pre").text(message.getTextBody());
            } else {
                guardedDocument
                    .body()
                    .empty()
                    .appendElement("p")
                    .attr("style", "font:14px/1.6 system-ui;color:#8b95a5;padding:12px 0")
                    .text(message.isHasAttachments()
                        ? "This message has no body text. It only contains"
                        + " attachments, shown below."
                        : "This message has no body content.");
            }
        }
        guardedDocument
            .select("a[href]")
            .forEach(link -> {
                link.attr("data-dispatch-href", link.attr("href"));
                link.attr("href", "#");
                link.attr("role", "link");
                link.attr("tabindex", "0");
            });
        content = guardedDocument.body().html();
        String presentation = original
            ? "html{color-scheme:light}body{background:#fff;color:#111;margin:0;min-height:100%;overflow-wrap:anywhere}a{color:#0000ee;text-decoration:underline}"
            : "html{color-scheme:dark}body{background:#0d1117;color:#c9d1d9;font:14px"
            + " system-ui;line-height:1.55;padding:24px;margin:0;overflow-wrap:anywhere}a{color:#a78bfa}";
        String scriptNonce = UUID.randomUUID().toString().replace("-", "");
        String linkGuardScript =
            "<script nonce=\""
                + scriptNonce
                + "\">(()=>{const send=(link)=>{const"
                + " box=link.getBoundingClientRect();parent.postMessage({type:'dispatch-link-confirm',url:link.dataset.dispatchHref||'',label:(link.textContent||'').trim(),rect:{left:box.left,top:box.top,bottom:box.bottom}},'*')};document.addEventListener('click',event=>{parent.postMessage({type:'dispatch-frame-click'},'*');const"
                + " target=event.target instanceof"
                + " Element?event.target.closest('a[data-dispatch-href]'):null;if(!target)return;event.preventDefault();event.stopPropagation();send(target)},true);document.addEventListener('auxclick',event=>{const"
                + " target=event.target instanceof"
                + " Element?event.target.closest('a[data-dispatch-href]'):null;if(!target)return;event.preventDefault();event.stopPropagation();send(target)},true);document.addEventListener('keydown',event=>{if(event.key!=='Enter')return;const"
                + " target=event.target instanceof"
                + " Element?event.target.closest('a[data-dispatch-href]'):null;if(!target)return;event.preventDefault();send(target)},true)})();"
                + "</script>";
        String page =
            "<!doctype html><html><head><meta charset=\"utf-8\"><style>"
                + presentation
                + "a[data-dispatch-href]{cursor:pointer}img{max-width:100%;height:auto}table{max-width:100%;border-collapse:collapse}pre{white-space:pre-wrap}"
                + "</style></head><body>"
                + content
                + linkGuardScript
                + "</body></html>";

        String imagePolicy = externalImages ? "img-src data: https: http:;" : "img-src data:;";

        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .header(
                "Content-Security-Policy",
                "sandbox allow-scripts; default-src 'none'; script-src 'nonce-"
                    + scriptNonce
                    + "'; style-src 'unsafe-inline'; "
                    + imagePolicy
                    + " base-uri 'none'; form-action 'none'; frame-ancestors 'self'")
            .header("X-Content-Type-Options", "nosniff")
            .body(page.getBytes(StandardCharsets.UTF_8));
    }
}
