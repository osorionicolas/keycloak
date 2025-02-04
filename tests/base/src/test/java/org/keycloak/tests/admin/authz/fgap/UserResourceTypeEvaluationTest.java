/*
 * Copyright 2024 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.keycloak.tests.admin.authz.fgap;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.keycloak.authorization.AdminPermissionsSchema.IMPERSONATE;
import static org.keycloak.authorization.AdminPermissionsSchema.MANAGE;
import static org.keycloak.authorization.AdminPermissionsSchema.MANAGE_GROUP_MEMBERSHIP;
import static org.keycloak.authorization.AdminPermissionsSchema.MAP_ROLES;
import static org.keycloak.authorization.AdminPermissionsSchema.VIEW;

import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.admin.client.resource.ScopePermissionsResource;
import org.keycloak.authorization.AdminPermissionsSchema;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.representations.idm.authorization.Logic;
import org.keycloak.representations.idm.authorization.ScopePermissionRepresentation;
import org.keycloak.representations.idm.authorization.UserPolicyRepresentation;
import org.keycloak.testframework.annotations.InjectAdminClient;
import org.keycloak.testframework.annotations.InjectUser;
import org.keycloak.testframework.annotations.KeycloakIntegrationTest;
import org.keycloak.testframework.realm.ManagedUser;
import org.keycloak.testframework.realm.UserConfigBuilder;
import org.keycloak.testframework.util.ApiUtil;

@KeycloakIntegrationTest(config = KeycloakAdminPermissionsServerConfig.class)
public class UserResourceTypeEvaluationTest extends AbstractPermissionTest {

    @InjectUser(ref = "alice")
    ManagedUser userAlice;

    @InjectAdminClient(mode = InjectAdminClient.Mode.MANAGED_REALM, client = "myclient", user = "myadmin")
    Keycloak realmAdminClient;

    private final String usersType = AdminPermissionsSchema.USERS.getType();

    private final String newUserUsername = "new_user";

    @AfterEach
    public void onAfter() {
        ScopePermissionsResource permissions = getScopePermissionsResource(client);

        for (ScopePermissionRepresentation permission : permissions.findAll(null, null, null, -1, -1)) {
            permissions.findById(permission.getId()).remove();
        }

        realm.admin().users().search(newUserUsername).forEach(user -> realm.admin().users().get(user.getId()).remove());
    }

    @Test
    public void testSingleUserPermission() {
        UserRepresentation myadmin = realm.admin().users().search("myadmin").get(0);
        UserPolicyRepresentation allowMyAdminPermission = createUserPolicy(realm, client, "Only My Admin User Policy", myadmin.getId());
        // allow my admin to see alice only
        createPermission(client, userAlice.admin().toRepresentation().getId(), usersType, Set.of(VIEW), allowMyAdminPermission);
        List<UserRepresentation> search = realmAdminClient.realm(realm.getName()).users().search(null, -1, -1);
        assertEquals(1, search.size());
        assertEquals(userAlice.getUsername(), search.get(0).getUsername());
    }

    @Test
    public void testImpersonatePermission() {
        // myadmin shouldn't be able to impersonate user just yet
        try {
            realmAdminClient.realm(realm.getName()).users().get(userAlice.getId()).impersonate();
            fail("Expected Exception wasn't thrown.");
        } catch (Exception ex) {
            assertThat(ex, instanceOf(ForbiddenException.class));
        }

        //create all-users permission for "myadmin" (so that myadmin can impersonate all users in the realm)
        UserPolicyRepresentation policy = createUserPolicy(realm, client,"Only My Admin User Policy", realm.admin().users().search("myadmin").get(0).getId());
        createAllPermission(client, usersType, policy, Set.of(IMPERSONATE));

        // create user permission forbidding the impersonation for userAlice
        String cannotImpersonateAlice = createPermission(client, Logic.NEGATIVE, userAlice.getId(), usersType, Set.of(IMPERSONATE)).getName();

        // even though "myadmin" has permission to impersonate all users in realm it should be denied to impersonate userAlice
        try {
            realmAdminClient.realm(realm.getName()).users().get(userAlice.getId()).impersonate();
            fail("Expected Exception wasn't thrown.");
        } catch (Exception ex) {
            assertThat(ex, instanceOf(ForbiddenException.class));
        }

        // remove the negative permission
        String cannotImpersonateAliceId = getScopePermissionsResource(client).findByName(cannotImpersonateAlice).getId();
        getScopePermissionsResource(client).findById(cannotImpersonateAliceId).remove();
        
        // need to create a separate client for the impersonation call, otherwise next usage of the 'realmAdminClient' would throw 401
        try (Keycloak adminClient = KeycloakBuilder.builder()
                .serverUrl("http://localhost:8080")
                .realm(realm.getName())
                .clientId("myclient")
                .clientSecret("mysecret")
                .username("myadmin")
                .password("password")
                .build()) {
            // now it should be possible to impersonate the user due to fallback to all-users permission
            adminClient.realm(realm.getName()).users().get(userAlice.getId()).impersonate();
        }
    }

    @Test
    public void testManageAllPermission() {
        // myadmin shouldn't be able to create user just yet
        try (Response response = realmAdminClient.realm(realm.getName()).users().create(UserConfigBuilder.create().username(newUserUsername).build())) {
            assertEquals(Response.Status.FORBIDDEN.getStatusCode(), response.getStatus());
        }

        //create all-users permission for "myadmin" (so that myadmin can manage all users in the realm)
        UserPolicyRepresentation policy = createUserPolicy(realm, client,"Only My Admin User Policy", realm.admin().users().search("myadmin").get(0).getId());
        createAllPermission(client, usersType, policy, Set.of(MANAGE));

        // creating user requires manage scope
        String newUserId = ApiUtil.handleCreatedResponse(realmAdminClient.realm(realm.getName()).users().create(UserConfigBuilder.create().username(newUserUsername).build()));

        // it should be possible to update the user due to fallback to all-users permission
        realmAdminClient.realm(realm.getName()).users().get(newUserId).update(UserConfigBuilder.create().email("new@test.com").build());
        assertEquals("new@test.com", realmAdminClient.realm(realm.getName()).users().get(newUserId).toRepresentation().getEmail());
    }

    @Test
    public void testManageUserPermission() {
        String myadminId = realm.admin().users().search("myadmin").get(0).getId();
        UserPolicyRepresentation policy = createUserPolicy(realm, client,"Only My Admin User Policy", myadminId);
        ScopePermissionRepresentation allUsersPermission = createAllPermission(client, usersType, policy, Set.of(MANAGE));

        // creating user requires manage scope
        String newUserId = ApiUtil.handleCreatedResponse(realmAdminClient.realm(realm.getName()).users().create(UserConfigBuilder.create().username(newUserUsername).build()));

        // remove all-users permissions to test user-permission
        allUsersPermission = getScopePermissionsResource(client).findByName(allUsersPermission.getName());
        getScopePermissionsResource(client).findById(allUsersPermission.getId()).remove();

        // create user-permissions
        createPermission(client, UserConfigBuilder.create().id(newUserId).build().getId(), usersType, Set.of(MANAGE), policy);

        // it should be possible to update the user due to single user-permission
        realmAdminClient.realm(realm.getName()).users().get(newUserId).update(UserConfigBuilder.create().email("email@test.com").build());
        assertEquals("email@test.com", realmAdminClient.realm(realm.getName()).users().get(newUserId).toRepresentation().getEmail());

        // remove the user permission
        getScopePermissionsResource(client).findAll(null, null, null, null, null).forEach(permission -> {
            getScopePermissionsResource(client).findById(permission.getId()).remove();
        });

        // updating the user should be denied
        try {
            realmAdminClient.realm(realm.getName()).users().get(newUserId).update(UserConfigBuilder.create().email("email@test.com").build());
            fail("Expected Exception wasn't thrown.");
        } catch (Exception ex) {
            assertThat(ex, instanceOf(ForbiddenException.class));
        }
    }

    @Test
    public void testViewAllPermission() {
        UserPolicyRepresentation policy = createUserPolicy(realm, client,"Only My Admin User Policy", realm.admin().users().search("myadmin").get(0).getId());
        ScopePermissionRepresentation permission = createAllPermission(client, usersType, policy, Set.of(VIEW));
        List<UserRepresentation> search = realmAdminClient.realm(realm.getName()).users().search(null, -1, -1);
        assertFalse(search.isEmpty());

        permission = client.admin().authorization().permissions().scope().findByName(permission.getName());
        permission.setPolicies(Set.of());
        client.admin().authorization().permissions().scope().findById(permission.getId()).update(permission);
        search = realmAdminClient.realm(realm.getName()).users().search(null, -1, -1);
        assertTrue(search.isEmpty());
    }

    @Test
    public void testViewUserPermission() {
        UserRepresentation myadmin = realm.admin().users().search("myadmin").get(0);
        UserPolicyRepresentation allowMyAdminPermission = createUserPolicy(realm, client,"Only My Admin User Policy", myadmin.getId());
        createAllPermission(client, usersType, allowMyAdminPermission, Set.of(VIEW));

        UserPolicyRepresentation denyMyAdminAccessingHisAccountPermission = createUserPolicy(realm, client,"Not My Admin User Policy", myadmin.getId(), Logic.NEGATIVE);
        createPermission(client, myadmin.getId(), usersType, Set.of(VIEW), denyMyAdminAccessingHisAccountPermission);
        List<UserRepresentation> search = realmAdminClient.realm(realm.getName()).users().search(null, -1, -1);
        assertEquals(1, search.size());
        assertEquals(userAlice.getUsername(), search.get(0).getUsername());
    }

    @Test
    public void testViewUserPermissionDenyByDefault() {
        String myadminId = realm.admin().users().search("myadmin").get(0).getId();
        UserPolicyRepresentation disallowMyAdmin = createUserPolicy(realm, client,"Not My Admin User Policy", myadminId, Logic.NEGATIVE);
        createAllPermission(client, usersType, disallowMyAdmin, Set.of(VIEW));

        UserPolicyRepresentation allowAliceOnlyForMyAdmin = createUserPolicy(realm, client,"My Admin User Policy", myadminId);
        createPermission(client, userAlice.getId(), usersType, Set.of(VIEW), allowAliceOnlyForMyAdmin);

        List<UserRepresentation> search = realmAdminClient.realm(realm.getName()).users().search(null, -1, -1);
        assertEquals(1, search.size());
        assertEquals(userAlice.getUsername(), search.get(0).getUsername());
    }

    @Test
    public void testMapRoles() {
        RoleRepresentation testRole = new RoleRepresentation();
        testRole.setName("testRole");
        realm.admin().roles().create(testRole);
        realm.cleanup().add(r -> r.roles().get("testRole").remove());

        // myadmin shouldn't be able to map roles to the user just yet
        try {
            realmAdminClient.realm(realm.getName()).users().get(userAlice.getId()).roles().realmLevel().add(List.of(testRole));
            fail("Expected Exception wasn't thrown.");
        } catch (Exception ex) {
            assertThat(ex, instanceOf(ForbiddenException.class));
        }

        //create all-users permission for "myadmin" (so that myadmin can map roles to users in the realm)
        UserPolicyRepresentation policy = createUserPolicy(realm, client,"Only My Admin User Policy", realm.admin().users().search("myadmin").get(0).getId());
        ScopePermissionRepresentation allUsersPermission = createAllPermission(client, usersType, policy, Set.of(MAP_ROLES));

        try {
            realmAdminClient.realm(realm.getName()).users().get(userAlice.getId()).roles().realmLevel().add(List.of(testRole));
            fail("Expected Exception wasn't thrown.");
        } catch (Exception ex) {
            // succsessfully checked the requireMapRoles https://github.com/keycloak/keycloak/blob/792b673f49d5faeed8b3bb2c61fb4a3b404df695/services/src/main/java/org/keycloak/services/resources/admin/RoleMapperResource.java#L235
            // expecting here NotFoundException: https://github.com/keycloak/keycloak/blob/792b673f49d5faeed8b3bb2c61fb4a3b404df695/services/src/main/java/org/keycloak/services/resources/admin/RoleMapperResource.java#L243
            assertThat(ex, instanceOf(NotFoundException.class));
            // successful role mapping would require permission to map individual role https://github.com/keycloak/keycloak/blob/792b673f49d5faeed8b3bb2c61fb4a3b404df695/services/src/main/java/org/keycloak/services/resources/admin/RoleMapperResource.java#L245
            // and it's not implemented yet for V2
        }

        // remove all-users permissions to test user-permission
        allUsersPermission = getScopePermissionsResource(client).findByName(allUsersPermission.getName());
        getScopePermissionsResource(client).findById(allUsersPermission.getId()).remove();

        // now myadmin cannot map roles
        try {
            realmAdminClient.realm(realm.getName()).users().get(userAlice.getId()).roles().realmLevel().add(List.of(testRole));
            fail("Expected Exception wasn't thrown.");
        } catch (Exception ex) {
            assertThat(ex, instanceOf(ForbiddenException.class));
        }

        // create userPermission
        createPermission(client, userAlice.getId(), usersType, Set.of(MAP_ROLES), policy);

        //check myadmin can map roles
        try {
            realmAdminClient.realm(realm.getName()).users().get(userAlice.getId()).roles().realmLevel().add(List.of(testRole));
            fail("Expected Exception wasn't thrown.");
        } catch (Exception ex) {
            // expecting here NotFoundException: https://github.com/keycloak/keycloak/blob/792b673f49d5faeed8b3bb2c61fb4a3b404df695/services/src/main/java/org/keycloak/services/resources/admin/RoleMapperResource.java#L243
            assertThat(ex, instanceOf(NotFoundException.class));
        }
    }

    @Test
    public void testManageGroupMembership() {
        // myadmin shouldn't be able to manage group membership of the user just yet
        try {
            realmAdminClient.realm(realm.getName()).users().get(userAlice.getId()).joinGroup("no-such");
            fail("Expected Exception wasn't thrown.");
        } catch (Exception ex) {
            assertThat(ex, instanceOf(ForbiddenException.class));
        }

        //create all-users permission for "myadmin" (so that myadmin can add users into a group)
        UserPolicyRepresentation policy = createUserPolicy(realm, client,"Only My Admin User Policy", realm.admin().users().search("myadmin").get(0).getId());
        ScopePermissionRepresentation allUsersPermission = createAllPermission(client, usersType, policy, Set.of(MANAGE_GROUP_MEMBERSHIP));

        //check myadmin can manage membership using all-users permission
        try {
            realmAdminClient.realm(realm.getName()).users().get(userAlice.getId()).joinGroup("no-such");
            fail("Expected Exception wasn't thrown.");
        } catch (Exception ex) {
            // expecting here NotFoundException: https://github.com/keycloak/keycloak/blob/b5c95e9f1c58bc500316dd5c0f2d3bb5e197ca99/services/src/main/java/org/keycloak/services/resources/admin/UserResource.java#L1060
            assertThat(ex, instanceOf(NotFoundException.class));
        }

        // remove all-users permissions to test user-permission
        allUsersPermission = getScopePermissionsResource(client).findByName(allUsersPermission.getName());
        getScopePermissionsResource(client).findById(allUsersPermission.getId()).remove();

        // now myadmin cannot manage membership
        try {
            realmAdminClient.realm(realm.getName()).users().get(userAlice.getId()).joinGroup("no-such");
            fail("Expected Exception wasn't thrown.");
        } catch (Exception ex) {
            assertThat(ex, instanceOf(ForbiddenException.class));
        }

        // create userPermission
        createPermission(client, userAlice.getId(), usersType, Set.of(MANAGE_GROUP_MEMBERSHIP), policy);

        //check myadmin can manage membership using individual permission
        try {
            realmAdminClient.realm(realm.getName()).users().get(userAlice.getId()).joinGroup("no-such");
            fail("Expected Exception wasn't thrown.");
        } catch (Exception ex) {
            // expecting here NotFoundException: https://github.com/keycloak/keycloak/blob/b5c95e9f1c58bc500316dd5c0f2d3bb5e197ca99/services/src/main/java/org/keycloak/services/resources/admin/UserResource.java#L1060
            assertThat(ex, instanceOf(NotFoundException.class));
        }
    }

    @Test
    public void testTransitiveUserPermissions() {
        //create all-users manage permission for "myadmin"
        UserPolicyRepresentation policy = createUserPolicy(realm, client,"Only My Admin User Policy", realm.admin().users().search("myadmin").get(0).getId());
        createAllPermission(client, usersType, policy, Set.of(MANAGE));

        // with manage permission it is possible also view
        List<UserRepresentation> search = realmAdminClient.realm(realm.getName()).users().search(null, -1, -1);
        assertFalse(search.isEmpty());

        // with manage permission it is possible also map roles
        try {
            realmAdminClient.realm(realm.getName()).users().get(userAlice.getId()).roles().realmLevel().add(List.of(new RoleRepresentation()));
            fail("Expected Exception wasn't thrown.");
        } catch (Exception ex) {
            // expecting here NotFoundException: https://github.com/keycloak/keycloak/blob/792b673f49d5faeed8b3bb2c61fb4a3b404df695/services/src/main/java/org/keycloak/services/resources/admin/RoleMapperResource.java#L243
            assertThat(ex, instanceOf(NotFoundException.class));
        }

        // with manage permission it is possible also manage group membership
        try {
            realmAdminClient.realm(realm.getName()).users().get(userAlice.getId()).joinGroup("no-such");
            fail("Expected Exception wasn't thrown.");
        } catch (Exception ex) {
            // expecting here NotFoundException: https://github.com/keycloak/keycloak/blob/b5c95e9f1c58bc500316dd5c0f2d3bb5e197ca99/services/src/main/java/org/keycloak/services/resources/admin/UserResource.java#L1060
            assertThat(ex, instanceOf(NotFoundException.class));
        }

        // with manage permission it is NOT possible to impersonate
        try {
            realmAdminClient.realm(realm.getName()).users().get(userAlice.getId()).impersonate();
            fail("Expected Exception wasn't thrown.");
        } catch (Exception ex) {
            assertThat(ex, instanceOf(ForbiddenException.class));
        }
    }
}
