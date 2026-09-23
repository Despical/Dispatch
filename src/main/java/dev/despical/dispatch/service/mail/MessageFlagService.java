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

import dev.despical.dispatch.entity.mail.MailFolder;
import dev.despical.dispatch.entity.mail.MailMessage;
import dev.despical.dispatch.exception.ApiException;
import dev.despical.dispatch.repository.mail.MailFolderRepository;
import dev.despical.dispatch.repository.mail.MailMessageRepository;
import jakarta.mail.*;
import lombok.RequiredArgsConstructor;
import org.eclipse.angus.mail.imap.AppendUID;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.eclipse.angus.mail.imap.IMAPStore;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * @author Despical
 * <p>
 * Created at 21.09.2026
 */
@Service
@RequiredArgsConstructor
public class MessageFlagService {

    private final MailAccessService access;
    private final MailMessageRepository messages;
    private final MailConnectionFactory connections;
    private final MailFolderRepository folders;
    private final GmailMessageService gmail;

    @Transactional
    public void setRead(Long ownerId, Long id, boolean value) {
        MailMessage message = access.message(ownerId, id, MailAccessService.Action.ORGANIZE);
        message.setReadFlag(value);
        try {
            updateRemote(message, Flags.Flag.SEEN, value);
        } catch (ApiException ignored) {
            // Keep the local read state usable while the remote IMAP server is temporarily
            // unavailable.
        }
    }

    @Transactional
    public void setStarred(Long ownerId, Long id, boolean value) {
        MailMessage message = access.message(ownerId, id, MailAccessService.Action.ORGANIZE);
        message.setStarredFlag(value);
        try {
            updateRemote(message, Flags.Flag.FLAGGED, value);
        } catch (ApiException ignored) {
            // Keep starring reliable in Dispatch even when the remote IMAP server is temporarily
            // unavailable.
        }
    }

    @Transactional
    public void setPinned(Long ownerId, Long id, boolean value) {
        MailMessage message = access.message(ownerId, id, MailAccessService.Action.ORGANIZE);
        message.setPinnedFlag(value);
        message.setPinnedAt(value ? Instant.now() : null);
    }

    @Transactional
    public void setTrashed(Long ownerId, Long id, boolean value) {
        MailMessage message = access.message(ownerId, id, MailAccessService.Action.DELETE);
        if ("GOOGLE".equals(message.getAccount().getAuthProvider())) {
            GmailMessageService.MoveResult result = gmail.move(message, value);
            if (result.missing())
                throw new ApiException(HttpStatus.CONFLICT,
                    "This message was removed in Gmail. The mailbox needs synchronization.");
            message.setGmailMessageId(result.gmailId());
            message.setTrashOriginLabels(value ? result.originLabels() : null);
            message.setTrashedFlag(result.trashed());
            message.setTrashedAt(result.trashed() ? Instant.now() : null);
            return;
        }
        if (message.isTrashedFlag() == value) return;
        String sourceName = message.getFolder().getFullName();
        boolean remoteTrash = "TRASH".equals(message.getFolder().getSpecialUse());
        if (value || remoteTrash) {
            MoveResult moved = moveRemote(message, value);
            if (moved != null) {
                MailFolder target =
                    folders.findByAccountIdAndFullName(message.getAccount().getId(), moved.name())
                        .orElseGet(() -> {
                            MailFolder created = new MailFolder();
                            created.setAccount(message.getAccount());
                            created.setFullName(moved.name());
                            created.setDisplayName(moved.displayName());
                            return created;
                        });
                target.setUidValidity(moved.uidValidity());
                target.setSpecialUse(value ? "TRASH" : moved.specialUse());
                target = folders.save(target);
                if (value) message.setTrashOriginFolder(sourceName);
                message.setFolder(target);
                message.setUidValidity(moved.uidValidity());
                message.setImapUid(moved.uid());
            }
        }
        message.setTrashedFlag(value);
        message.setTrashedAt(value ? Instant.now() : null);
        if (!value) message.setTrashOriginFolder(null);
    }

