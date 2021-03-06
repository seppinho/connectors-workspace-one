/*
 * Copyright © 2017 VMware, Inc. All Rights Reserved.
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.vmware.connectors.jira;

import com.google.common.collect.ImmutableList;
import com.vmware.connectors.mock.MockRestServiceServer;
import com.vmware.connectors.test.ControllerTestsBase;
import com.vmware.connectors.test.JsonReplacementsBuilder;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.test.web.client.ResponseActions;
import org.springframework.test.web.client.match.MockRestRequestMatchers;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;

import static com.vmware.connectors.test.JsonSchemaValidator.isValidHeroCardConnectorResponse;
import static org.hamcrest.CoreMatchers.any;
import static org.hamcrest.CoreMatchers.containsString;
import static org.springframework.http.HttpHeaders.*;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.HEAD;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpStatus.*;
import static org.springframework.http.MediaType.*;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Created by Rob Worsnop on 12/9/16.
 */
class JiraControllerTest extends ControllerTestsBase {

    @Value("classpath:jira/responses/APF-27.json")
    private Resource apf27;

    @Value("classpath:jira/responses/APF-28.json")
    private Resource apf28;

    @Value("classpath:jira/responses/myself.json")
    private Resource myself;

    private MockRestServiceServer mockJira;

    @BeforeEach
    void init() throws Exception {
        super.setup();
        mockJira = MockRestServiceServer.bindTo(requestHandlerHolder).ignoreExpectOrder(true).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/cards/requests",
            "/api/v1/issues/1234/comment",
            "/api/v1/issues/1234/watchers"})
    void testProtectedResource(String uri) throws Exception {
        testProtectedResource(POST, uri);
    }

    @Test
    void testDiscovery() throws Exception {
        testConnectorDiscovery();
    }

    @Test
    void testRegex() throws Exception {
        List<String> expected = ImmutableList.of(
                "ABC-1",
                "ABCDEFGHIJ-123",
//                "ABCDEFGHIJK-456", // Should not match because too many letters
                "ABC-3", // not ideal, but not worth fixing
                "ABC-4",
                "ABC-5",
//                "ABC-6", // Should not match due to leading "x"
                "ABC-7",
//                "ABC-8", // Should not match due to leading "1"
                "ABC-9",
                "ABC-10",
                "ABC-11",
                "ABC-12", // not ideal, but not worth fixing
                "ABC-13",
                "ABC-14",
                "ABC-15", // not ideal, but not worth fixing
                "ABC-16",
                "ABC-17",
                "ABC-18",
                "ABC-19", // not ideal, but not worth fixing
//                "ABC-20", // Should not match due to leading "x"
//                "D-2", // should not match
//                "MM-2", // should not match
//                "ZGW-2", // should not match
//                "XV-2", // should not match
//                "F-2", // should not match
//                "SA-2", // should not match
                "ABC-21"
        );

        testRegex("issue_id", fromFile("/regex/email.txt"), expected);
    }

    @Test
    void testRequestWithEmptyIssue() throws Exception {
        testRequestCards("emptyIssue.json", "emptyIssue.json", null);
    }

    @ParameterizedTest(name = "{index} ==> ''{0}''")
    @DisplayName("Missing parameter cases")
    @CsvSource({
            "emptyRequest.json, emptyRequest.json",
            "emptyToken.json, emptyToken.json"})
    void testRequestCardsWithMissingParameter(String requestFile, String responseFile) throws Exception {
        MockHttpServletRequestBuilder builder = requestCards("abc", requestFile);

        perform(builder)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                .andExpect(content().json(fromFile("connector/responses/" + responseFile)));
    }

    @DisplayName("Card request success cases")
    @ParameterizedTest(name = "{index} ==> Language=''{0}''")
    @CsvSource({
            StringUtils.EMPTY + ", success.json",
            "xx, success_xx.json"})
    void testRequestCardsSuccess(String lang, String resFile) throws Exception {
        expect("APF-27").andRespond(withSuccess(apf27, APPLICATION_JSON));
        expect("APF-28").andRespond(withSuccess(apf28, APPLICATION_JSON));
        testRequestCards("request.json", resFile, lang);
        mockJira.verify();
    }

    @Test
    void testAuthSuccess() throws Exception {
        mockJira.expect(requestTo("https://jira.acme.com/rest/api/2/myself"))
                .andExpect(method(HEAD))
                .andExpect(MockRestRequestMatchers.header(AUTHORIZATION, "Bearer abc"))
                .andRespond(withSuccess());

        perform(head("/test-auth").with(token(accessToken()))
                .header("x-jira-authorization", "Bearer abc")
                .header("x-jira-base-url", "https://jira.acme.com"))
                .andExpect(status().isNoContent());

        mockJira.verify();
    }

    @Test
    void testAuthFail() throws Exception {
        mockJira.expect(requestTo("https://jira.acme.com/rest/api/2/myself"))
                .andExpect(method(HEAD))
                .andExpect(MockRestRequestMatchers.header(AUTHORIZATION, "Bearer abc"))
                .andRespond(withUnauthorizedRequest());

        perform(head("/test-auth").with(token(accessToken()))
                .header("x-jira-authorization", "Bearer abc")
                .header("x-jira-base-url", "https://jira.acme.com"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("x-backend-status", "401"));

        mockJira.verify();
    }

    /*
    Give more priority to x-auth header if more than one request-headers are missing.
     */
    @Test
    void testMissingRequestHeaders() throws Exception {
        perform(post("/cards/requests").with(token(accessToken()))
                .contentType(APPLICATION_JSON)
                .accept(APPLICATION_JSON)
                .header("x-routing-prefix", "https://hero/connectors/jira/")
                .content(fromFile("/jira/requests/request.json")))
                .andExpect(status().isBadRequest())
                .andExpect(status().reason(containsString("Missing request header 'x-jira-authorization'")));
    }

    @Test
    void testRequestCardsNotAuthorized() throws Exception {
        mockJira.expect(times(1), requestTo(any(String.class)))
                .andExpect(MockRestRequestMatchers.header(AUTHORIZATION, "Bearer bogus"))
                .andExpect(method(GET))
                .andRespond(withUnauthorizedRequest());
        perform(requestCards("bogus", "request.json"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("X-Backend-Status", "401"))
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                .andExpect(content().json(fromFile("/connector/responses/invalid_connector_token.json")));
        mockJira.verify();
    }

    @Test
    void testRequestCardsOneNotFound() throws Exception {
        expect("APF-27").andRespond(withSuccess(apf27, APPLICATION_JSON));
        expect("BOGUS-999").andRespond(withStatus(NOT_FOUND));
        perform(requestCards("abc", "oneCardNotFound.json"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                .andExpect(content().string(isValidHeroCardConnectorResponse()))
                .andExpect(content().string(JsonReplacementsBuilder.from(
                        fromFile("connector/responses/APF-27.json")).buildForCards()));
        mockJira.verify();
    }

    @Test
    void testRequestCardsOneServerError() throws Exception {
        expect("POISON-PILL").andRespond(withServerError());
        perform(requestCards("abc", "oneServerError.json"))
                .andExpect(status().is5xxServerError())
                .andExpect(header().string("X-Backend-Status", "500"));
        mockJira.verify();
    }

    @Test
    void testAddComment() throws Exception {
        mockJira.expect(requestTo("https://jira.acme.com/rest/api/2/issue/1234/comment"))
                .andExpect(MockRestRequestMatchers.header(AUTHORIZATION, "Bearer abc"))
                .andExpect(method(POST))
                .andExpect(MockRestRequestMatchers.content().string("{\"body\":\"Hello\"}"))
                .andRespond(withStatus(CREATED));

        perform(post("/api/v1/issues/1234/comment").with(token(accessToken()))
                .contentType(APPLICATION_FORM_URLENCODED)
                .header("x-jira-authorization", "Bearer abc")
                .header("x-jira-base-url", "https://jira.acme.com")
                .content("body=Hello"))
                .andExpect(status().isCreated());
        mockJira.verify();
    }

    @Test
    void testAddCommentWith401() throws Exception {
        perform(post("/api/v1/issues/1234/comment")
                .contentType(APPLICATION_FORM_URLENCODED)
                .header("x-jira-authorization", "Bearer abc")
                .header("x-jira-base-url", "https://jira.acme.com")
                .content("body=Hello"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"error\":\"unauthorized\"}"));
        mockJira.verify();
    }

    @Test
    void testAddCommentWithMissingConnectorAuthorization() throws Exception {
        perform(post("/api/v1/issues/1234/comment").with(token(accessToken()))
                .contentType(APPLICATION_FORM_URLENCODED)
                .header("x-jira-base-url", "https://jira.acme.com")
                .content("body=Hello"))
                .andExpect(status().isBadRequest());
        mockJira.verify();
    }

    @Test
    void testAddCommentWithBackend401() throws Exception {
        mockJira.expect(requestTo("https://jira.acme.com/rest/api/2/issue/1234/comment"))
                .andExpect(MockRestRequestMatchers.header(AUTHORIZATION, "Bearer bogus"))
                .andExpect(method(POST))
                .andExpect(MockRestRequestMatchers.content().string("{\"body\":\"Hello\"}"))
                .andRespond(withStatus(UNAUTHORIZED));

        perform(post("/api/v1/issues/1234/comment").with(token(accessToken()))
                .contentType(APPLICATION_FORM_URLENCODED)
                .header("x-jira-authorization", "Bearer bogus")
                .header("x-jira-base-url", "https://jira.acme.com")
                .content("body=Hello"))
                .andExpect(status().isBadRequest())
                .andExpect((content().json(fromFile("/connector/responses/invalid_connector_token.json"))));
        mockJira.verify();
    }

    @Test
    void testAddWatcher() throws Exception {
        mockJira.expect(requestTo("https://jira.acme.com/rest/api/2/myself"))
                .andExpect(MockRestRequestMatchers.header(AUTHORIZATION, "Bearer abc"))
                .andExpect(method(GET))
                .andRespond(withSuccess(myself, APPLICATION_JSON));
        mockJira.expect(requestTo("https://jira.acme.com/rest/api/2/issue/1234/watchers"))
                .andExpect(MockRestRequestMatchers.header(AUTHORIZATION, "Bearer abc"))
                .andExpect(method(POST))
                .andExpect(MockRestRequestMatchers.content().string("\"harshas\""))
                .andRespond(withStatus(NO_CONTENT));

        perform(post("/api/v1/issues/1234/watchers").with(token(accessToken()))
                .header("x-jira-authorization", "Bearer abc")
                .header("x-jira-base-url", "https://jira.acme.com"))
                .andExpect(status().isNoContent());
        mockJira.verify();
    }

    @Test
    void testAddWatcherWith401() throws Exception {
        perform(post("/api/v1/issues/1234/watchers")
                .header("x-jira-authorization", "Bearer abc")
                .header("x-jira-base-url", "https://jira.acme.com"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"error\":\"unauthorized\"}"));
        mockJira.verify();
    }

    @Test
    void testAddWatcherWithMissingConnectorAuthorization() throws Exception {
        perform(post("/api/v1/issues/1234/watchers").with(token(accessToken()))
                .header("x-jira-base-url", "https://jira.acme.com"))
                .andExpect(status().isBadRequest());
        mockJira.verify();
    }

    @Test
    void testAddWatcherWithBackend401() throws Exception {
        mockJira.expect(requestTo("https://jira.acme.com/rest/api/2/myself"))
                .andExpect(MockRestRequestMatchers.header(AUTHORIZATION, "Bearer bogus"))
                .andExpect(method(GET))
                .andRespond(withStatus(UNAUTHORIZED));

        perform(post("/api/v1/issues/1234/watchers").with(token(accessToken()))
                .header("x-jira-authorization", "Bearer bogus")
                .header("x-jira-base-url", "https://jira.acme.com"))
                .andExpect(status().isBadRequest())
                .andExpect((content().json(fromFile("/connector/responses/invalid_connector_token.json"))));
        mockJira.verify();
    }

    @Test
    void testGetImage() throws Exception {
        perform(get("/images/connector.png"))
                .andExpect(status().isOk())
                .andExpect(header().longValue(CONTENT_LENGTH, 11851))
                .andExpect(header().string(CONTENT_TYPE, IMAGE_PNG_VALUE))
                .andExpect((content().bytes(bytesFromFile("/static/images/connector.png"))));
    }

    private ResponseActions expect(String issue) {
        return mockJira.expect(requestTo("https://jira.acme.com/rest/api/2/issue/" + issue))
                .andExpect(method(GET))
                .andExpect(MockRestRequestMatchers.header(AUTHORIZATION, "Bearer abc"));
    }

    private void testRequestCards(String requestFile, String responseFile, String acceptLanguage) throws Exception {
        MockHttpServletRequestBuilder builder = requestCards("abc", requestFile);
        if (acceptLanguage != null) {
            builder = builder.header(ACCEPT_LANGUAGE, acceptLanguage);
        }
        perform(builder)
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                .andExpect(content().string(isValidHeroCardConnectorResponse()))
                .andExpect(content().string(JsonReplacementsBuilder.from(
                        fromFile("connector/responses/" + responseFile)).buildForCards()));
    }

    private MockHttpServletRequestBuilder requestCards(String authToken, String requestfile) throws Exception {
        return post("/cards/requests").with(token(accessToken()))
                .contentType(APPLICATION_JSON)
                .accept(APPLICATION_JSON)
                .header("x-jira-authorization", "Bearer " + authToken)
                .header("x-jira-base-url", "https://jira.acme.com")
                .header("x-routing-prefix", "https://hero/connectors/jira/")
                .content(fromFile("/jira/requests/" + requestfile));
    }
}
