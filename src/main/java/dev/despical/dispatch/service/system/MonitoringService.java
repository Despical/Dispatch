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

import com.sun.management.OperatingSystemMXBean;
import dev.despical.dispatch.repository.mail.MailAccountRepository;
import dev.despical.dispatch.service.mail.ClamAvService;
import dev.despical.dispatch.service.mail.MailSyncService;
import dev.despical.dispatch.service.mail.OutboundService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * @author Despical
 * <p>
 * Created at 23.09.2026
 */
@Service
public class MonitoringService {
    private final URI prometheus;
    private final URI alertmanager;
    private final ObjectMapper json;
    private final StorageUsageService storage;
    private final DataSource database;
    private final MailSyncService sync;
    private final OutboundService outbox;
    private final ClamAvService scanner;
    private final MailAccountRepository accounts;
    private final HttpClient http =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public MonitoringService(@Value("${dispatch.monitoring.prometheus-url}") String prometheus,
                             @Value("${dispatch.monitoring.alertmanager-url}") String alertmanager,
                             ObjectMapper json, StorageUsageService storage, DataSource database,
                             MailSyncService sync, OutboundService outbox, ClamAvService scanner,
                             MailAccountRepository accounts) {
        this.prometheus = URI.create(prometheus);
        this.alertmanager = URI.create(alertmanager);
        this.json = json;
        this.storage = storage;
        this.database = database;
        this.sync = sync;
        this.outbox = outbox;
        this.scanner = scanner;
        this.accounts = accounts;
    }

    private static String bytes(long value) {
        return String.format(java.util.Locale.ROOT, "%.1f GB", Math.max(0, value) / 1073741824.0);
    }

    private static int percent(long used, long total) {
        return total <= 0 ? 0 : (int) Math.min(100, Math.max(0, Math.round(used * 100.0 / total)));
    }

    private static String diskState(int percent) {
        return percent >= 95 ? "DOWN" : percent >= 90 ? "WARN" : "UP";
    }

    private static String state(int percent) {
        return percent >= 90 ? "DOWN" : percent >= 75 ? "WARN" : "UP";
    }

    private static long elapsed(long started) {
        return Duration.ofNanos(System.nanoTime() - started).toMillis();
    }

    private static String formatUptime(long millis) {
        long minutes = Duration.ofMillis(millis).toMinutes();
        long days = minutes / 1440;
        long hours = minutes % 1440 / 60;
        return (days > 0 ? days + "d " : "") + hours + "h " + minutes % 60 + "m";
    }

    public Status current() {
        Instant checkedAt = Instant.now();
        List<ServiceCheck> services = new ArrayList<>();
        // Reaching this controller proves that Dispatch is handling requests;
        // dependencies are measured independently below.
        String application = "UP";
        services.add(new ServiceCheck("Dispatch application", "Application",
            "Admin request handling is available.", application, 0L));
        long started = System.nanoTime();
        String databaseStatus = "DOWN";
        try (var connection = database.getConnection()) {
            databaseStatus = connection.isValid(2) ? "UP" : "DOWN";
        } catch (Exception ignored) {
            // A failed connection is reported honestly below.
        }
        services.add(new ServiceCheck("Database", "Data",
            "UP".equals(databaseStatus) ? "Primary application database connection is healthy."
                : "The application cannot connect to its database.", databaseStatus,
            elapsed(started)));
        String syncStatus = "UNKNOWN";
        if (accounts.count() == 0) syncStatus = "UP";
        else if (sync.getLastSuccessfulSync() != null)
            syncStatus = Duration.between(sync.getLastSuccessfulSync(), checkedAt).toMinutes() > 15
                ? "DOWN"
                : "UP";
        services.add(new ServiceCheck("Mail sync", "Email",
            "DOWN".equals(syncStatus) ? "No successful mailbox synchronization in the last 15 minutes."
                : "UNKNOWN".equals(syncStatus) ? "No successful mailbox synchronization has been recorded."
                : "Connected mailbox synchronization is current.", syncStatus, null));
        long pending = outbox.pendingCount();
        services.add(new ServiceCheck("Outgoing mail", "Email",
            pending > 100 ? pending + " messages pending in the outbox (limit: 100)."
                : pending + " message" + (pending == 1 ? "" : "s") +
                    " pending in the outbox.",
            pending > 100 ? "DOWN" : "UP", null));
        boolean scannerAvailable = scanner.isAvailable();
        services.add(new ServiceCheck("Attachment scanner", "Security",
            scannerAvailable ? "Attachment scanning is available."
                : "Attachment scanning is unavailable; downloads remain blocked.",
            scannerAvailable ? "UP" : "DOWN", null));
        String host = "UNKNOWN";
        String prometheusStatus = "UNKNOWN";
        started = System.nanoTime();
        try {
            JsonNode result = get(prometheus.resolve(
                "/api/v1/query?query=" + URLEncoder.encode("up", StandardCharsets.UTF_8)));
            if ("success".equals(result.path("status").asText())) {
                prometheusStatus = "UP";
                for (JsonNode item : result.path("data").path("result")) {
                    String job = item.path("metric").path("job").asText();
                    String value = item.path("value").path(1).asText();
                    if ("node".equals(job)) host = "1".equals(value) ? "UP" : "DOWN";
                }
            }
        } catch (Exception ignored) {
            // Monitoring is unavailable; never present missing telemetry as healthy.
        }
        services.add(new ServiceCheck("Prometheus", "Monitoring",
            "UP".equals(prometheusStatus) ? "Metrics collection is available."
                : "Metrics collection could not be reached.",
            prometheusStatus, elapsed(started)));
        services.add(new ServiceCheck("Node exporter", "Monitoring",
            "DOWN".equals(host) ? "The host metrics scrape target is down."
                : "UNKNOWN".equals(host) ? "The host metrics scrape target could not be checked."
                : "Host metrics are being collected.", host, null));
        String alertmanagerStatus = "UNKNOWN";
        List<Alert> alerts = new ArrayList<>();
        started = System.nanoTime();
        try {
            JsonNode response = get(alertmanager.resolve("/api/v2/alerts"));
            if (response.isArray()) {
                alertmanagerStatus = "UP";
                for (JsonNode item : response) {
                    if (!"active".equals(item.path("status").path("state").asText())) continue;
                    alerts.add(new Alert(
                        item.path("labels").path("alertname").asText("Alert"),
                        item.path("labels").path("severity").asText("warning"),
                        item.path("annotations").path("summary").asText("Monitoring alert")));
                }
            }
        } catch (Exception ignored) {
            // Alertmanager might be unreachable while the application is available.
        }
        services.add(new ServiceCheck("Alertmanager", "Monitoring",
            "UP".equals(alertmanagerStatus) ? "Alert grouping is available."
                : "Alertmanager could not be reached.", alertmanagerStatus,
            elapsed(started)));
        Capacity capacity = capacity();
        String overall =
            services.stream().anyMatch(item -> "DOWN".equals(item.state())) || !alerts.isEmpty()
                || capacity.resources().stream().anyMatch(item -> "DOWN".equals(item.state()) || "WARN".equals(item.state()))
                ? "ISSUE"
                : services.stream().anyMatch(item -> !"UP".equals(item.state()))
                    || capacity.resources().stream().anyMatch(item -> "UNKNOWN".equals(item.state())) ? "UNKNOWN"
                : "OPERATIONAL";
        return new Status(overall, application, host, alertmanagerStatus, alerts, checkedAt,
            capacity, services);
    }

