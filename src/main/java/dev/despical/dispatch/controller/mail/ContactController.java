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
package dev.despical.dispatch.controller.mail;

import dev.despical.dispatch.entity.mail.SavedContact;
import dev.despical.dispatch.exception.ApiException;
import dev.despical.dispatch.repository.mail.SavedContactRepository;
import dev.despical.dispatch.repository.security.AdminUserRepository;
import dev.despical.dispatch.security.DispatchPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * @author Despical
 * <p>
 * Created at 22.09.2026
 */
@RestController
@RequestMapping("/api/mail/contacts")
@RequiredArgsConstructor
public class ContactController {

    private final SavedContactRepository contacts;
    private final AdminUserRepository admins;

    private ContactView view(SavedContact item) {
        return new ContactView(
            item.getId(), item.getDisplayName(), item.getEmail(), item.isStarred());
    }

    private SavedContact owned(Long id, Long owner) {
        return contacts.findByIdAndOwnerId(id, owner)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Contact not found."));
    }

    @GetMapping
    List<ContactView> list(@AuthenticationPrincipal DispatchPrincipal principal) {
        return contacts
            .findAllByOwnerIdAndDeletedAtIsNullOrderByDisplayNameAsc(principal.adminId())
            .stream()
            .map(this::view)
            .toList();
    }

    @PostMapping
    @Transactional
    ContactView create(
        @Valid @RequestBody ContactRequest request,
        @AuthenticationPrincipal DispatchPrincipal principal
    ) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        var existing = contacts.findByOwnerIdAndEmailIgnoreCase(principal.adminId(), email);

        if (existing.isPresent() && existing.get().getDeletedAt() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "This email is already saved in your contacts.");
        }

        SavedContact contact = existing.orElseGet(SavedContact::new);
        if (contact.getOwner() == null) {
            contact.setOwner(admins.findById(principal.adminId()).orElseThrow());
        }

        contact.setDeletedAt(null);
        contact.setEmail(email);
        contact.setDisplayName(request.displayName().trim());
        return view(contacts.save(contact));
    }

    @PutMapping("/{id}")
    @Transactional
    ContactView update(
        @PathVariable Long id,
        @Valid @RequestBody ContactRequest request,
        @AuthenticationPrincipal DispatchPrincipal principal
    ) {
        var contact = owned(id, principal.adminId());
        if (contact.getDeletedAt() != null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Contact not found.");
        }

        String email = request.email().trim().toLowerCase(Locale.ROOT);

        if (contacts.findByOwnerIdAndEmailIgnoreCase(principal.adminId(), email)
            .filter(other -> !other.getId().equals(id))
            .isPresent()
        ) {
            throw new ApiException(HttpStatus.CONFLICT, "This email is already saved in your contacts.");
        }

        contact.setEmail(email);
        contact.setDisplayName(request.displayName().trim());
        return view(contact);
    }

    @DeleteMapping("/{id}")
    @Transactional
    void delete(@PathVariable Long id, @AuthenticationPrincipal DispatchPrincipal principal) {
        owned(id, principal.adminId()).setDeletedAt(Instant.now());
    }

    @PutMapping("/{id}/star")
    @Transactional
    ContactView star(
        @PathVariable Long id,
        @Valid @RequestBody StarRequest request,
        @AuthenticationPrincipal DispatchPrincipal principal
    ) {
        var contact = owned(id, principal.adminId());
        if (contact.getDeletedAt() != null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Contact not found.");
        }

        contact.setStarred(request.starred());
        return view(contact);
    }

    @PostMapping("/{id}/restore")
    @Transactional
    ContactView restore(
        @PathVariable Long id, @AuthenticationPrincipal DispatchPrincipal principal) {
        var contact = owned(id, principal.adminId());
        contact.setDeletedAt(null);
        return view(contact);
    }

    public record ContactRequest(
        @NotBlank @Size(max = 120) String displayName,
        @NotBlank @Email @Size(max = 190) String email) {
    }

    public record ContactView(Long id, String displayName, String email, boolean starred) {
    }

    public record StarRequest(@NotNull Boolean starred) {
    }
}
