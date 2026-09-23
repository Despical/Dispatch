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

import dev.despical.dispatch.entity.mail.Attachment;
import dev.despical.dispatch.entity.mail.MailAccount;
import dev.despical.dispatch.entity.mail.MailMessage;
import dev.despical.dispatch.entity.security.AdminUser;
import dev.despical.dispatch.exception.ApiException;
import dev.despical.dispatch.repository.mail.AttachmentRepository;
import dev.despical.dispatch.repository.mail.MailAccountRepository;
import dev.despical.dispatch.repository.mail.MailMessageRepository;
import dev.despical.dispatch.repository.mail.MailShareRepository;
import dev.despical.dispatch.repository.security.AdminUserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * @author Despical
 * <p>
 * Created at 22.09.2026
 */
@Service
@Transactional(readOnly = true)
public class MailAccessService {
    private final MailShareRepository shares;
    private final MailAccountRepository accounts;
    private final MailMessageRepository messages;
    private final AttachmentRepository attachments;
    private final AdminUserRepository admins;
    private final MailSharePermissionStore permissionStore;

    public MailAccessService(MailShareRepository shares, MailAccountRepository accounts,
                             MailMessageRepository messages, AttachmentRepository attachments,
                             AdminUserRepository admins) {
        this(shares, accounts, messages, attachments, admins, null);
    }

    @Autowired
    public MailAccessService(MailShareRepository shares, MailAccountRepository accounts,
                             MailMessageRepository messages, AttachmentRepository attachments,
                             AdminUserRepository admins, MailSharePermissionStore permissionStore) {
        this.shares = shares;
        this.accounts = accounts;
        this.messages = messages;
        this.attachments = attachments;
        this.admins = admins;
        this.permissionStore = permissionStore;
    }

    public AdminUser actor(Long id) {
        return admins.findById(id).filter(AdminUser::isEnabled).orElseThrow(this::denied);
    }

    public MailAccount account(Long actorId, Long accountId, Action action) {
        MailAccount account = accounts.findById(accountId).orElseThrow(this::denied);
        require(actorId, account, action);
        return account;
    }

    public void require(Long actorId, MailAccount account, Action action) {
        if (account.getOwner().getId().equals(actorId)) return;
        var share = shares.findByOwnerIdAndViewerId(account.getOwner().getId(), actorId)
            .orElseThrow(this::denied);
        if (!account.isActive() || !share.getOwner().isEnabled() ||
            !share.getViewer().isEnabled() || share.isHidden())
            throw denied();
        if (share.getAccounts().stream().noneMatch(a -> a.getId().equals(account.getId())))
            throw denied();
        var permission =
            permissionStore == null ? null : permissionStore.get(share.getId(), account.getId());
        if (permissionStore != null && permission == null) throw denied();
        boolean canView = permission == null ? share.isCanView() : permission.canView();
        boolean canSend = permission == null ? share.isCanSend() : permission.canSend();
        boolean canOrganize = permission == null ? share.isCanOrganize() : permission.canOrganize();
        boolean canDelete = permission == null ? share.isCanDelete() : permission.canDelete();
        boolean allowed = switch (action) {
            case VIEW -> canView;
            case SEND -> canSend;
            case ORGANIZE -> canView && canOrganize;
            case DELETE -> canView && canDelete;
        };
        if (!allowed) throw denied();
    }

    public MailMessage message(Long actorId, Long id, Action action) {
        var message = messages.findById(id).orElseThrow(this::denied);
        require(actorId, message.getAccount(), action);
        if (message.isTrashedFlag() && !message.getAccount().getOwner().getId().equals(actorId) &&
            action != Action.DELETE)
            require(actorId, message.getAccount(), Action.DELETE);
        return message;
    }

    public Attachment attachment(Long actorId, Long id) {
        var attachment = attachments.findById(id).orElseThrow(this::denied);
        message(actorId, attachment.getMessage().getId(), Action.VIEW);
        return attachment;
    }

    private ApiException denied() {
        return new ApiException(HttpStatus.NOT_FOUND,
            "This mail item is unavailable or you do not have access.");
    }

    public enum Action {VIEW, SEND, ORGANIZE, DELETE}
}
