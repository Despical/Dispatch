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

import dev.despical.dispatch.entity.mail.MailShare;
import dev.despical.dispatch.exception.ApiException;
import dev.despical.dispatch.repository.mail.MailAccountRepository;
import dev.despical.dispatch.repository.mail.MailFolderRepository;
import dev.despical.dispatch.repository.mail.MailShareRepository;
import dev.despical.dispatch.repository.security.AdminUserRepository;
import dev.despical.dispatch.service.mail.MailSharePermissionStore.AccountPermission;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/**
 * @author Despical
 * <p>
 * Created at 22.09.2026
 */
@Service
@Transactional(readOnly = true)
public class MailSharingService {
    private final MailShareRepository shares;
    private final AdminUserRepository admins;
    private final MailAccountRepository accounts;
    private final MailFolderRepository folders;
    private final MailSharePermissionStore permissionStore;

    public MailSharingService(MailShareRepository shares, AdminUserRepository admins,
                              MailAccountRepository accounts, MailFolderRepository folders) {
        this(shares, admins, accounts, folders, null);
    }

    @Autowired
    public MailSharingService(MailShareRepository shares, AdminUserRepository admins,
                              MailAccountRepository accounts, MailFolderRepository folders,
                              MailSharePermissionStore permissionStore) {
        this.shares = shares;
        this.admins = admins;
        this.accounts = accounts;
        this.folders = folders;
        this.permissionStore = permissionStore;
    }

    public Overview list(Long actorId) {
        return new Overview(
            shares.findAllByOwnerIdOrderByCreatedAtDesc(actorId)
                .stream()
                .map(s -> view(s, false))
                .toList(),
            shares.findAllByViewerIdOrderByCreatedAtDesc(actorId)
                .stream()
                .map(s -> view(s, true))
                .toList(),
            accounts.findAllByOwnerIdAndActiveTrueOrderByDisplayOrderAscDisplayNameAscIdAsc(actorId)
                .stream()
                .map(a
                    -> new SharedAccount(a.getId(), a.getEmail(), a.getDisplayName(),
                    a.getAuthProvider(), 0))
                .toList());
    }

    private Grant view(MailShare share, boolean incoming) {
        var user = incoming ? share.getOwner() : share.getViewer();
        var selected = share.getAccounts()
            .stream()
            .filter(a -> a.isActive())
            .sorted(Comparator.comparing(a -> a.getEmail()))
            .toList();
        var permissions =
            permissionStore == null
                ? selected.stream()
                .map(a
                    -> new AccountPermission(a.getId(), share.isCanView(), share.isCanSend(),
                    share.isCanOrganize(), share.isCanDelete()))
                .toList()
                : permissionStore.list(share.getId());
        var perAccount = permissions.stream().collect(
            java.util.stream.Collectors.toMap(AccountPermission::accountId, p -> p));
        var visible =
            incoming && !share.isHidden() && user.isEnabled()
                ? selected.stream()
                .filter(a -> {
                    var p = perAccount.get(a.getId());
                    return p != null && (p.canView() || p.canSend());
                })
                .map(a -> {
                    var p = perAccount.get(a.getId());
                    return new SharedAccount(
                        a.getId(), a.getEmail(), a.getDisplayName(), a.getAuthProvider(),
                        p.canView()
                            ? folders.findAllByAccountIdOrderByDisplayNameAsc(a.getId())
                            .stream()
                            .mapToInt(f -> Math.max(0, f.getUnreadCount()))
                            .sum()
                            : 0);
                })
                .toList()
                : List.<SharedAccount>of();
        return new Grant(share.getId(), user.getEmail(), user.getDisplayName(), user.isEnabled(),
            share.isHidden(),
            permissions.stream().anyMatch(AccountPermission::canView),
            permissions.stream().anyMatch(AccountPermission::canSend),
            permissions.stream().anyMatch(AccountPermission::canOrganize),
            permissions.stream().anyMatch(AccountPermission::canDelete),
            selected.stream().map(a -> a.getId()).toList(), visible, permissions);
    }

