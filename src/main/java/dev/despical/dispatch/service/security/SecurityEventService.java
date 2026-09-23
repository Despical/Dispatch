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

import dev.despical.dispatch.entity.security.SecurityEvent;
import dev.despical.dispatch.repository.security.SecurityEventRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * @author Despical
 * <p>
 * Created at 21.09.2026
 */
@Service
@RequiredArgsConstructor
public class SecurityEventService {

    private final SecurityEventRepository repository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long adminId, String type, String outcome, String ip, String detail) {
        SecurityEvent event = new SecurityEvent();
        event.setAdminUserId(adminId);
        event.setEventType(type);
        event.setOutcome(outcome);
        event.setIpAddress(limit(ip, 64));
        event.setDetail(limit(detail, 255));
        repository.save(event);
    }

    private String limit(String value, int length) {
        if (value == null) {
            return null;
        }
        return value.length() <= length ? value : value.substring(0, length);
    }
}
