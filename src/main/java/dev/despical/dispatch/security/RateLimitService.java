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
package dev.despical.dispatch.security;

import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author Despical
 * <p>
 * Created at 21.09.2026
 */
@Service
public class RateLimitService {

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LoginFailures> loginFailures = new ConcurrentHashMap<>();
    private final Clock clock = Clock.systemUTC();

    public boolean allowLogin(String email) {
        long now = clock.instant().getEpochSecond();
        AtomicBoolean allowed = new AtomicBoolean();

        loginFailures.compute(
            email,
            (ignored, current) -> {
                if (current != null && current.lockedUntil > now) return current;
                int count = current == null
                    || current.lockedUntil != 0
                    || now - current.lastAttemptAt > 900
                    ? 1
                    : current.count + 1;
                allowed.set(true);
                return new LoginFailures(count, count >= 5 ? now + 900 : 0, now);
            });

        if (loginFailures.size() > 20_000) {
            loginFailures.entrySet().removeIf(entry -> now - entry.getValue().lastAttemptAt > 3600);
        }

        return allowed.get();
    }

    public void loginSucceeded(String email) {
        loginFailures.remove(email);
    }

    public boolean allow(String key, int capacity, long windowSeconds) {
        long now = clock.instant().getEpochSecond();
        Window result = windows.compute(
            key,
            (ignored, current) -> {
                if (current == null || now >= current.startedAt + windowSeconds) {
                    return new Window(now, 1);
                }
                return new Window(current.startedAt, current.count + 1);
            });

        if (windows.size() > 20_000) {
            windows.entrySet().removeIf(entry -> now - entry.getValue().startedAt > 3600);
        }

        return result.count <= capacity;
    }

    private record Window(long startedAt, int count) {
    }

    private record LoginFailures(int count, long lockedUntil, long lastAttemptAt) {
    }
}
