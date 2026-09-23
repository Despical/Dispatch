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

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.despical.dispatch.entity.mail.SavedContact;
import dev.despical.dispatch.entity.security.AdminUser;
import dev.despical.dispatch.exception.ApiException;
import dev.despical.dispatch.repository.mail.SavedContactRepository;
import dev.despical.dispatch.repository.security.AdminUserRepository;
import dev.despical.dispatch.security.DispatchPrincipal;

import org.junit.jupiter.api.Test;

import java.util.*;

/**
 * @author Despical
 * <p>
 * Created at 22.09.2026
 */
class ContactControllerTest {
    final SavedContactRepository contacts = mock(SavedContactRepository.class);
    final AdminUserRepository admins = mock(AdminUserRepository.class);
    final ContactController controller = new ContactController(contacts, admins);
    final DispatchPrincipal principal =
        new DispatchPrincipal(42L, "owner@example.test", "Owner", UUID.randomUUID());

    @Test
    void foreignContactsCannotBeEditedDeletedOrRestored() {
        when(contacts.findByIdAndOwnerId(7L, 42L)).thenReturn(Optional.empty());
        assertThatThrownBy(
            () ->
                controller.update(
                    7L,
                    new ContactController.ContactRequest(
                        "Name", "name@example.test"),
                    principal))
            .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> controller.delete(7L, principal)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> controller.restore(7L, principal))
            .isInstanceOf(ApiException.class);
        assertThatThrownBy(
            () ->
                controller.star(
                    7L, new ContactController.StarRequest(true), principal))
            .isInstanceOf(ApiException.class);
    }

    @Test
    void starsPersistThroughDeleteAndRestoreAndCanBeRemoved() {
        var contact = new SavedContact();
        contact.setId(7L);
        when(contacts.findByIdAndOwnerId(7L, 42L)).thenReturn(Optional.of(contact));
        assertThat(
            controller
                .star(7L, new ContactController.StarRequest(true), principal)
                .starred())
            .isTrue();
        controller.delete(7L, principal);
        assertThatThrownBy(
            () ->
                controller.star(
                    7L, new ContactController.StarRequest(false), principal))
            .isInstanceOf(ApiException.class);
        assertThat(controller.restore(7L, principal).starred()).isTrue();
        assertThat(
            controller
                .star(7L, new ContactController.StarRequest(false), principal)
                .starred())
            .isFalse();
    }

    @Test
    void deleteAndUndoPreserveTheSameContact() {
        var contact = new SavedContact();
        contact.setId(7L);
        contact.setEmail("name@example.test");
        contact.setDisplayName("Name");
        when(contacts.findByIdAndOwnerId(7L, 42L)).thenReturn(Optional.of(contact));
        controller.delete(7L, principal);
        assertThat(contact.getDeletedAt()).isNotNull();
        assertThat(controller.restore(7L, principal).id()).isEqualTo(7L);
        assertThat(contact.getDeletedAt()).isNull();
        verify(contacts, never()).delete(any());
    }

    @Test
    void createNormalizesAddressesAndRejectsDuplicates() {
        when(admins.findById(42L)).thenReturn(Optional.of(new AdminUser()));
        when(contacts.save(any())).thenAnswer(call -> call.getArgument(0));
        var result =
            controller.create(
                new ContactController.ContactRequest(" Name ", "Name@Example.test"),
                principal);
        assertThat(result.email()).isEqualTo("name@example.test");
        assertThat(result.displayName()).isEqualTo("Name");
        when(contacts.findByOwnerIdAndEmailIgnoreCase(42L, "name@example.test"))
            .thenReturn(Optional.of(new SavedContact()));
        assertThatThrownBy(
            () ->
                controller.create(
                    new ContactController.ContactRequest(
                        "Name", "name@example.test"),
                    principal))
            .isInstanceOf(ApiException.class);
    }
}
