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

import dev.despical.dispatch.entity.mail.MailAccount;
import dev.despical.dispatch.exception.ApiException;
import dev.despical.dispatch.security.CryptoService;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author Despical
 * <p>
 * Created at 23.09.2026
 */
@Service
public class GoogleOAuthService {

    private static final String AUTHORIZE = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String TOKEN = "https://oauth2.googleapis.com/token";
    private static final String PROFILE = "https://gmail.googleapis.com/gmail/v1/users/me/profile";

    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;
    private final JdbcTemplate jdbc;
    private final CryptoService crypto;
    private final ObjectMapper json;
    private final MailAccountService accounts;
    private final HttpClient http =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final SecureRandom random = new SecureRandom();
    private final Map<Long, AccessToken> accessTokens = new ConcurrentHashMap<>();

    public GoogleOAuthService(@Value("${dispatch.google.client-id:}") String clientId,
                              @Value("${dispatch.google.client-secret:}") String clientSecret,
                              @Value("${dispatch.google.redirect-uri:}") String redirectUri,
                              JdbcTemplate jdbc, CryptoService crypto, ObjectMapper json,
                              MailAccountService accounts) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.redirectUri = redirectUri;
        this.jdbc = jdbc;
        this.crypto = crypto;
        this.json = json;
        this.accounts = accounts;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public boolean configured() {
        if (clientId.isBlank() || clientSecret.isBlank() || redirectUri.isBlank()) return false;

        try {
            URI uri = URI.create(redirectUri);
            return "https".equalsIgnoreCase(uri.getScheme()) ||
                "http".equalsIgnoreCase(uri.getScheme()) &&
                    "localhost".equalsIgnoreCase(uri.getHost());
        } catch (IllegalArgumentException _) {
            return false;
        }
    }

    public String start(Long ownerId) {
        if (!configured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "Google connection is not configured yet.");
        }

        jdbc.update("DELETE FROM google_oauth_flows WHERE expires_at < ?",
            Timestamp.from(Instant.now()));

        byte[] stateBytes = new byte[32];
        byte[] verifierBytes = new byte[32];
        random.nextBytes(stateBytes);
        random.nextBytes(verifierBytes);

        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(stateBytes);
        String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(verifierBytes);
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
            java.util.HexFormat.of().parseHex(CryptoService.sha256(verifier)));
        jdbc.update("INSERT INTO google_oauth_flows (state_hash, owner_user_id,"
                + " encrypted_code_verifier, expires_at) VALUES (?, ?, ?, ?)",
            CryptoService.sha256(state), ownerId, crypto.encrypt(verifier),
            Timestamp.from(Instant.now().plus(Duration.ofMinutes(10))));
        return AUTHORIZE + "?client_id=" + encode(clientId) +
            "&redirect_uri=" + encode(redirectUri) +
            "&response_type=code&scope=" + encode("https://mail.google.com/") +
            "&access_type=offline&prompt=consent&code_challenge_method=S256&code_challenge=" +
            encode(challenge) + "&state=" + encode(state);
    }

    @Transactional
    public Long complete(String state, String code) {
        if (!configured() || state == null || state.isBlank() || code == null || code.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Google connection was not completed.");
        }

        String hash = CryptoService.sha256(state);
        var rows = jdbc.query("SELECT owner_user_id, encrypted_code_verifier, expires_at FROM"
                + " google_oauth_flows WHERE state_hash = ? FOR UPDATE",
            (result, ignored)
                -> new Flow(result.getLong(1), result.getString(2),
                result.getTimestamp(3).toInstant()),
            hash);
        if (rows.isEmpty() || !Instant.now().isBefore(rows.getFirst().expiresAt())) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "Google connection expired. Start again.");
        }
        Flow flow = rows.getFirst();
        jdbc.update("DELETE FROM google_oauth_flows WHERE state_hash = ?", hash);
        JsonNode token =
            tokenRequest("grant_type=authorization_code&code=" + encode(code) + "&client_id=" +
                encode(clientId) + "&client_secret=" + encode(clientSecret) +
                "&redirect_uri=" + encode(redirectUri) +
                "&code_verifier=" + encode(crypto.decrypt(flow.encryptedVerifier())));
        String access = token.path("access_token").asText("");
        String refresh = token.path("refresh_token").asText("");
        if (access.isBlank() || refresh.isBlank()) {
            throw new ApiException(
                HttpStatus.BAD_GATEWAY,
                "Google did not grant offline mail access. Try connecting again.");
        }
        String email = profileEmail(access);
        MailAccount account = accounts.createGoogle(flow.ownerId(), email, refresh);
        accessTokens.put(
            account.getId(),
            new AccessToken(access, Instant.now().plusSeconds(
                Math.max(60, token.path("expires_in").asLong(3600) - 60))));
        return account.getId();
    }

    public String accessToken(MailAccount account) {
        AccessToken cached = accessTokens.get(account.getId());
        if (cached != null && Instant.now().isBefore(cached.expiresAt())) return cached.value();
        synchronized (accessTokens) {
            cached = accessTokens.get(account.getId());
            if (cached != null && Instant.now().isBefore(cached.expiresAt())) return cached.value();
            JsonNode token = tokenRequest("grant_type=refresh_token&refresh_token=" +
                encode(crypto.decrypt(account.getEncryptedPassword())) +
                "&client_id=" + encode(clientId) +
                "&client_secret=" + encode(clientSecret));
            String value = token.path("access_token").asText("");
            if (value.isBlank())
                throw new ApiException(
                    HttpStatus.BAD_GATEWAY,
                    "Google mail authorization has expired. Reconnect this account.");
            accessTokens.put(
                account.getId(),
                new AccessToken(value, Instant.now().plusSeconds(Math.max(
                    60, token.path("expires_in").asLong(3600) - 60))));
            return value;
        }
    }

    public void forget(Long accountId) {
        accessTokens.remove(accountId);
    }

    private JsonNode tokenRequest(String form) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(TOKEN))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
            HttpResponse<String> response =
                http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200)
                throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "Google authorization failed. Reconnect this account.");
            return json.readTree(response.body());
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                "Google authorization is unavailable. Try again later.");
        }
    }

    private String profileEmail(String accessToken) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(PROFILE))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();
            HttpResponse<String> response =
                http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200)
                throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "Google mail profile could not be verified.");
            String email = json.readTree(response.body()).path("emailAddress").asText("").trim();
            if (email.isBlank() || !email.contains("@"))
                throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "Google did not return a mail address.");
            return email;
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                "Google mail profile is unavailable. Try again later.");
        }
    }

    private record Flow(Long ownerId, String encryptedVerifier, Instant expiresAt) {
    }

    private record AccessToken(String value, Instant expiresAt) {
    }
}
