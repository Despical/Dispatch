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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * @author Despical
 * <p>
 * Created at 21.09.2026
 */
class TotpServiceTest {

    @Test
    void acceptsCurrentStepAndRejectsMalformedCodes() {
        Clock clock = Clock.fixed(Instant.parse("2026-09-21T12:00:00Z"), ZoneOffset.UTC);
        TotpService service = new TotpService(clock);
        String secret = "JBSWY3DPEHPK3PXP";
        String code = service.codeForStep(secret, service.currentStep());

        assertThat(service.verifyAndResolveStep(secret, code)).isEqualTo(service.currentStep());
        assertThat(service.verifyAndResolveStep(secret, "12345")).isEqualTo(-1);
        assertThat(service.verifyAndResolveStep(secret, "abcdef")).isEqualTo(-1);
    }
}
