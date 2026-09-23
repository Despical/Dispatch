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
package dev.despical.dispatch.config;

import dev.despical.dispatch.repository.mail.MailAccountRepository;
import dev.despical.dispatch.service.mail.ClamAvService;
import dev.despical.dispatch.service.mail.MailSyncService;
import dev.despical.dispatch.service.mail.OutboundService;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.time.Instant;

/**
 * @author Despical
 * <p>
 * Created at 21.09.2026
 */
@Configuration
public class MonitoringConfig {

    public MonitoringConfig(
        MeterRegistry registry,
        MailSyncService sync,
        OutboundService outbox,
        ClamAvService clamAv,
        MailAccountRepository accounts) {
        Gauge.builder(
                "dispatch.mail.sync.age.seconds",
                sync,
                service ->
                    service.getLastSuccessfulSync() == null
                        ? 0
                        : Duration.between(
                            service.getLastSuccessfulSync(),
                            Instant.now())
                        .toSeconds())
            .description("Seconds since the last successful mail synchronization")
            .register(registry);
        Gauge.builder("dispatch.outbox.pending", outbox, OutboundService::pendingCount)
            .description("Queued, sending or uncertain outbound messages")
            .register(registry);
        Gauge.builder("dispatch.clamav.available", clamAv, service -> service.isAvailable() ? 1 : 0)
            .description("Whether ClamAV is reachable")
            .register(registry);
        Gauge.builder("dispatch.mail.accounts.active", accounts, MailAccountRepository::count)
            .description("Configured mail accounts including inactive history")
            .register(registry);
    }

    @Bean(name = "mailSync")
    HealthIndicator mailSyncHealth(MailSyncService sync, MailAccountRepository accounts) {
        return () -> {
            if (accounts.count() == 0) {
                return Health.up().withDetail("status", "no accounts configured").build();
            }

            if (sync.getLastSuccessfulSync() == null) {
                return Health.unknown().withDetail("status", "awaiting first sync").build();
            }

            long age = Duration.between(sync.getLastSuccessfulSync(), Instant.now()).toMinutes();
            return age > 15
                ? Health.down().withDetail("lastSuccessfulMinutesAgo", age).build()
                : Health.up().withDetail("lastSuccessfulMinutesAgo", age).build();
        };
    }

    @Bean(name = "outbox")
    HealthIndicator outboxHealth(OutboundService outbox) {
        return () ->
            outbox.pendingCount() > 100
                ? Health.down().withDetail("pending", outbox.pendingCount()).build()
                : Health.up().withDetail("pending", outbox.pendingCount()).build();
    }

    @Bean(name = "clamAv")
    HealthIndicator clamAvHealth(ClamAvService clamAv) {
        return () ->
            clamAv.isAvailable()
                ? Health.up().build()
                : Health.down()
                .withDetail(
                    "status",
                    "scanner unavailable; attachments are not trusted")
                .build();
    }
}
