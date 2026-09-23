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

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * @author Despical
 * <p>
 * Created at 23.09.2026
 */
@Service
@RequiredArgsConstructor
public class MailSharePermissionStore {
    private final JdbcTemplate jdbc;

    public List<AccountPermission> list(Long shareId) {
        return jdbc.query("SELECT account_id, can_view, can_send, can_organize, can_delete FROM"
                + " mail_share_accounts WHERE share_id = ? ORDER BY account_id",
            (row, ignored)
                -> new AccountPermission(row.getLong(1), row.getBoolean(2),
                row.getBoolean(3), row.getBoolean(4),
                row.getBoolean(5)),
            shareId);
    }

    public AccountPermission get(Long shareId, Long accountId) {
        var permissions = jdbc.query(
            "SELECT account_id, can_view, can_send, can_organize, can_delete FROM"
                + " mail_share_accounts WHERE share_id = ? AND account_id = ?",
            (row, ignored)
                -> new AccountPermission(row.getLong(1), row.getBoolean(2), row.getBoolean(3),
                row.getBoolean(4), row.getBoolean(5)),
            shareId, accountId);
        return permissions.isEmpty() ? null : permissions.getFirst();
    }

    public void save(Long shareId, AccountPermission permission) {
        int updated =
            jdbc.update("UPDATE mail_share_accounts SET can_view = ?, can_send = ?, can_organize ="
                    + " ?, can_delete = ? WHERE share_id = ? AND account_id = ?",
                permission.canView(), permission.canSend(), permission.canOrganize(),
                permission.canDelete(), shareId, permission.accountId());
        if (updated != 1)
            throw new IllegalStateException("Mailbox permission could not be stored.");
    }

    public record AccountPermission(Long accountId, boolean canView, boolean canSend,
                                    boolean canOrganize, boolean canDelete) {
    }
}
