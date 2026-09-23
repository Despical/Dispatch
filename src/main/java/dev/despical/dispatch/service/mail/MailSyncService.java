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

import dev.despical.dispatch.entity.mail.MailAccount;
import dev.despical.dispatch.entity.mail.MailFolder;
import dev.despical.dispatch.entity.mail.MailMessage;
import dev.despical.dispatch.event.mail.MailSyncCompletedEvent;
import dev.despical.dispatch.repository.mail.MailAccountRepository;
import dev.despical.dispatch.repository.mail.MailFolderRepository;
import dev.despical.dispatch.repository.mail.MailMessageRepository;
import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author Despical
 * <p>
 * Created at 21.09.2026
 */
@Service
public class MailSyncService {

    private static final Logger LOGGER = LoggerFactory.getLogger(MailSyncService.class);

    private final MailAccountRepository accounts;
    private final MailFolderRepository folders;
    private final MailMessageRepository messages;
    private final MailConnectionFactory connections;
    private final HtmlSanitizerService sanitizer;
    private final AttachmentService attachmentService;
    private final GmailMessageService gmail;
    private final TransactionTemplate transactions;
    private final ApplicationEventPublisher events;
    private final Map<Long, Object> locks = new ConcurrentHashMap<>();
    private volatile Instant lastSuccessfulSync;

    public MailSyncService(MailAccountRepository accounts, MailFolderRepository folders,
                           MailMessageRepository messages, MailConnectionFactory connections,
                           HtmlSanitizerService sanitizer, AttachmentService attachmentService,
                           GmailMessageService gmail,
                           PlatformTransactionManager transactionManager,
                           ApplicationEventPublisher events) {
        this.accounts = accounts;
        this.folders = folders;
        this.messages = messages;
        this.connections = connections;
        this.sanitizer = sanitizer;
        this.attachmentService = attachmentService;
        this.gmail = gmail;
        this.transactions = new TransactionTemplate(transactionManager);
        this.events = events;
    }

    @Scheduled(fixedDelayString = "${dispatch.mail.sync-interval-ms:60000}")
    public void scheduledSync() {
        accounts.findAllByActiveTrueOrderByDisplayNameAsc()
            .stream()
            .sorted(Comparator.comparing(account -> "GOOGLE".equals(account.getAuthProvider())))
            .forEach(account -> {
                try {
                    syncAccount(account.getId());
                } catch (Exception ignored) {
                    // Status is persisted without logging credentials, message data or
                    // server responses.
                }
            });
    }

    public void syncAccount(Long accountId) {
        syncAccount(accountId, false);
    }

    public void syncAccountFully(Long accountId) {
        syncAccount(accountId, true);
    }

