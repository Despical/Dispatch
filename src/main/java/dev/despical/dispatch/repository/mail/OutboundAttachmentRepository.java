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

import dev.despical.dispatch.entity.mail.OutboundAttachment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

/**
 * @author Despical
 * <p>
 * Created at 21.09.2026
 */
public interface OutboundAttachmentRepository extends JpaRepository<OutboundAttachment, Long> {

    @Query("""
        select a from OutboundAttachment a where a.outboundMessage.account.owner.id = :ownerId
        or a.outboundMessage.createdBy.id = :ownerId
        """)
    List<OutboundAttachment> findAllByOutboundMessageAccountOwnerId(Long ownerId);

    List<OutboundAttachment> findAllByOutboundMessageIdOrderByIdAsc(Long outboundMessageId);
}
