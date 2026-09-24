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

import dev.despical.dispatch.entity.mail.OutboundMessage;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * @author Despical
 * <p>
 * Created at 21.09.2026
 */
public interface OutboundMessageRepository extends JpaRepository<OutboundMessage, Long> {

    @EntityGraph(attributePaths = {"account", "account.owner", "createdBy"})
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from OutboundMessage m where m.id = :id")
    Optional<OutboundMessage> findForDelivery(Long id);

    @Query(
        "select m from OutboundMessage m where m.createdBy.id = :ownerId and m.status <>"
            + " 'DRAFT' and (:query = '' or lower(m.subject) like lower(concat('%', :query,"
            + " '%')) or lower(m.recipients) like lower(concat('%', :query, '%')))")
    Page<OutboundMessage> findSent(Long ownerId, String query, Pageable pageable);

    @Query(
        "select (count(m) > 0) from OutboundMessage m where (m.account.owner.id = :ownerId or"
            + " m.createdBy.id = :ownerId) and m.status = :status")
    boolean existsByAccountOwnerIdAndStatus(Long ownerId, OutboundMessage.Status status);

    @Query(
        "select m from OutboundMessage m where m.createdBy.id = :ownerId and m.status = :status"
            + " and m.trashedAt is null and (:query = '' or lower(m.subject) like"
            + " lower(concat('%', :query, '%')) or lower(m.recipients) like lower(concat('%',"
            + " :query, '%'))) ")
    Page<OutboundMessage> findDrafts(Long ownerId, OutboundMessage.Status status, String query, Pageable pageable);

    @Query(
        "select m from OutboundMessage m where m.createdBy.id = :ownerId and m.account.id = :accountId"
            + " and m.status = :status and m.trashedAt is null and (:query = '' or lower(m.subject)"
            + " like lower(concat('%', :query, '%')) or lower(m.recipients) like"
            + " lower(concat('%', :query, '%')))"
    )
    Page<OutboundMessage> findDraftsForAccount(
        Long ownerId, Long accountId, OutboundMessage.Status status, String query, Pageable pageable);

    long countByCreatedByIdAndStatusAndTrashedAtIsNull(Long ownerId, OutboundMessage.Status status);

    long countByCreatedByIdAndAccountIdAndStatusAndTrashedAtIsNull(
        Long ownerId, Long accountId, OutboundMessage.Status status);

    List<OutboundMessage> findAllByCreatedByIdAndStatusAndTrashedAtIsNotNull(Long ownerId, OutboundMessage.Status status);

    List<OutboundMessage> findAllByAccountIdAndCreatedByIdAndStatusAndTrashedAtIsNotNull(
        Long accountId, Long ownerId, OutboundMessage.Status status);

    List<OutboundMessage> findAllByStatusAndTrashedAtBefore(
        OutboundMessage.Status status, java.time.Instant cutoff);

    Optional<OutboundMessage> findByPublicIdAndCreatedById(UUID publicId, Long ownerId);

    Optional<OutboundMessage> findByIdempotencyKeyAndCreatedById(
        String idempotencyKey, Long ownerId);

    List<OutboundMessage> findTop20ByStatusInOrderByCreatedAtAsc(Collection<OutboundMessage.Status> statuses);

    long countByStatusIn(Collection<OutboundMessage.Status> statuses);
}