    private void syncAccount(Long accountId, boolean full) {
        synchronized (locks.computeIfAbsent(accountId, ignored -> new Object())) {
            MailAccount account = accounts.findById(accountId).orElseThrow();
            if (!account.isActive()) return;
            markStatus(accountId, "SYNCING", null, false);
            try (Store store = connections.openImap(account)) {
                Folder[] available = store.getDefaultFolder().list("*");
                boolean google = "GOOGLE".equals(account.getAuthProvider());
                boolean hasAllMail = google && Arrays.stream(available).anyMatch(
                    folder -> "ALL".equals(specialUse(folder)));
                List<Folder> ordered =
                    Arrays.stream(available)
                        .sorted(Comparator.comparingInt(
                            folder -> google ? folderPriority(specialUse(folder)) : 0))
                        .toList();
                boolean upToDate = true;
                Set<String> seenGmailIds = full && google ? new HashSet<>() : null;
                Set<LocalKey> seenGmailLocations = full && google ? new HashSet<>() : null;
                for (Folder remote : ordered) {
                    if ((remote.getType() & Folder.HOLDS_MESSAGES) != 0 &&
                        remote instanceof UIDFolder uidFolder) {
                        String specialUse = specialUse(remote);
                        if (google && !"ALL".equals(specialUse) && !"JUNK".equals(specialUse) &&
                            !"TRASH".equals(specialUse) &&
                            !("INBOX".equals(specialUse) && !hasAllMail))
                            continue;
                        upToDate &= syncFolder(account, remote, uidFolder,
                            "INBOX".equals(specialUse) ? "ALL" : specialUse,
                            full, seenGmailIds, seenGmailLocations);
                    }
                }
                if (seenGmailIds != null) {
                    List<MailMessage> removed = new ArrayList<>();
                    for (MailMessage message : messages.findAllByAccountId(accountId)) {
                        if (seenGmailLocations.contains(new LocalKey(
                            message.getFolder().getId(), message.getUidValidity(),
                            message.getImapUid()))) continue;
                        GmailMessageService.MoveResult state = gmail.inspect(message);
                        if (state.missing() || seenGmailIds.contains(state.gmailId()))
                            removed.add(message);
                        else transactions.executeWithoutResult(status -> {
                            MailMessage managed = messages.findById(message.getId()).orElseThrow();
                            managed.setGmailMessageId(state.gmailId());
                            managed.setTrashedFlag(state.trashed());
                            managed.setTrashedAt(state.trashed() ? managed.getTrashedAt() == null
                                ? Instant.now() : managed.getTrashedAt() : null);
                            if (!state.trashed()) managed.setTrashOriginLabels(null);
                        });
                    }
                    if (!removed.isEmpty()) {
                        attachmentService.deleteFilesForMessages(removed.stream().map(MailMessage::getId).toList());
                        transactions.executeWithoutResult(status -> messages.deleteAllInBatch(removed));
                    }
                }
                markStatus(accountId, upToDate ? "READY" : "SYNCING", null, true);
                lastSuccessfulSync = Instant.now();
                events.publishEvent(new MailSyncCompletedEvent(accountId, true, Instant.now()));
            } catch (Exception exception) {
                Throwable root = exception;
                while (root.getCause() != null) root = root.getCause();
                StackTraceElement origin = root.getStackTrace().length == 0 ? null
                    : root.getStackTrace()[0];
                LOGGER.warn("Mail sync failed for account {}: {} at {}", accountId,
                    root.getClass().getSimpleName(), origin);
                markStatus(accountId, "ERROR",
                    "Synchronization failed. Check server connectivity and credentials.",
                    false);
                events.publishEvent(new MailSyncCompletedEvent(accountId, false, Instant.now()));
                throw new IllegalStateException("Mail synchronization failed", exception);
            }
        }
    }

    private String specialUse(Folder folder) {
        if (folder instanceof IMAPFolder imap) {
            try {
                for (String attribute : imap.getAttributes()) {
                    if ("\\All".equalsIgnoreCase(attribute)) return "ALL";
                    if ("\\Junk".equalsIgnoreCase(attribute)) return "JUNK";
                    if ("\\Trash".equalsIgnoreCase(attribute)) return "TRASH";
                    if ("\\Inbox".equalsIgnoreCase(attribute)) return "INBOX";
                }
            } catch (Exception ignored) {
                /* Fall back to the standard inbox name. */
            }
        }
        String name = folder.getFullName();
        if ("INBOX".equalsIgnoreCase(name)) return "INBOX";
        if (name.matches("(?i)(?:.*[./])?(?:trash|deleted(?: items| messages)?|bin|çöp kutusu)"))
            return "TRASH";
        return null;
    }

    private int folderPriority(String specialUse) {
        return switch (specialUse == null ? "" : specialUse) {
            case "JUNK" -> 0;
            case "TRASH" -> 1;
            case "ALL", "INBOX" -> 2;
            default -> 3;
        };
    }

