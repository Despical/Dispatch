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
package dev.despical.dispatch.service.security;

import dev.despical.dispatch.entity.mail.OutboundMessage;
import dev.despical.dispatch.exception.ApiException;
import dev.despical.dispatch.repository.mail.AttachmentRepository;
import dev.despical.dispatch.repository.mail.OutboundAttachmentRepository;
import dev.despical.dispatch.repository.mail.OutboundMessageRepository;
import dev.despical.dispatch.repository.security.AdminUserRepository;
import dev.despical.dispatch.service.mail.AttachmentService;
import dev.despical.dispatch.service.mail.OutboundAttachmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

/**
 * @author Despical
 * <p>
 * Created at 22.09.2026
 */
@Service
@RequiredArgsConstructor
public class AdminLifecycleService {

    private final AdminUserRepository admins;
    private final AttachmentRepository attachments;
    private final OutboundAttachmentRepository outgoingAttachments;
    private final AttachmentService files;
    private final OutboundAttachmentService outgoingFiles;
    private final OutboundMessageRepository outbox;
    private final SecurityEventService events;

    @Transactional
    public void enable(Long id, Long actor, String ip) {
        var user = admins.findByIdForUpdate(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Administrator not found."));
        user.setEnabled(true);

        events.record(id, "ADMIN_ENABLED", "SUCCESS", ip, "Enabled by administrator " + actor);
    }

    @Transactional
    public void delete(Long id, Long actor, String ip) {
        if (id.equals(actor)) {
            throw new ApiException(HttpStatus.CONFLICT, "You cannot delete your own administrator account.");
        }

        var user = admins.findByIdForUpdate(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Administrator not found."));
        if (user.isEnabled()) {
            throw new ApiException(HttpStatus.CONFLICT, "Disable this administrator before deleting the account.");
        }

        if (outbox.existsByAccountOwnerIdAndStatus(id, OutboundMessage.Status.SENDING)) {
            throw new ApiException(HttpStatus.CONFLICT, "A mail delivery is still in progress. Try again after it finishes.");
        }

        var paths = new ArrayList<Path>();
        attachments.findAllByMessageAccountOwnerId(id)
            .stream()
            .filter(item -> !"rejected".equals(item.getStoragePath()))
            .forEach(item -> paths.add(files.resolveForDownload(item)));

        outgoingAttachments.findAllByOutboundMessageAccountOwnerId(id).forEach(
            item -> paths.add(outgoingFiles.resolve(item)));
        admins.delete(user);
        admins.flush();

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {

            @Override
            public void afterCommit() {
                for (var path : paths)
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException _) {
                    }

                events.record(actor, "ADMIN_DELETED", "SUCCESS", ip, "Deleted administrator " + id);
            }
        });
    }
}
