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
package dev.despical.dispatch.service.system;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Despical
 * <p>
 * Created at 24.09.2026
 */
class MonitoringMemoryTest {

    private static final long GIB = 1073741824L;

    @Test
    void excludesReclaimableCacheFromUsedHostMemory() {
        String memInfo = "MemTotal: 8388608 kB\nMemFree: 524288 kB\n" +
            "MemAvailable: 4194304 kB\nCached: 3670016 kB\n";

        assertThat(MonitoringService.usedMemoryBytes(8 * GIB, GIB / 2, memInfo))
            .isEqualTo(4 * GIB);
    }

    @Test
    void keepsContainerMemoryLimitsWhenHostMeminfoDoesNotMatch() {
        String hostMemInfo = "MemTotal: 8388608 kB\nMemAvailable: 4194304 kB\n";

        assertThat(MonitoringService.usedMemoryBytes(2 * GIB, GIB / 2, hostMemInfo))
            .isEqualTo(3 * GIB / 2);
    }
}