    private boolean syncFolder(MailAccount account, Folder remote, UIDFolder uidFolder,
                               String specialUse, boolean full, Set<String> seenGmailIds,
                               Set<LocalKey> seenGmailLocations) throws Exception {
        remote.open(Folder.READ_ONLY);
        try {
            long uidValidity = uidFolder.getUIDValidity();
            int unreadCount = remote.getUnreadMessageCount();
            MailFolder local = transactions.execute(status -> {
                MailFolder folder =
                    folders.findByAccountIdAndFullName(account.getId(), remote.getFullName())
                        .orElseGet(MailFolder::new);
                if (folder.getId() == null) {
                    folder.setAccount(account);
                    folder.setFullName(remote.getFullName());
                    folder.setDisplayName(remote.getName());
                }
                if (folder.getUidValidity() != 0 && folder.getUidValidity() != uidValidity) {
                    messages.deleteAllByFolderId(folder.getId());
                    folder.setLastSyncedUid(0);
                }
                folder.setUidValidity(uidValidity);
                folder.setUnreadCount(unreadCount);
                folder.setSpecialUse(specialUse);
                return folders.save(folder);
            });
            backfillStyledMessages(account, local, uidValidity, uidFolder);
            long startUid = full && seenGmailIds != null ? 1
                : Math.max(1, local.getLastSyncedUid() + 1);
            long nextUid = uidFolder.getUIDNext();
            long highestAvailable =
                nextUid > 0 ? nextUid - 1
                    : remote.getMessageCount() == 0
                    ? 0
                    : uidFolder.getUID(remote.getMessage(remote.getMessageCount()));
            if (full && seenGmailIds != null) {
                int count = remote.getMessageCount();
                for (int first = 1; first <= count; first += 500) {
                    int last = Math.min(count, first + 499);
                    syncMessages(account, local, uidValidity, uidFolder, remote,
                        remote.getMessages(first, last), seenGmailIds,
                        seenGmailLocations);
                }
                long checkpoint = highestAvailable;
                transactions.executeWithoutResult(status ->
                    folders.findById(local.getId()).orElseThrow().setLastSyncedUid(checkpoint));
                return true;
            }
            if (highestAvailable < startUid) return true;
            if ("GOOGLE".equals(account.getAuthProvider()) && "ALL".equals(specialUse) &&
                local.getLastSyncedUid() < highestAvailable - 2000 &&
                remote.getMessageCount() > 0) {
                int last = remote.getMessageCount();
                syncMessages(account, local, uidValidity, uidFolder, remote,
                    remote.getMessages(Math.max(1, last - 14), last), null, null);
            }
            boolean google = "GOOGLE".equals(account.getAuthProvider());
            int batchSpan = google ? 500 : 200;
            for (long first = startUid; first <= highestAvailable; first += batchSpan) {
                long last = Math.min(highestAvailable, first + batchSpan - 1);
                syncUidRange(account, local, uidValidity, uidFolder, remote, first, last,
                    seenGmailIds, seenGmailLocations);
                long checkpoint = last;
                transactions.executeWithoutResult(
                    status
                        -> folders.findById(local.getId()).orElseThrow().setLastSyncedUid(checkpoint));
                if (google && !full) return last >= highestAvailable;
            }
            return true;
        } finally {
            if (remote.isOpen()) remote.close(false);
        }
    }

    private void syncUidRange(MailAccount account, MailFolder local, long uidValidity,
                              UIDFolder uidFolder, Folder remote, long first, long last,
                              Set<String> seenGmailIds, Set<LocalKey> seenGmailLocations)
        throws Exception {
        Message[] fetched = uidFolder.getMessagesByUID(first, last);
        syncMessages(account, local, uidValidity, uidFolder, remote, fetched, seenGmailIds,
            seenGmailLocations);
    }

