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

import dev.despical.dispatch.dto.mail.MailDtos.PageResponse;
import dev.despical.dispatch.entity.security.AuthSession;
import dev.despical.dispatch.repository.security.AuthSessionRepository;
import dev.despical.dispatch.repository.security.SecurityEventRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * @author Despical
 * <p>
 * Created at 22.09.2026
 */
@Service
@RequiredArgsConstructor
public class SecurityActivityService {
    private final SecurityEventRepository events;
    private final AuthSessionRepository sessions;

    @Transactional(readOnly = true)
    public Activity activity(Long ownerId, UUID currentSessionId, int page) {
        var history = events
            .findByAdminUserIdAndEventTypeIn(
                ownerId,
                List.of("LOGIN_PASSWORD", "LOGIN_2FA", "LOGOUT", "LOGOUT_ALL",
                    "REAUTH", "REFRESH_REUSE"),
                PageRequest.of(Math.max(0, page), 20,
                    Sort.by(Sort.Direction.DESC, "createdAt", "id")))
            .map(event
                -> new EventView(event.getEventType(), event.getOutcome(),
                event.getIpAddress(), event.getCreatedAt()));
        Instant now = Instant.now();
        var active =
            sessions.findAllByAdminUserIdAndRevokedAtIsNull(ownerId)
                .stream()
                .filter(session -> session.isActiveAt(now))
                .sorted(Comparator
                    .comparing(AuthSession::getLastSeenAt)
                    .reversed())
                .map(session
                    -> new SessionView(session.getPublicId().equals(currentSessionId),
                    session.getIpAddress(), session.getUserAgent(),
                    session.getCreatedAt(), session.getLastSeenAt()))
                .toList();
        return new Activity(
            active, new PageResponse<>(history.getContent(), history.getNumber(), history.getSize(),
            history.getTotalElements(), history.getTotalPages()));
    }

    public record Activity(List<SessionView> sessions, PageResponse<EventView> events) {
    }

    public record EventView(String type, String outcome, String ip, Instant time) {
    }

    public record SessionView(boolean current, String ip, String userAgent, Instant startedAt,
                              Instant lastSeenAt) {
    }
}