    private Capacity capacity() {
        List<Resource> resources = new ArrayList<>();
        try {
            var disk = storage.current();
            resources.add(new Resource("Disk space",
                bytes(disk.totalBytes() - disk.usedBytes()) + " free",
                bytes(disk.totalBytes()) + " total", disk.usedPercent(),
                diskState(disk.usedPercent())));
        } catch (Exception ignored) {
            resources.add(new Resource("Disk space", "Unavailable",
                "Storage volume could not be measured.", 0, "UNKNOWN"));
        }
        String load = "Unavailable";
        String uptime = "Unavailable";
        try {
            OperatingSystemMXBean os =
                (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            long total = os.getTotalMemorySize();
            long free = os.getFreeMemorySize();
            int memory = percent(total - free, total);
            resources.add(new Resource("Memory", memory + "% used",
                bytes(total - free) + " of " + bytes(total), memory,
                state(memory)));
            double cpu = os.getCpuLoad();
            int cpuPercent = cpu < 0 ? 0 : (int) Math.round(cpu * 100);
            resources.add(new Resource("CPU load", cpu < 0 ? "Unavailable" : cpuPercent + "%",
                "Recent system CPU usage", cpuPercent,
                cpu < 0 ? "UNKNOWN" : state(cpuPercent)));
            double average = os.getSystemLoadAverage();
            if (average >= 0) load = String.format(java.util.Locale.ROOT, "%.2f", average);
            uptime = formatUptime(ManagementFactory.getRuntimeMXBean().getUptime());
        } catch (Exception ignored) {
            resources.add(new Resource("Memory", "Unavailable",
                "Host memory could not be measured.", 0, "UNKNOWN"));
            resources.add(new Resource("CPU load", "Unavailable", "Host CPU could not be measured.",
                0, "UNKNOWN"));
        }
        return new Capacity(resources, load, uptime);
    }

    private JsonNode get(URI uri) throws Exception {
        HttpRequest request =
            HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(4)).GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200)
            throw new IllegalStateException("Monitoring endpoint unavailable");
        return json.readTree(response.body());
    }

    public record Alert(String name, String severity, String summary) {
    }

    public record Resource(String name, String value, String detail, int percent, String state) {
    }

    public record Capacity(List<Resource> resources, String load, String uptime) {
    }

    public record ServiceCheck(String name, String category, String detail, String state,
                               Long latencyMs) {
    }

    public record Status(String overall, String application, String host, String alertmanager,
                         List<Alert> alerts, Instant checkedAt, Capacity capacity,
                         List<ServiceCheck> services) {
    }
}