    private void syncMessages(MailAccount account, MailFolder local, long uidValidity,
                              UIDFolder uidFolder, Folder remote, Message[] fetched,
                              Set<String> seenGmailIds, Set<LocalKey> seenGmailLocations)
        throws Exception {
        if (fetched == null || fetched.length == 0) return;
        Map<Long, String> gmailIds = Map.of();
        if ("GOOGLE".equals(account.getAuthProvider()) && remote instanceof IMAPFolder imap) {
            long first = Long.MAX_VALUE;
            long last = 0;
            for (Message source : fetched) {
                long uid = uidFolder.getUID(source);
                if (uid > 0) {
                    first = Math.min(first, uid);
                    last = Math.max(last, uid);
                }
            }
            if (last > 0) gmailIds = GmailImapMessageIds.fetch(imap, first, last);
            for (Message source : fetched) {
                if (source != null && !source.isExpunged()) {
                    long uid = uidFolder.getUID(source);
                    if (uid > 0 && !gmailIds.containsKey(uid))
                        throw new IllegalStateException("Gmail did not provide a stable message ID.");
                }
            }
        }
        FetchProfile profile = new FetchProfile();
        profile.add(FetchProfile.Item.ENVELOPE);
        profile.add(FetchProfile.Item.FLAGS);
        profile.add(UIDFolder.FetchProfileItem.UID);
        remote.fetch(fetched, profile);
        for (Message source : fetched) {
            if (source == null || source.isExpunged()) continue;
            long uid = uidFolder.getUID(source);
            if (uid <= 0) continue;
            try {
                persistMessage(account, local, uidValidity, uid, source, gmailIds.get(uid));
                if (seenGmailIds != null) seenGmailIds.add(gmailIds.get(uid));
                if (seenGmailLocations != null)
                    seenGmailLocations.add(new LocalKey(local.getId(), uidValidity, uid));
            } catch (IllegalStateException exception) {
                if (!missingEnvelope(exception)) throw exception;
                // Gmail can expunge a message between UID listing and envelope fetch.
            }
        }
    }