    @Transactional
    public void add(Long ownerId, Request request) {
        var owner = admins.findByIdForUpdate(ownerId)
            .filter(u -> u.isEnabled())
            .orElseThrow(() -> missing());
        var viewer =
            admins.findByEmailIgnoreCase(request.email().trim())
                .filter(u -> u.isEnabled())
                .orElseThrow(()
                    -> new ApiException(HttpStatus.BAD_REQUEST,
                    "Enter the sign-in email of an existing active"
                        + " Dispatch user."));
        if (viewer.getId().equals(ownerId))
            throw new ApiException(HttpStatus.BAD_REQUEST, "You already own these mail accounts.");
        if (shares.findByOwnerIdAndViewerId(ownerId, viewer.getId()).isPresent())
            throw new ApiException(
                HttpStatus.CONFLICT,
                "This person already has access. Edit their permissions instead.");
        var share = new MailShare();
        share.setOwner(owner);
        share.setViewer(viewer);
        apply(share, new Permissions(request.canView(), request.canSend(), request.canOrganize(),
            request.canDelete(), request.accountIds(),
            request.accountPermissions()));
        shares.save(share);
        if (permissionStore != null) {
            shares.flush();
            storePermissions(share,
                new Permissions(request.canView(), request.canSend(),
                    request.canOrganize(), request.canDelete(),
                    request.accountIds(), request.accountPermissions()));
        }
    }

    @Transactional
    public void update(Long ownerId, Long id, Permissions permissions) {
        var share = shares.findByIdAndOwnerId(id, ownerId).orElseThrow(this::missing);
        apply(share, permissions);
        if (permissionStore != null) {
            shares.flush();
            storePermissions(share, permissions);
        }
    }

    @Transactional
    public void remove(Long ownerId, Long id) {
        shares.delete(shares.findByIdAndOwnerId(id, ownerId).orElseThrow(this::missing));
    }

    @Transactional
    public void hide(Long viewerId, Long id, boolean hidden) {
        shares.findByIdAndViewerId(id, viewerId).orElseThrow(this::missing).setHidden(hidden);
    }

    private void apply(MailShare s, Permissions p) {
        if (p.accountIds() == null || p.accountIds().isEmpty() || p.accountIds().size() > 1000)
            throw new ApiException(HttpStatus.BAD_REQUEST, "Select at least one mail account.");
        var requested = new HashSet<>(p.accountIds());
        var selected = accounts
            .findAllByOwnerIdAndActiveTrueOrderByDisplayOrderAscDisplayNameAscIdAsc(
                s.getOwner().getId())
            .stream()
            .filter(a -> requested.contains(a.getId()))
            .toList();
        if (selected.size() != requested.size())
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "Select only your active mail accounts.");
        s.setCanView(p.canView());
        s.setCanSend(p.canSend());
        s.setCanOrganize(p.canOrganize());
        s.setCanDelete(p.canDelete());
        s.getAccounts().clear();
        s.getAccounts().addAll(selected);
    }

    private void storePermissions(MailShare share, Permissions input) {
        var requested = new HashSet<>(input.accountIds());
        List<AccountPermission> permissions =
            input.accountPermissions() == null
                ? input.accountIds()
                .stream()
                .map(id
                    -> new AccountPermission(id, input.canView(), input.canSend(),
                    input.canOrganize(), input.canDelete()))
                .toList()
                : input.accountPermissions();
        if (permissions.size() != requested.size() ||
            !permissions.stream()
                .map(AccountPermission::accountId)
                .collect(java.util.stream.Collectors.toSet())
                .equals(requested))
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "Set permissions for every selected mailbox.");
        for (var permission : permissions)
            permissionStore.save(share.getId(), permission);
    }

    private ApiException missing() {
        return new ApiException(HttpStatus.NOT_FOUND, "Sharing entry not found.");
    }

    public record Permissions(boolean canView, boolean canSend, boolean canOrganize,
                              boolean canDelete,
                              @NotNull @Size(min = 1, max = 1000) List<@NotNull Long> accountIds,
                              List<AccountPermission> accountPermissions) {
        public Permissions(boolean canView, boolean canSend, boolean canOrganize, boolean canDelete,
                           List<Long> accountIds) {
            this(canView, canSend, canOrganize, canDelete, accountIds, null);
        }
    }

    public record Request(@NotBlank @Email @Size(max = 190) String email, boolean canView,
                          boolean canSend, boolean canOrganize, boolean canDelete,
                          @NotNull @Size(min = 1, max = 1000) List<@NotNull Long> accountIds,
                          List<AccountPermission> accountPermissions) {
        public Request(String email, boolean canView, boolean canSend, boolean canOrganize,
                       boolean canDelete, List<Long> accountIds) {
            this(email, canView, canSend, canOrganize, canDelete, accountIds, null);
        }
    }

    public record SharedAccount(Long id, String email, String displayName, String authProvider,
                                int unreadCount) {
    }

    public record Grant(Long id, String email, String displayName, boolean enabled, boolean hidden,
                        boolean canView, boolean canSend, boolean canOrganize, boolean canDelete,
                        List<Long> accountIds, List<SharedAccount> accounts,
                        List<AccountPermission> accountPermissions) {
    }

    public record Overview(List<Grant> outgoing, List<Grant> incoming,
                           List<SharedAccount> ownAccounts) {
    }
}
