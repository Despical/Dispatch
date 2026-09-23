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

import dev.despical.dispatch.dto.mail.MailDtos.AccountRequest;
import dev.despical.dispatch.dto.mail.MailDtos.AccountResponse;
import dev.despical.dispatch.entity.mail.MailAccount;
import dev.despical.dispatch.exception.ApiException;
import dev.despical.dispatch.mapper.MailAccountMapper;
import dev.despical.dispatch.repository.mail.MailAccountRepository;
import dev.despical.dispatch.repository.security.AdminUserRepository;
import dev.despical.dispatch.security.CryptoService;

import jakarta.mail.Store;
import jakarta.mail.Transport;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * @author Despical
 * <p>
 * Created at 21.09.2026
 */
@Service
@RequiredArgsConstructor
public class MailAccountService {

    private final MailAccountRepository accounts;
    private final MailAccountMapper mapper;
    private final CryptoService crypto;
    private final HostPolicy hostPolicy;
    private final MailConnectionFactory connections;
    private final HtmlSanitizerService sanitizer;
    private final AdminUserRepository admins;

    public List<AccountResponse> list(Long ownerId) {
        return accounts
            .findAllByOwnerIdAndActiveTrueOrderByDisplayOrderAscDisplayNameAscIdAsc(ownerId)
            .stream()
            .map(mapper::toResponse)
            .toList();
    }

    @Transactional
    public void reorder(Long ownerId, List<Long> accountIds) {
        List<MailAccount> owned =
            accounts.findAllByOwnerIdAndActiveTrueOrderByDisplayOrderAscDisplayNameAscIdAsc(
                ownerId);
        var byId =
            owned.stream().collect(Collectors.toMap(MailAccount::getId, Function.identity()));
        if (accountIds.size() != owned.size() || !new HashSet<>(accountIds).equals(byId.keySet())) {
            throw new ApiException(HttpStatus.CONFLICT,
                "Mail accounts have changed. Refresh the list and try again.");
        }
        for (int position = 0; position < accountIds.size(); position++) {
            byId.get(accountIds.get(position)).setDisplayOrder(position);
        }
    }

    @Transactional
    public AccountResponse create(Long ownerId, AccountRequest request) {
        hostPolicy.validate(request.imapHost());
        hostPolicy.validate(request.smtpHost());
        MailAccount account = new MailAccount();
        account.setOwner(admins.findById(ownerId).orElseThrow(
            () -> new ApiException(HttpStatus.NOT_FOUND, "Administrator not found.")));
        apply(account, request);
        testConnections(account);
        account.setSyncStatus("PENDING");
        return mapper.toResponse(accounts.save(account));
    }

    @Transactional
    public AccountResponse update(Long ownerId, Long id, AccountRequest request) {
        hostPolicy.validate(request.imapHost());
        hostPolicy.validate(request.smtpHost());
        MailAccount account =
            accounts.findByIdAndOwnerId(id, ownerId)
                .orElseThrow(
                    () -> new ApiException(HttpStatus.NOT_FOUND, "Mail account not found."));
        if ("GOOGLE".equals(account.getAuthProvider())) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "Reconnect Google accounts through Google authorization.");
        }
        apply(account, request);
        testConnections(account);
        account.setSyncStatus("PENDING");
        return mapper.toResponse(account);
    }

    @Transactional
    public MailAccount createGoogle(Long ownerId, String email, String refreshToken) {
        var existing =
            accounts.findAllByOwnerIdAndActiveTrueOrderByDisplayOrderAscDisplayNameAscIdAsc(ownerId)
                .stream()
                .filter(account -> account.getEmail().equalsIgnoreCase(email))
                .findFirst();
        if (existing.isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT,
                "This Google mailbox is already connected.");
        }
        MailAccount account = new MailAccount();
        account.setOwner(admins.findById(ownerId).orElseThrow(
            () -> new ApiException(HttpStatus.NOT_FOUND, "User not found.")));
        account.setDisplayName(email.substring(0, email.indexOf('@')));
        account.setEmail(email);
        account.setUsername(email);
        account.setImapHost("imap.gmail.com");
        account.setImapPort(993);
        account.setSmtpHost("smtp.gmail.com");
        account.setSmtpPort(587);
        account.setAuthProvider("GOOGLE");
        account.setEncryptedPassword(crypto.encrypt(refreshToken));
        account.setActive(true);
        account.setSyncStatus("PENDING");
        accounts.saveAndFlush(account);
        testConnections(account);
        return account;
    }

    @Transactional
    public void remove(Long ownerId, Long id) {
        MailAccount account =
            accounts.findByIdAndOwnerId(id, ownerId)
                .orElseThrow(
                    () -> new ApiException(HttpStatus.NOT_FOUND, "Mail account not found."));
        account.setActive(false);
        account.setEncryptedPassword(
            crypto.encrypt("removed-credential-" + java.util.UUID.randomUUID()));
        account.setSyncStatus("REMOVED");
        account.setSyncError(null);
    }

    @Transactional(readOnly = true)
    public void requireOwned(Long ownerId, Long id) {
        if (accounts.findByIdAndOwnerId(id, ownerId).isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Mail account not found.");
        }
    }

    private MailAccount apply(MailAccount account, AccountRequest request) {
        account.setDisplayName(request.displayName().trim());
        account.setEmail(request.email().trim());
        account.setImapHost(request.imapHost().trim());
        account.setImapPort(request.imapPort());
        account.setSmtpHost(request.smtpHost().trim());
        account.setSmtpPort(request.smtpPort());
        account.setUsername(request.username().trim());
        account.setEncryptedPassword(crypto.encrypt(request.password()));
        account.setSignatureHtml(sanitizer.sanitize(request.signatureHtml(), false));
        account.setActive(true);
        return account;
    }

    private void testConnections(MailAccount account) {
        try (Store ignored = connections.openImap(account);
             Transport ignoredSmtp = connections.openSmtp(account)) {
            // TLS handshakes, certificate hostname checks and authentication have completed.
        } catch (Exception exception) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "The mail servers could not be authenticated over verified TLS. Check the host,"
                    + " port and credentials.");
        }
    }
}
