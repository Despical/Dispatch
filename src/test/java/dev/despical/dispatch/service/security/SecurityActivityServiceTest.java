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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.despical.dispatch.entity.security.AuthSession;
import dev.despical.dispatch.entity.security.SecurityEvent;
import dev.despical.dispatch.repository.security.AuthSessionRepository;
import dev.despical.dispatch.repository.security.SecurityEventRepository;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * @author Despical
 * <p>
 * Created at 22.09.2026
 */
class SecurityActivityServiceTest {
    @Test
    void scopesBothQueriesToOwnerAndOnlyReturnsActiveSessionsWithoutSecrets() {
        var events = mock(SecurityEventRepository.class);
        var sessions = mock(AuthSessionRepository.class);
        UUID current = UUID.randomUUID();
        AuthSession active = new AuthSession();
        active.setPublicId(current);
        active.setAbsoluteExpiresAt(Instant.now().plusSeconds(60));
        active.setLastSeenAt(Instant.now());
        active.setIpAddress("127.0.0.1");
        active.setCurrentRefreshHash("never-expose");
        AuthSession expired = new AuthSession();
        expired.setAbsoluteExpiresAt(Instant.now().minusSeconds(1));
        SecurityEvent event = new SecurityEvent();
        event.setEventType("LOGOUT");
        event.setOutcome("SUCCESS");
        event.setIpAddress("127.0.0.2");
        when(events.findByAdminUserIdAndEventTypeIn(eq(42L), anyCollection(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(event)));
        when(sessions.findAllByAdminUserIdAndRevokedAtIsNull(42L))
            .thenReturn(List.of(active, expired));
        var result = new SecurityActivityService(events, sessions).activity(42L, current, 0);
        assertThat(result.sessions()).hasSize(1);
        assertThat(result.sessions().getFirst().current()).isTrue();
        assertThat(result.toString())
            .doesNotContain("never-expose")
            .doesNotContain(current.toString());
        assertThat(result.events().content().getFirst().type()).isEqualTo("LOGOUT");
        verify(events)
            .findByAdminUserIdAndEventTypeIn(eq(42L), anyCollection(), any(Pageable.class));
        verify(sessions).findAllByAdminUserIdAndRevokedAtIsNull(42L);
    }
}
