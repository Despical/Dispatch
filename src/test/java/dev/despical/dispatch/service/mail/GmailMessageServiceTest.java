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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.despical.dispatch.entity.mail.MailAccount;
import dev.despical.dispatch.entity.mail.MailMessage;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Set;

/**
 * @author Despical
 * <p>
 * Created at 23.09.2026
 */
class GmailMessageServiceTest {

    @Test
    void trashCapturesInboxCategoryAndRestoreReappliesIt() throws Exception {
        var oauth = mock(GoogleOAuthService.class);
        var http = mock(HttpClient.class);
        var account = new MailAccount();
        account.setId(5L);
        var message = new MailMessage();
        message.setAccount(account);
        message.setGmailMessageId("abc123");
        when(oauth.accessToken(account)).thenReturn("test-token");
        var beforeTrash = response("{\"labelIds\":[\"INBOX\",\"CATEGORY_SOCIAL\"]}");
        var inTrash = response("{\"labelIds\":[\"TRASH\"]}");
        var afterUntrash = response("{\"labelIds\":[]}");
        var restoredLabels = response("{\"labelIds\":[\"INBOX\",\"CATEGORY_SOCIAL\"]}");
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenReturn(beforeTrash, inTrash, inTrash, afterUntrash, restoredLabels);

        var service = new GmailMessageService(oauth, new ObjectMapper(), http);
        var trashed = service.move(message, true);
        assertThat(trashed.trashed()).isTrue();
        assertThat(trashed.originLabels()).isEqualTo("INBOX,CATEGORY_SOCIAL");

        message.setTrashOriginLabels(trashed.originLabels());
        var restored = service.move(message, false);
        assertThat(restored.trashed()).isFalse();
        assertThat(GmailMessageService.originalLabelsToRestore(
            trashed.originLabels(), Set.of())).containsExactly("INBOX", "CATEGORY_SOCIAL");
    }

    @Test
    void staleProviderRestoreIsNotDeletedAgain() throws Exception {
        var oauth = mock(GoogleOAuthService.class);
        var http = mock(HttpClient.class);
        var account = new MailAccount();
        account.setId(5L);
        var message = new MailMessage();
        message.setAccount(account);
        message.setGmailMessageId("abc123");
        when(oauth.accessToken(account)).thenReturn("test-token");
        var inbox = response("{\"labelIds\":[\"INBOX\"]}");
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenReturn(inbox);

        var state = new GmailMessageService(oauth, new ObjectMapper(), http)
            .deleteIfTrashed(message);
        assertThat(state.missing()).isFalse();
        assertThat(state.trashed()).isFalse();
        verify(http).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<String> response(String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(body);
        return response;
    }
}