    private boolean missingEnvelope(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof jakarta.mail.MessagingException &&
                "Failed to load IMAP envelope".equals(cause.getMessage()))
                return true;
        }
        return false;
    }

    private void backfillStyledMessages(MailAccount account, MailFolder folder, long uidValidity,
                                        UIDFolder remote) {
        for (MailMessage pending :
            messages.findAllByFolderIdAndStyledHtmlIsNullOrderByImapUidAsc(folder.getId())) {
            try {
                Message source = remote.getMessageByUID(pending.getImapUid());
                if (source != null && !source.isExpunged()) {
                    persistMessage(account, folder, uidValidity, pending.getImapUid(), source,
                        pending.getGmailMessageId());
                }
            } catch (Exception ignored) {
                // A removed or malformed remote message must not block the rest of the mailbox
                // sync.
            }
        }
    }

    private void persistMessage(MailAccount account, MailFolder folder, long uidValidity, long uid,
                                Message source, String gmailId) {
        var existing =
            messages.findByFolderIdAndUidValidityAndImapUid(folder.getId(), uidValidity, uid);
        if (existing.isPresent()) {
            transactions.executeWithoutResult(status -> {
                MailMessage managed = messages.findById(existing.get().getId()).orElseThrow();
                if (gmailId != null) managed.setGmailMessageId(gmailId);
                updateTrashState(managed, folder);
            });
            if (existing.get().getStyledHtml() == null ||
                existing.get().getStyledHtml().isBlank()) {
                transactions.executeWithoutResult(status -> {
                    try {
                        MailMessage managed =
                            messages.findById(existing.get().getId()).orElseThrow();
                        BodyParts body = new BodyParts();
                        collectParts(source, body, managed, false);
                        managed.setStyledHtml(sanitizer.sanitizeStyled(bodyHtml(body), false));
                    } catch (Exception exception) {
                        throw new IllegalStateException("Unable to backfill styled message content",
                            exception);
                    }
                });
            }
            return;
        }
        transactions.executeWithoutResult(status -> {
            try {
                MailMessage message = gmailId == null ? null
                    : messages.findFirstByAccountIdAndGmailMessageIdOrderByIdDesc(account.getId(), gmailId)
                    .orElse(null);
                if (message == null && gmailId != null) {
                    String internetId = header(source, "Message-ID");
                    if (internetId != null) {
                        List<MailMessage> matches = messages.findAllByAccountIdAndInternetMessageId(
                            account.getId(), internetId);
                        if (matches.size() == 1 &&
                            (matches.get(0).getGmailMessageId() == null ||
                                gmailId.equals(matches.get(0).getGmailMessageId())) &&
                            matches.get(0).getSubject().equals(source.getSubject() == null ? "(no subject)" : source.getSubject()))
                            message = matches.get(0);
                    }
                }
                boolean newMessage = message == null;
                if (newMessage) message = new MailMessage();
                message.setAccount(account);
                message.setFolder(folder);
                message.setUidValidity(uidValidity);
                message.setImapUid(uid);
                message.setGmailMessageId(gmailId);
                updateTrashState(message, folder);
                if (!newMessage) return;
                message.setInternetMessageId(header(source, "Message-ID"));
                message.setInReplyTo(header(source, "In-Reply-To"));
                message.setReferencesHeader(header(source, "References"));
                message.setSubject(source.getSubject() == null ? "(no subject)"
                    : source.getSubject());
                message.setFromAddress(addresses(source.getFrom()));
                message.setRecipients(addresses(source.getAllRecipients()));
                message.setReadFlag(source.isSet(Flags.Flag.SEEN));
                message.setStarredFlag(source.isSet(Flags.Flag.FLAGGED));
                if (source.getSentDate() != null)
                    message.setSentAt(source.getSentDate().toInstant());
                if (source.getReceivedDate() != null)
                    message.setReceivedAt(source.getReceivedDate().toInstant());
                else
                    message.setReceivedAt(message.getSentAt() == null ? Instant.now()
                        : message.getSentAt());
                messages.save(message);
                BodyParts body = new BodyParts();
                collectParts(source, body, message, true);
                message.setTextBody(limit(body.text.toString(), 65_535));
                String html = bodyHtml(body);
                message.setSanitizedHtml(sanitizer.sanitize(html, false));
                message.setStyledHtml(sanitizer.sanitizeStyled(html, false));
                message.setHasAttachments(body.attachments > 0);
            } catch (Exception exception) {
                throw new IllegalStateException("Unable to persist message summary", exception);
            }
        });
    }

    private void updateTrashState(MailMessage message, MailFolder folder) {
        boolean trashed = "TRASH".equals(folder.getSpecialUse());
        message.setTrashedFlag(trashed);
        message.setTrashedAt(trashed ? message.getTrashedAt() == null ? Instant.now()
            : message.getTrashedAt() : null);
        if (!trashed) message.setTrashOriginLabels(null);
    }

    private void collectParts(Part part, BodyParts body, MailMessage message,
                              boolean storeAttachments) throws Exception {
        String disposition = part.getDisposition();
        boolean attachment = Part.ATTACHMENT.equalsIgnoreCase(disposition) ||
            (part.getFileName() != null && !part.getFileName().isBlank());
        if (attachment) {
            if (storeAttachments) {
                attachmentService.store(message, part);
                body.attachments++;
            }
            return;
        }
        if (part.isMimeType("text/plain")) {
            body.text.append(part.getContent()).append('\n');
        } else if (part.isMimeType("text/html")) {
            body.html.append(part.getContent());
        } else if (part.isMimeType("multipart/*") && part.getContent() instanceof
            Multipart multipart) {
            for (int index = 0; index < multipart.getCount(); index++) {
                collectParts(multipart.getBodyPart(index), body, message, storeAttachments);
            }
        }
    }

    private String bodyHtml(BodyParts body) {
        return body.html.isEmpty()
            ? new org.jsoup.nodes.Element("pre").text(body.text.toString()).outerHtml()
            : body.html.toString();
    }

    private String header(Message message, String name) throws Exception {
        String[] values = message.getHeader(name);
        return values == null || values.length == 0 ? null : limit(String.join(" ", values), 998);
    }

    private String addresses(Address[] values) {
        if (values == null) return "";
        List<String> output = new ArrayList<>();
        for (Address address : values) {
            output.add(address instanceof InternetAddress internet ? internet.toUnicodeString()
                : address.toString());
        }
        return String.join(", ", output);
    }

    private void markStatus(Long accountId, String syncStatus, String error, boolean completed) {
        transactions.executeWithoutResult(
            status -> accounts.findById(accountId).ifPresent(account -> {
                account.setSyncStatus(syncStatus);
                account.setSyncError(error);
                if (completed) account.setLastSyncAt(Instant.now());
            }));
    }

    public Instant getLastSuccessfulSync() {
        return lastSuccessfulSync;
    }

    private String limit(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private static final class BodyParts {
        final StringBuilder text = new StringBuilder();
        final StringBuilder html = new StringBuilder();
        int attachments;
    }

    private record LocalKey(Long folderId, long uidValidity, long uid) {
    }
}
