/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.security.audit.index;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.http.message.BasicHeader;
import org.elasticsearch.action.ActionFuture;
import org.elasticsearch.action.admin.indices.exists.indices.IndicesExistsResponse;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.support.PlainActionFuture;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.Requests;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.common.network.NetworkModule;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.test.SecurityIntegTestCase;
import org.elasticsearch.test.SecuritySettingsSource;
import org.elasticsearch.xpack.security.ScrollHelper;
import org.elasticsearch.xpack.security.audit.AuditTrail;
import org.elasticsearch.xpack.security.audit.AuditTrailService;
import org.elasticsearch.xpack.security.authc.AuthenticationService;
import org.elasticsearch.xpack.security.authc.support.UsernamePasswordToken;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;

import static org.elasticsearch.test.SecuritySettingsSource.TEST_PASSWORD_SECURE_STRING;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.iterableWithSize;

public class AuditTrailTests extends SecurityIntegTestCase {

    private static final String AUTHENTICATE_USER = "http_user";
    private static final String EXECUTE_USER = "exec_user";
    private static final String ROLE_CAN_RUN_AS = "can_run_as";
    private static final String ROLES = ROLE_CAN_RUN_AS + ":\n" + "  run_as: [ '" + EXECUTE_USER + "' ]\n";

    @Override
    public Settings nodeSettings(int nodeOrdinal) {
        return Settings.builder()
                .put(super.nodeSettings(nodeOrdinal))
                .put(NetworkModule.HTTP_ENABLED.getKey(), true)
                .put("xpack.security.audit.enabled", true)
                .put("xpack.security.audit.outputs", "index")
                .putList("xpack.security.audit.index.events.include", "access_denied", "authentication_failed", "run_as_denied")
                .build();
    }

    @Override
    public String configRoles() {
        return ROLES + super.configRoles();
    }

    @Override
    public String configUsers() {
        return super.configUsers()
                + AUTHENTICATE_USER + ":" + SecuritySettingsSource.TEST_PASSWORD_HASHED + "\n"
                + EXECUTE_USER + ":xx_no_password_xx\n";
    }

    @Override
    public String configUsersRoles() {
        return super.configUsersRoles()
                + ROLE_CAN_RUN_AS + ":" + AUTHENTICATE_USER + "\n"
                + "kibana_user:" + EXECUTE_USER;
    }

    @Override
    public boolean transportSSLEnabled() {
        return true;
    }

    public void testAuditAccessDeniedWithRunAsUser() throws Exception {
        try {
            getRestClient().performRequest("GET", "/.security/_search",
                    new BasicHeader(UsernamePasswordToken.BASIC_AUTH_HEADER,
                            UsernamePasswordToken.basicAuthHeaderValue(AUTHENTICATE_USER, TEST_PASSWORD_SECURE_STRING)),
                    new BasicHeader(AuthenticationService.RUN_AS_USER_HEADER, EXECUTE_USER));
            fail("request should have failed");
        } catch (ResponseException e) {
            assertThat(e.getResponse().getStatusLine().getStatusCode(), is(403));
        }

        final Collection<Map<String, Object>> events = waitForAuditEvents();

        assertThat(events, iterableWithSize(1));
        final Map<String, Object> event = events.iterator().next();
        assertThat(event.get(IndexAuditTrail.Field.TYPE), equalTo("access_denied"));
        assertThat((List<?>) event.get(IndexAuditTrail.Field.INDICES), containsInAnyOrder(".security"));
        assertThat(event.get(IndexAuditTrail.Field.PRINCIPAL), equalTo(EXECUTE_USER));
        assertThat(event.get(IndexAuditTrail.Field.RUN_BY_PRINCIPAL), equalTo(AUTHENTICATE_USER));
    }


    public void testAuditRunAsDeniedEmptyUser() throws Exception {
        try {
            getRestClient().performRequest("GET", "/.security/_search",
                    new BasicHeader(UsernamePasswordToken.BASIC_AUTH_HEADER,
                            UsernamePasswordToken.basicAuthHeaderValue(AUTHENTICATE_USER, TEST_PASSWORD_SECURE_STRING)),
                    new BasicHeader(AuthenticationService.RUN_AS_USER_HEADER, ""));
            fail("request should have failed");
        } catch (ResponseException e) {
            assertThat(e.getResponse().getStatusLine().getStatusCode(), is(401));
        }

        final Collection<Map<String, Object>> events = waitForAuditEvents();

        assertThat(events, iterableWithSize(1));
        final Map<String, Object> event = events.iterator().next();
        assertThat(event.get(IndexAuditTrail.Field.TYPE), equalTo("run_as_denied"));
        assertThat(event.get(IndexAuditTrail.Field.PRINCIPAL), equalTo(""));
        assertThat(event.get(IndexAuditTrail.Field.RUN_BY_PRINCIPAL), equalTo(AUTHENTICATE_USER));
    }

    private Collection<Map<String, Object>> waitForAuditEvents() throws InterruptedException {
        waitForAuditTrailToBeWritten();
        AtomicReference<Collection<Map<String, Object>>> eventsRef = new AtomicReference<>();
        awaitBusy(() -> {
            try {
                final Collection<Map<String, Object>> events = getAuditEvents();
                eventsRef.set(events);
                return events.size() > 0;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        return eventsRef.get();
    }
    private Collection<Map<String, Object>> getAuditEvents() throws Exception {
        final Client client = client();
        DateTime now = new DateTime(DateTimeZone.UTC);
        String indexName = IndexNameResolver.resolve(IndexAuditTrail.INDEX_NAME_PREFIX, now, IndexNameResolver.Rollover.DAILY);

        assertTrue(awaitBusy(() -> indexExists(client, indexName), 5, TimeUnit.SECONDS));

        client.admin().indices().refresh(Requests.refreshRequest(indexName)).get();

        SearchRequest request = client.prepareSearch(indexName)
                .setTypes(IndexAuditTrail.DOC_TYPE)
                .setQuery(QueryBuilders.matchAllQuery())
                .setSize(1000)
                .setFetchSource(true)
                .request();
        request.indicesOptions().ignoreUnavailable();

        PlainActionFuture<Collection<Map<String, Object>>> listener = new PlainActionFuture();
        ScrollHelper.fetchAllByEntity(client, request, listener, SearchHit::getSourceAsMap);

        return listener.get();
    }

    private boolean indexExists(Client client, String indexName) {
        try {
            final ActionFuture<IndicesExistsResponse> future = client.admin().indices().exists(Requests.indicesExistsRequest(indexName));
            return future.get().isExists();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException("Failed to check if " + indexName + " exists", e);
        }
    }

    private void waitForAuditTrailToBeWritten() throws InterruptedException {
        final AuditTrailService auditTrailService = (AuditTrailService) internalCluster().getInstance(AuditTrail.class);
        assertThat(auditTrailService.getAuditTrails(), iterableWithSize(1));

        final IndexAuditTrail indexAuditTrail = (IndexAuditTrail) auditTrailService.getAuditTrails().get(0);
        assertTrue(awaitBusy(() -> indexAuditTrail.peek() == null, 5, TimeUnit.SECONDS));
    }
}
