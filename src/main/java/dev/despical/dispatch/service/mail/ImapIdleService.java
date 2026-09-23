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
import dev.despical.dispatch.repository.mail.MailAccountRepository;

import jakarta.annotation.PreDestroy;
import jakarta.mail.Folder;
import jakarta.mail.Store;

import lombok.RequiredArgsConstructor;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author Despical
 * <p>
 * Created at 21.09.2026
 */
@Service
@RequiredArgsConstructor
public class ImapIdleService {

    private final MailAccountRepository accounts;
    private final MailConnectionFactory connections;
    private final MailSyncService syncService;
    private final Set<Long> active = ConcurrentHashMap.newKeySet();
    private volatile boolean running = true;

    @Scheduled(fixedDelay = 30_000)
    public void ensureListeners() {
        for (MailAccount account : accounts.findAllByActiveTrueOrderByDisplayNameAsc()) {
            if (active.add(account.getId())) {
                Thread.ofPlatform().daemon().name("imap-idle-" + account.getId())
                    .start(() -> idleLoop(account.getId()));
            }
        }
    }

    private void idleLoop(Long accountId) {
        try {
            while (running) {
                MailAccount account = accounts.findById(accountId).orElse(null);
                if (account == null || !account.isActive()) return;
                try (Store store = connections.openImap(account)) {
                    Folder inbox = store.getFolder("INBOX");
                    inbox.open(Folder.READ_ONLY);
                    Method idle = findIdleMethod(inbox.getClass());
                    if (idle == null) return;
                    while (running && inbox.isOpen()) {
                        idle.invoke(inbox, true);
                        syncService.syncAccount(accountId);
                    }
                } catch (Exception ignored) {
                    Thread.sleep(5_000);
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            active.remove(accountId);
        }
    }

    private Method findIdleMethod(Class<?> type) {
        try {
            return type.getMethod("idle", boolean.class);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    @PreDestroy
    void stop() {
        running = false;
    }
}
