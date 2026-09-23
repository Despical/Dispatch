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
package dev.despical.dispatch.service.mail;

import dev.despical.dispatch.entity.mail.MailMessage;
import dev.despical.dispatch.exception.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * @author Despical
 * <p>
 * Created at 23.09.2026
 */
@Service
public class GmailMessageService {

    private static final URI API = URI.create("https://gmail.googleapis.com/gmail/v1/users/me/messages/");

    private final GoogleOAuthService oauth;
    private final ObjectMapper json;
    private final HttpClient http;

    @Autowired
    public GmailMessageService(GoogleOAuthService oauth, ObjectMapper json) {
        this(oauth, json, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    GmailMessageService(GoogleOAuthService oauth, ObjectMapper json, HttpClient http) {
        this.oauth = oauth;
        this.json = json;
        this.http = http;
    }

    static String originLabels(Set<String> labels) {
        List<String> origin = new ArrayList<>();
        if (labels.contains("INBOX")) origin.add("INBOX");
        labels.stream().filter(label -> label.startsWith("CATEGORY_"))
            .sorted().forEach(origin::add);
        return String.join(",", origin);
    }

    static List<String> originalLabelsToRestore(String origin, Set<String> current) {
        List<String> missing = new ArrayList<>();
        boolean hasCurrentCategory = current.stream().anyMatch(label -> label.startsWith("CATEGORY_"));
        for (String label : origin.split(",")) {
            if ("INBOX".equals(label) && !current.contains(label)) missing.add(label);
            else if (label.startsWith("CATEGORY_") && !hasCurrentCategory && !current.contains(label))
                missing.add(label);
        }
        return missing;
    }

    public MoveResult move(MailMessage message, boolean toTrash) {
        String id = message.getGmailMessageId();
        if (id == null || id.isBlank()) id = findByInternetMessageId(message);
        if (id == null) throw new ApiException(HttpStatus.CONFLICT,
            "This Gmail message needs synchronization before it can be changed.");

        State current = get(message, id);
        if (current == null) return MoveResult.notFound();
        String origin = message.getTrashOriginLabels();
        if (toTrash && !current.trashed()) origin = originLabels(current.labels());

        if (current.trashed() != toTrash) {
            current = postAndVerify(message, id, toTrash ? "trash" : "untrash", toTrash);
        }

        if (!toTrash && !current.trashed() && origin != null && !origin.isBlank()) {
            List<String> missing = originalLabelsToRestore(origin, current.labels());
            if (!missing.isEmpty()) {
                try {
                    State modified = post(message, id, "modify",
                        json.writeValueAsString(Map.of("addLabelIds", missing, "removeLabelIds", List.of())));
                    if (modified != null) current = modified;
                } catch (Exception ignored) {
                    // The message is already out of Trash. A category repair must not turn a
                    // successful restore into a failed operation.
                }
            }
        }
        return new MoveResult(id, current.trashed(), origin, false);
    }

    public MoveResult inspect(MailMessage message) {
        String id = message.getGmailMessageId();
        if (id == null || id.isBlank()) id = findByInternetMessageId(message);
        if (id == null) return MoveResult.notFound();
        State current = get(message, id);
        return current == null ? MoveResult.notFound()
            : new MoveResult(id, current.trashed(), message.getTrashOriginLabels(), false);
    }

    private String findByInternetMessageId(MailMessage message) {
        String internetId = message.getInternetMessageId();
        if (internetId == null || internetId.isBlank()) {
            throw new ApiException(HttpStatus.CONFLICT,
                "This Gmail message needs synchronization before it can be changed.");
        }
        String query = "rfc822msgid:" + internetId.trim();
        URI uri = URI.create(API.toString().substring(0, API.toString().length() - 1)
            + "?includeSpamTrash=true&q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
            + "&maxResults=2");
        JsonNode result = request(message, uri, "GET", null);
        if (result == null) return null;
        JsonNode matches = result.path("messages");
        if (!matches.isArray() || matches.isEmpty()) return null;
        if (matches.size() != 1) {
            throw new ApiException(HttpStatus.CONFLICT,
                "Gmail returned more than one match. Synchronize this mailbox first.");
        }
        String id = matches.get(0).path("id").asText("");
        return id.isBlank() ? null : id;
    }

    private State get(MailMessage message, String id) {
        JsonNode result = request(message, URI.create(API + id + "?format=minimal"), "GET", null);
        return result == null ? null : state(result);
    }

    private State postAndVerify(MailMessage message, String id, String action, boolean expected) {
        try {
            State result = post(message, id, action, "");
            if (result != null && result.trashed() == expected) return result;
        } catch (ApiException ignored) {
            // Gmail may have applied the action before the response was lost. Read the
            // provider state before reporting a failure.
        }
        State latest = get(message, id);
        if (latest != null && latest.trashed() == expected) return latest;
        if (latest == null) throw new ApiException(HttpStatus.CONFLICT,
            "This message was removed in Gmail. Synchronize the mailbox.");
        throw new ApiException(HttpStatus.BAD_GATEWAY,
            "Gmail did not confirm the message change. The mailbox will be refreshed.");
    }

    private State post(MailMessage message, String id, String action, String body) {
        JsonNode result = request(message, URI.create(API + id + "/" + action), "POST", body);
        return result == null ? null : state(result);
    }

    public MoveResult deleteIfTrashed(MailMessage message) {
        String id = message.getGmailMessageId();
        if (id == null || id.isBlank()) id = findByInternetMessageId(message);
        if (id == null) return MoveResult.notFound();
        State current = get(message, id);
        if (current == null) return MoveResult.notFound();
        if (!current.trashed())
            return new MoveResult(id, false, message.getTrashOriginLabels(), false);
        try {
            request(message, URI.create(API + id), "DELETE", null);
        } catch (ApiException ignored) {
            // The request may have reached Gmail even when its response was lost.
        }
        if (get(message, id) == null) return MoveResult.notFound();
        throw new ApiException(HttpStatus.BAD_GATEWAY,
            "Gmail did not confirm permanent deletion. The mailbox will be refreshed.");
    }

    private JsonNode request(MailMessage message, URI uri, String method, String body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + oauth.accessToken(message.getAccount()));
            if ("DELETE".equals(method)) builder.DELETE();
            else if ("GET".equals(method)) builder.GET();
            else builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) return null;
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "Gmail did not confirm the message change. Try synchronizing the mailbox.");
            }
            if (response.body() == null || response.body().isBlank()) return null;
            return json.readTree(response.body());
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                "Gmail is unavailable. The mailbox will be refreshed when it responds.");
        }
    }

    private State state(JsonNode message) {
        Set<String> labels = new LinkedHashSet<>();
        for (JsonNode label : message.path("labelIds")) {
            if (label.isString()) labels.add(label.asText());
        }
        return new State(labels.contains("TRASH"), labels);
    }

    public record MoveResult(String gmailId, boolean trashed, String originLabels, boolean missing) {
        static MoveResult notFound() {
            return new MoveResult(null, false, null, true);
        }
    }

    private record State(boolean trashed, Set<String> labels) {
    }
}
