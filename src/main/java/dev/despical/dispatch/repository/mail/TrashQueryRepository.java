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
package dev.despical.dispatch.repository.mail;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Types;

/**
 * @author Despical
 * <p>
 * Created at 22.09.2026
 */
@Repository
@RequiredArgsConstructor
public class TrashQueryRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public Page<Entry> list(
        Long ownerId,
        Long mailboxOwnerId,
        Long accountId,
        String filter,
        String query,
        Pageable pageable
    ) {
        String union =
            """
                SELECT m.id, FALSE AS draft, m.trashed_at FROM mail_messages m
                JOIN mail_accounts a ON a.id = m.mail_account_id
                WHERE a.owner_admin_user_id = :mailboxOwner AND (CAST(:accountId AS BIGINT) IS NULL OR a.id = :accountId) AND m.trashed_flag = TRUE
                  AND :filter <> 'drafts'
                  AND (:filter <> 'unread' OR m.read_flag = FALSE)
                  AND (:filter <> 'starred' OR m.starred_flag = TRUE)
                  AND (:query = '' OR LOWER(m.subject) LIKE :pattern OR LOWER(m.from_address) LIKE :pattern OR LOWER(m.text_body) LIKE :pattern)
                UNION ALL
                SELECT d.id, TRUE AS draft, d.trashed_at FROM outbound_messages d
                JOIN mail_accounts a ON a.id = d.mail_account_id
                WHERE d.created_by_id = :owner AND (CAST(:accountId AS BIGINT) IS NULL OR a.id = :accountId) AND d.status = 'DRAFT' AND d.trashed_at IS NOT NULL
                  AND :filter IN ('all', 'drafts')
                  AND (:query = '' OR LOWER(d.subject) LIKE :pattern OR LOWER(d.recipients) LIKE :pattern OR LOWER(d.body_text) LIKE :pattern)
                """;

        var params =
            new MapSqlParameterSource()
                .addValue("owner", ownerId)
                .addValue("mailboxOwner", mailboxOwnerId)
                .addValue("accountId", accountId, Types.BIGINT)
                .addValue("filter", filter)
                .addValue("query", query)
                .addValue("pattern", "%" + query.toLowerCase(java.util.Locale.ROOT) + "%")
                .addValue("limit", pageable.getPageSize())
                .addValue("offset", pageable.getOffset());

        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM (" + union + ") items", params, Long.class);
        var rows =
            jdbc.query(
                "SELECT * FROM ("
                    + union
                    + ") items ORDER BY trashed_at DESC, draft, id DESC LIMIT :limit"
                    + " OFFSET :offset",
                params,
                (rs, _) -> new Entry(rs.getLong("id"), rs.getBoolean("draft")));
        return new PageImpl<>(rows, pageable, count == null ? 0 : count);
    }

    public long count(Long ownerId, Long mailboxOwnerId, Long accountId) {
        var params = new MapSqlParameterSource()
            .addValue("owner", ownerId)
            .addValue("mailboxOwner", mailboxOwnerId)
            .addValue("accountId", accountId, Types.BIGINT);
        Long count = jdbc.queryForObject(
            """
                SELECT (SELECT COUNT(*) FROM mail_messages m JOIN mail_accounts a ON a.id = m.mail_account_id
                        WHERE a.owner_admin_user_id = :mailboxOwner AND (CAST(:accountId AS BIGINT) IS NULL OR a.id = :accountId) AND m.trashed_flag = TRUE)
                     + (SELECT COUNT(*) FROM outbound_messages d JOIN mail_accounts a ON a.id = d.mail_account_id
                        WHERE d.created_by_id = :owner AND (CAST(:accountId AS BIGINT) IS NULL OR a.id = :accountId)
                          AND d.status = 'DRAFT' AND d.trashed_at IS NOT NULL)
                """,
            params,
            Long.class);
        return count == null ? 0 : count;
    }

    public record Entry(long id, boolean draft) {
    }
}