    private MoveResult moveRemote(MailMessage local, boolean toTrash) {
        boolean google = "GOOGLE".equals(local.getAccount().getAuthProvider());
        try (Store store = connections.openImap(local.getAccount())) {
            if (!(store instanceof IMAPStore imapStore) || !imapStore.hasCapability("UIDPLUS")) {
                if (google)
                    throw new ApiException(
                        HttpStatus.BAD_GATEWAY,
                        "Gmail did not provide the required message move support.");
                return null;
            }
            Folder destination = toTrash ? trashFolder(store) : restoreFolder(store, local, google);
            if (destination == null || !destination.exists()) {
                if (google)
                    throw new ApiException(HttpStatus.BAD_GATEWAY,
                        "Gmail Trash folder is unavailable.");
                return null;
            }
            Folder source = store.getFolder(local.getFolder().getFullName());
            source.open(Folder.READ_WRITE);
            try {
                if (!(source instanceof IMAPFolder imapSource) ||
                    imapSource.getUIDValidity() != local.getUidValidity())
                    throw new ApiException(
                        HttpStatus.CONFLICT,
                        "The remote folder changed; synchronize before moving this message.");
                Message remote = imapSource.getMessageByUID(local.getImapUid());
                if (remote == null)
                    throw new ApiException(
                        HttpStatus.CONFLICT,
                        "This message is no longer in its remote folder. Synchronize first.");
                AppendUID[] mapping;
                if (imapStore.hasCapability("MOVE")) {
                    mapping = imapSource.moveUIDMessages(new Message[]{remote}, destination);
                } else {
                    mapping = imapSource.copyUIDMessages(new Message[]{remote}, destination);
                    if (mapping == null || mapping.length != 1 || mapping[0] == null)
                        throw new ApiException(
                            HttpStatus.BAD_GATEWAY,
                            "The mail server did not confirm the destination message.");
                    remote.setFlag(Flags.Flag.DELETED, true);
                    imapSource.expunge(new Message[]{remote});
                }
                if (mapping == null || mapping.length != 1 || mapping[0] == null ||
                    mapping[0].uid <= 0)
                    throw new ApiException(
                        HttpStatus.BAD_GATEWAY,
                        "The mail server moved the message without a destination ID."
                            + " Synchronize the account.");
                return new MoveResult(destination.getFullName(), destination.getName(),
                    mapping[0].uidvalidity, mapping[0].uid,
                    toTrash ? "TRASH"
                        : google ? "ALL"
                        : "INBOX".equalsIgnoreCase(destination.getFullName())
                        ? "INBOX"
                        : null);
            } finally {
                if (source.isOpen()) source.close(false);
            }
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                "The mail server did not accept the message move.");
        }
    }

    private Folder restoreFolder(Store store, MailMessage local, boolean google) throws Exception {
        if (google) {
            for (Folder folder : store.getDefaultFolder().list("*")) {
                if (folder instanceof IMAPFolder imap) {
                    for (String attribute : imap.getAttributes()) {
                        if ("\\All".equalsIgnoreCase(attribute)) return folder;
                    }
                }
            }
            return store.getFolder("INBOX");
        }
        String origin = local.getTrashOriginFolder();
        return store.getFolder(origin == null || origin.isBlank() ? "INBOX" : origin);
    }

    private Folder trashFolder(Store store) throws Exception {
        Folder[] available = store.getDefaultFolder().list("*");
        for (Folder folder : available) {
            if (folder instanceof IMAPFolder imap) {
                for (String attribute : imap.getAttributes()) {
                    if ("\\Trash".equalsIgnoreCase(attribute)) return folder;
                }
            }
        }
        for (Folder folder : available) {
            if (folder.getFullName().matches(
                "(?i)(?:.*[./])?(?:trash|deleted(?: items| messages)?|bin|çöp kutusu)"))
                return folder;
        }
        return null;
    }

    private void updateRemote(MailMessage local, Flags.Flag flag, boolean value) {
        try (Store store = connections.openImap(local.getAccount())) {
            Folder folder = store.getFolder(local.getFolder().getFullName());
            folder.open(Folder.READ_WRITE);
            try {
                if (!(folder instanceof UIDFolder uidFolder) ||
                    uidFolder.getUIDValidity() != local.getUidValidity()) {
                    throw new ApiException(
                        HttpStatus.CONFLICT,
                        "The remote folder identity changed; synchronize first.");
                }
                Message remote = uidFolder.getMessageByUID(local.getImapUid());
                if (remote == null)
                    throw new ApiException(HttpStatus.NOT_FOUND,
                        "The message no longer exists on the server.");
                remote.setFlag(flag, value);
            } finally {
                folder.close(false);
            }
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                "The mail server did not accept the flag change.");
        }
    }

    private record MoveResult(String name, String displayName, long uidValidity, long uid,
                              String specialUse) {
    }
}
