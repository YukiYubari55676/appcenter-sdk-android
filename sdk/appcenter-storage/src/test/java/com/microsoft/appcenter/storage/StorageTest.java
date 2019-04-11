/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License.
 */

package com.microsoft.appcenter.storage;

import com.google.gson.Gson;
import com.microsoft.appcenter.channel.Channel;
import com.microsoft.appcenter.http.HttpClient;
import com.microsoft.appcenter.http.HttpException;
import com.microsoft.appcenter.http.ServiceCall;
import com.microsoft.appcenter.http.ServiceCallback;
import com.microsoft.appcenter.ingestion.Ingestion;
import com.microsoft.appcenter.ingestion.models.json.LogFactory;
import com.microsoft.appcenter.storage.client.CosmosDb;
import com.microsoft.appcenter.storage.client.TokenExchange;
import com.microsoft.appcenter.storage.exception.StorageException;
import com.microsoft.appcenter.storage.models.Document;
import com.microsoft.appcenter.storage.models.Page;
import com.microsoft.appcenter.storage.models.PaginatedDocuments;
import com.microsoft.appcenter.storage.models.ReadOptions;
import com.microsoft.appcenter.storage.models.TokenResult;
import com.microsoft.appcenter.storage.models.WriteOptions;
import com.microsoft.appcenter.utils.async.AppCenterFuture;
import com.microsoft.appcenter.utils.storage.SharedPreferencesManager;

import org.hamcrest.CoreMatchers;
import org.json.JSONException;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.powermock.core.classloader.annotations.PrepareForTest;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import javax.net.ssl.SSLException;

import static com.microsoft.appcenter.http.DefaultHttpClient.METHOD_DELETE;
import static com.microsoft.appcenter.http.DefaultHttpClient.METHOD_GET;
import static com.microsoft.appcenter.http.DefaultHttpClient.METHOD_POST;
import static com.microsoft.appcenter.storage.Constants.PREFERENCE_PARTITION_PREFIX;
import static com.microsoft.appcenter.storage.Constants.USER;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.anyMapOf;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.contains;
import static org.mockito.Matchers.endsWith;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.isNull;
import static org.mockito.Matchers.refEq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.powermock.api.mockito.PowerMockito.mock;
import static org.powermock.api.mockito.PowerMockito.mockStatic;
import static org.powermock.api.mockito.PowerMockito.verifyNoMoreInteractions;
import static org.powermock.api.mockito.PowerMockito.when;

@PrepareForTest({
        CosmosDb.class,
        TokenExchange.class,
        TokenManager.class
})
public class StorageTest extends AbstractStorageTest {

    private static final String TOKEN_RESULT = String.format("{\n" +
            "            \"partition\": \"%s\",\n" +
            "            \"dbAccount\": \"lemmings-01-8f37d78902\",\n" +
            "            \"dbName\": \"db\",\n" +
            "            \"dbCollectionName\": \"collection\",\n" +
            "            \"token\": \"%s\",\n" +
            "            \"status\": \"Succeed\",\n" +
            "            \"accountId\": \"%s\"\n" +
            "}", RESOLVED_USER_PARTITION, "token", ACCOUNT_ID);

    @Before
    public void setUpAuth() {
        setUpAuthContext();
    }

    @Test
    public void singleton() {
        Assert.assertSame(Storage.getInstance(), Storage.getInstance());
    }

    @Test
    public void isAppSecretRequired() {
        assertTrue(Storage.getInstance().isAppSecretRequired());
    }

    @Test
    public void checkFactories() {
        Map<String, LogFactory> factories = Storage.getInstance().getLogFactories();
        assertNull(factories);
    }

    @Test
    public void setEnabled() {
        Storage.setEnabled(true);

        verify(mChannel).removeGroup(eq(mStorage.getGroupName()));
        verify(mChannel).addGroup(eq(mStorage.getGroupName()), anyInt(), anyLong(), anyInt(), isNull(Ingestion.class), any(Channel.GroupListener.class));

        /* Now we can see the service enabled. */
        assertTrue(Storage.isEnabled().get());

        /* Disable. Testing to wait setEnabled to finish while we are at it. */
        Storage.setEnabled(false).get();
        assertFalse(Storage.isEnabled().get());
    }

    @Test
    public void disablePersisted() {
        when(SharedPreferencesManager.getBoolean(STORAGE_ENABLED_KEY, true)).thenReturn(false);
        verify(mChannel, never()).removeListener(any(Channel.Listener.class));
        verify(mChannel, never()).addListener(any(Channel.Listener.class));
    }

    @Test
    public void listEndToEndWhenSinglePage() {

        /* Setup mock to get expiration token from cache. */
        Calendar expirationDate = Calendar.getInstance(TimeZone.getTimeZone("GMT"));
        expirationDate.add(Calendar.SECOND, 1000);
        String tokenResult = new Gson().toJson(new TokenResult().withPartition(RESOLVED_USER_PARTITION).withExpirationTime(expirationDate.getTime()).withToken("fakeToken"));
        when(SharedPreferencesManager.getString(PREFERENCE_PARTITION_PREFIX + USER)).thenReturn(tokenResult);

        /* Setup list documents api response. */
        List<Document<TestDocument>> documents = Collections.singletonList(new Document<>(
                new TestDocument("Test"),
                RESOLVED_USER_PARTITION,
                "document id",
                "e tag",
                0
        ));
        final String expectedResponse = new Gson().toJson(
                new Page<TestDocument>().withDocuments(documents)
        );
        when(mHttpClient.callAsync(endsWith("docs"), anyString(), anyMapOf(String.class, String.class), any(HttpClient.CallTemplate.class), any(ServiceCallback.class))).then(new Answer<ServiceCall>() {

            @Override
            public ServiceCall answer(InvocationOnMock invocation) {
                ((ServiceCallback) invocation.getArguments()[4]).onCallSucceeded(expectedResponse, new HashMap<String, String>());
                return mock(ServiceCall.class);
            }
        });

        /* Make the call. */
        PaginatedDocuments<TestDocument> docs = Storage.list(USER, TestDocument.class).get();

        /* Verify the result correct. */
        assertFalse(docs.hasNextPage());
        assertEquals(1, docs.getCurrentPage().getItems().size());
        assertEquals(docs.getCurrentPage().getItems().get(0).getDocument().test, documents.get(0).getDocument().test);
    }

    @Test
    public void listEndToEndWhenMultiplePages() {

        /* Setup mock to get expiration token from cache. */
        Calendar expirationDate = Calendar.getInstance(TimeZone.getTimeZone("GMT"));
        expirationDate.add(Calendar.SECOND, 1000);
        String tokenResult = new Gson().toJson(new TokenResult().withPartition(RESOLVED_USER_PARTITION).withExpirationTime(expirationDate.getTime()).withToken("fakeToken"));
        when(SharedPreferencesManager.getString(PREFERENCE_PARTITION_PREFIX + USER)).thenReturn(tokenResult);

        /* Setup list documents api response. */
        List<Document<TestDocument>> firstPartDocuments = Collections.singletonList(new Document<>(
                new TestDocument("Test"),
                RESOLVED_USER_PARTITION,
                "document id",
                "e tag",
                0
        ));
        final String expectedFirstResponse = new Gson().toJson(
                new Page<TestDocument>().withDocuments(firstPartDocuments)
        );
        final List<Document<TestDocument>> secondPartDocuments = Collections.singletonList(new Document<>(
                new TestDocument("Test2"),
                RESOLVED_USER_PARTITION,
                "document id 2",
                "e tag 2",
                1
        ));
        final String expectedSecondResponse = new Gson().toJson(
                new Page<TestDocument>().withDocuments(secondPartDocuments)
        );

        @SuppressWarnings("unchecked") final ArgumentCaptor<Map<String, String>> headers = ArgumentCaptor.forClass((Class) Map.class);
        when(mHttpClient.callAsync(endsWith("docs"), anyString(), headers.capture(), any(HttpClient.CallTemplate.class), any(ServiceCallback.class))).then(new Answer<ServiceCall>() {

            @Override
            public ServiceCall answer(InvocationOnMock invocation) {
                String expectedResponse = headers.getValue().containsKey(Constants.CONTINUATION_TOKEN_HEADER) ? expectedSecondResponse : expectedFirstResponse;
                Map<String, String> newHeader = headers.getValue().containsKey(Constants.CONTINUATION_TOKEN_HEADER) ? new HashMap<String, String>() : new HashMap<String, String>() {
                    {
                        put(Constants.CONTINUATION_TOKEN_HEADER, "continuation token");
                    }
                };
                ((ServiceCallback) invocation.getArguments()[4]).onCallSucceeded(expectedResponse, newHeader);
                return mock(ServiceCall.class);
            }
        });

        /* Make the call. */
        PaginatedDocuments<TestDocument> docs = Storage.list(USER, TestDocument.class).get();
        assertTrue(docs.hasNextPage());
        assertEquals(firstPartDocuments.get(0).getId(), docs.getCurrentPage().getItems().get(0).getId());
        Page<TestDocument> secondPage = docs.getNextPage().get();
        assertFalse(docs.hasNextPage());
        assertEquals(secondPage.getItems().get(0).getId(), docs.getCurrentPage().getItems().get(0).getId());
    }

    @Test
    public void listEndToEndWhenUseIterators() {

        /* Setup mock to get expiration token from cache. */
        Calendar expirationDate = Calendar.getInstance(TimeZone.getTimeZone("GMT"));
        expirationDate.add(Calendar.SECOND, 1000);
        String tokenResult = new Gson().toJson(new TokenResult().withPartition(RESOLVED_USER_PARTITION).withExpirationTime(expirationDate.getTime()).withToken("fakeToken"));
        when(SharedPreferencesManager.getString(PREFERENCE_PARTITION_PREFIX + USER)).thenReturn(tokenResult);

        /* Setup list documents api response. */
        List<Document<TestDocument>> firstPartDocuments = Collections.nCopies(2, new Document<>(
                new TestDocument("Test"),
                RESOLVED_USER_PARTITION,
                "document id",
                "e tag",
                0
        ));
        final String expectedFirstResponse = new Gson().toJson(
                new Page<TestDocument>().withDocuments(firstPartDocuments)
        );
        final List<Document<TestDocument>> secondPartDocuments = Collections.singletonList(new Document<>(
                new TestDocument("Test2"),
                RESOLVED_USER_PARTITION,
                "document id 2",
                "e tag 2",
                1
        ));
        final String expectedSecondResponse = new Gson().toJson(
                new Page<TestDocument>().withDocuments(secondPartDocuments)
        );

        @SuppressWarnings("unchecked") final ArgumentCaptor<Map<String, String>> headers = ArgumentCaptor.forClass((Class) Map.class);
        when(mHttpClient.callAsync(endsWith("docs"), anyString(), headers.capture(), any(HttpClient.CallTemplate.class), any(ServiceCallback.class))).then(new Answer<ServiceCall>() {

            @Override
            public ServiceCall answer(InvocationOnMock invocation) {
                String expectedResponse = headers.getValue().containsKey(Constants.CONTINUATION_TOKEN_HEADER) ? expectedSecondResponse : expectedFirstResponse;
                Map<String, String> newHeader = headers.getValue().containsKey(Constants.CONTINUATION_TOKEN_HEADER) ? new HashMap<String, String>() : new HashMap<String, String>() {
                    {
                        put(Constants.CONTINUATION_TOKEN_HEADER, "continuation token");
                    }
                };
                ((ServiceCallback) invocation.getArguments()[4]).onCallSucceeded(expectedResponse, newHeader);
                return mock(ServiceCall.class);
            }
        });

        /* Make the call. */
        Iterator<Document<TestDocument>> iterator = Storage.list(USER, TestDocument.class).get().iterator();
        List<Document<TestDocument>> documents = new ArrayList<>();
        while (iterator.hasNext()) {
            documents.add(iterator.next());
        }
        assertEquals(3, documents.size());
        assertEquals(firstPartDocuments.get(0).getId(), documents.get(0).getId());
        assertEquals(secondPartDocuments.get(0).getId(), documents.get(2).getId());
        assertNotNull(iterator.next().getDocumentError());

        /* Verify not throws exception. */
        iterator.remove();
    }

    @Test
    public void listEndToEndWhenExceptionHappened() {
        Calendar expirationDate = Calendar.getInstance(TimeZone.getTimeZone("GMT"));
        expirationDate.add(Calendar.SECOND, 1000);
        String tokenResult = new Gson().toJson(new TokenResult().withPartition(RESOLVED_USER_PARTITION).withExpirationTime(expirationDate.getTime()).withToken("fakeToken"));
        when(SharedPreferencesManager.getString(PREFERENCE_PARTITION_PREFIX + USER)).thenReturn(tokenResult);
        when(mHttpClient.callAsync(endsWith("docs"), anyString(), anyMapOf(String.class, String.class), any(HttpClient.CallTemplate.class), any(ServiceCallback.class))).then(new Answer<ServiceCall>() {

            @Override
            public ServiceCall answer(InvocationOnMock invocation) {
                ((ServiceCallback) invocation.getArguments()[4]).onCallFailed(new Exception("some error"));
                return mock(ServiceCall.class);
            }
        });

        /* Make the call. */
        PaginatedDocuments<TestDocument> docs = Storage.list(USER, TestDocument.class).get();

        /* Verify the result correct. */
        assertFalse(docs.hasNextPage());
        assertNotNull(docs.getCurrentPage());
        assertNotNull(docs.getCurrentPage().getError());

        /* Make the call, when continuation token is null. */
        Page nextPage = docs.getNextPage().get();
        assertNotNull(nextPage);
        assertNotNull(nextPage.getError());

        /* Set the continuation token, but the http call failed. */
        docs.withContinuationToken("fake continuation token").withTokenResult(new Gson().fromJson(tokenResult, TokenResult.class)).withHttpClient(mHttpClient);
        nextPage = docs.getNextPage().get();
        assertNotNull(nextPage);
        assertNotNull(nextPage.getError());
    }

    @Test
    public void listEndToEndWhenMakeTokenExchangeCallFails() throws JSONException {
        AppCenterFuture<PaginatedDocuments<TestDocument>> documents = Storage.list(USER, TestDocument.class);

        String exceptionMessage = "Call to token exchange failed for whatever reason";
        verifyTokenExchangeFlow(null, new HttpException(503, exceptionMessage));

        /*
         *  No retries and Cosmos DB does not get called.
         */
        verifyNoMoreInteractions(mHttpClient);
        assertNotNull(documents);
        assertNotNull(documents.get());
        assertNotNull(documents.get().getCurrentPage().getError());
        assertThat(
                documents.get().getCurrentPage().getError().getError().getMessage(),
                CoreMatchers.containsString(exceptionMessage));
    }

    @Test
    public void replaceEndToEnd() throws JSONException {

        /* Mock http call to get token. */
        AppCenterFuture<Document<TestDocument>> doc = Storage.replace(USER, DOCUMENT_ID, new TestDocument(TEST_FIELD_VALUE), TestDocument.class);

        /* Make the call. */
        verifyTokenExchangeToCosmosDbFlow(null, TOKEN_EXCHANGE_USER_PAYLOAD, METHOD_POST, COSMOS_DB_DOCUMENT_RESPONSE_PAYLOAD, null);

        /* Get and verify token. */
        assertNotNull(doc);

        Document<TestDocument> testCosmosDocument = doc.get();
        assertNotNull(testCosmosDocument);
        assertEquals(RESOLVED_USER_PARTITION, testCosmosDocument.getPartition());
        assertEquals(DOCUMENT_ID, testCosmosDocument.getId());
        assertNull(testCosmosDocument.getDocumentError());
        assertNotNull(testCosmosDocument.getEtag());
        assertNotEquals(0L, testCosmosDocument.getTimestamp());

        TestDocument testDocument = testCosmosDocument.getDocument();
        assertNotNull(testDocument);
        assertEquals(TEST_FIELD_VALUE, testDocument.test);
    }

    @Test
    public void readUserEndToEndWithNetwork() throws JSONException {
        readEndToEndWithNetwork(USER, TOKEN_EXCHANGE_USER_PAYLOAD);
    }

    @Test
    public void readReadonlyEndToEndWithNetwork() throws JSONException {
        readEndToEndWithNetwork(Constants.READONLY, TOKEN_EXCHANGE_READONLY_PAYLOAD);
    }

    private void readEndToEndWithNetwork(String partition, String tokenExchangePayload) throws JSONException {

        /* Mock http call to get token. */
        AppCenterFuture<Document<TestDocument>> doc = Storage.read(partition, DOCUMENT_ID, TestDocument.class);

        /* Make cosmos db http call with exchanged token. */
        verifyTokenExchangeToCosmosDbFlow(DOCUMENT_ID, tokenExchangePayload, METHOD_GET, COSMOS_DB_DOCUMENT_RESPONSE_PAYLOAD, null);
        verifyCosmosDbFlow(DOCUMENT_ID, METHOD_GET, COSMOS_DB_DOCUMENT_RESPONSE_PAYLOAD, null);

        /* Get and verify token. */
        assertNotNull(doc);

        Document<TestDocument> testCosmosDocument = doc.get();
        assertNotNull(testCosmosDocument);
        assertEquals(RESOLVED_USER_PARTITION, testCosmosDocument.getPartition());
        assertEquals(DOCUMENT_ID, testCosmosDocument.getId());
        assertNull(testCosmosDocument.getDocumentError());
        assertNotNull(testCosmosDocument.getEtag());
        assertNotEquals(0L, testCosmosDocument.getTimestamp());

        TestDocument testDocument = testCosmosDocument.getDocument();
        assertNotNull(testDocument);
        assertEquals(TEST_FIELD_VALUE, testDocument.test);
    }

    @Test
    public void readFailedCosmosDbCallFailed() throws JSONException {
        AppCenterFuture<Document<TestDocument>> doc = Storage.read(USER, DOCUMENT_ID, TestDocument.class);
        verifyTokenExchangeToCosmosDbFlow(DOCUMENT_ID, TOKEN_EXCHANGE_USER_PAYLOAD, METHOD_GET, null, new Exception("Cosmos db exception."));

        /*
         *  No retries and Cosmos DB does not get called.
         */
        verifyNoMoreInteractions(mHttpClient);
        assertNotNull(doc);
        assertNotNull(doc.get());
        assertNull(doc.get().getDocument());
        assertNotNull(doc.get().getDocumentError());
        assertThat(
                doc.get().getDocumentError().getError().getMessage(),
                CoreMatchers.containsString("Cosmos db exception."));
    }

    @Test
    public void readFailTokenExchangeReturnsFailedTokenResultPayload() {
        AppCenterFuture<Document<TestDocument>> doc = Storage.read(USER, DOCUMENT_ID, TestDocument.class);

        ArgumentCaptor<TokenExchange.TokenExchangeServiceCallback> tokenExchangeServiceCallbackArgumentCaptor =
                ArgumentCaptor.forClass(TokenExchange.TokenExchangeServiceCallback.class);
        verifyNoMoreInteractions(mLocalDocumentStorage);
        verify(mHttpClient).callAsync(
                endsWith(TokenExchange.GET_TOKEN_PATH_FORMAT),
                eq(METHOD_POST),
                anyMapOf(String.class, String.class),
                any(HttpClient.CallTemplate.class),
                tokenExchangeServiceCallbackArgumentCaptor.capture());
        TokenExchange.TokenExchangeServiceCallback tokenExchangeServiceCallback = tokenExchangeServiceCallbackArgumentCaptor.getValue();
        assertNotNull(tokenExchangeServiceCallback);
        String tokenExchangeFailedResponsePayload = "{\n" +
                "    \"tokens\": [\n" +
                "        {\n" +
                "            \"partition\": \"\",\n" +
                "            \"dbAccount\": \"\",\n" +
                "            \"dbName\": \"\",\n" +
                "            \"dbCollectionName\": \"\",\n" +
                "            \"token\": \"\",\n" +
                "            \"status\": \"Failed\"\n" +
                "        }\n" +
                "    ]\n" +
                "}";
        tokenExchangeServiceCallback.onCallSucceeded(tokenExchangeFailedResponsePayload, new HashMap<String, String>());

        /*
         *  No retries and Cosmos DB does not get called.
         */
        verifyNoMoreInteractions(mHttpClient);
        assertNotNull(doc);
        assertNotNull(doc.get());
        assertNull(doc.get().getDocument());
        assertNotNull(doc.get().getDocumentError());
        assertThat(
                doc.get().getDocumentError().getError().getMessage(),
                CoreMatchers.containsString(tokenExchangeFailedResponsePayload));
    }

    @Test
    public void readTokenExchangeCallFails() throws JSONException {
        AppCenterFuture<Document<TestDocument>> doc = Storage.read(USER, DOCUMENT_ID, TestDocument.class);

        String exceptionMessage = "Call to token exchange failed for whatever reason";
        verifyTokenExchangeToCosmosDbFlow(DOCUMENT_ID, TOKEN_EXCHANGE_USER_PAYLOAD, METHOD_GET, null, new HttpException(503, exceptionMessage));

        /*
         *  No retries and Cosmos DB does not get called.
         */
        verifyNoMoreInteractions(mHttpClient);
        assertNotNull(doc);
        assertNotNull(doc.get());
        assertNull(doc.get().getDocument());
        assertNotNull(doc.get().getDocumentError());
        assertThat(
                doc.get().getDocumentError().getError().getMessage(),
                CoreMatchers.containsString(exceptionMessage));
    }

    @Test
    public void readWithoutNetwork() {
        when(mNetworkStateHelper.isNetworkConnected()).thenReturn(false);
        when(SharedPreferencesManager.getString(PREFERENCE_PARTITION_PREFIX + USER)).thenReturn(TOKEN_RESULT);
        when(mLocalDocumentStorage.read(eq(USER_TABLE_NAME), anyString(), anyString(), any(Class.class), any(ReadOptions.class))).thenReturn(new Document(new Exception("document not set")));
        Storage.read(USER, DOCUMENT_ID, TestDocument.class);
        verifyNoMoreInteractions(mHttpClient);
        verify(mLocalDocumentStorage).read(
                eq(USER_TABLE_NAME),
                eq(RESOLVED_USER_PARTITION),
                eq(DOCUMENT_ID),
                eq(TestDocument.class),
                any(ReadOptions.class));
    }

    @Test
    public void readWhenLocalStorageContainsDeletePendingOperation() {
        when(mNetworkStateHelper.isNetworkConnected()).thenReturn(false);
        when(SharedPreferencesManager.getString(PREFERENCE_PARTITION_PREFIX + USER)).thenReturn(TOKEN_RESULT);
        Document<String> deletedDocument = new Document<>();
        deletedDocument.setPendingOperation(Constants.PENDING_OPERATION_DELETE_VALUE);
        when(mLocalDocumentStorage.read(eq(USER_TABLE_NAME), anyString(), anyString(), any(Class.class), any(ReadOptions.class))).thenReturn(deletedDocument);
        Document<String> document = Storage.read(USER, DOCUMENT_ID, String.class).get();
        assertNotNull(document.getDocumentError());
        assertTrue(document.getDocumentError().getError() instanceof StorageException);
    }

    @Test
    public void readOnLineWhenLocalStorageContainsNullOperation() {
        Calendar expirationDate = Calendar.getInstance(TimeZone.getTimeZone("GMT"));
        expirationDate.add(Calendar.SECOND, 1000);
        TokenResult tokenResult = new TokenResult().withPartition(RESOLVED_USER_PARTITION).withExpirationTime(expirationDate.getTime()).withToken("tokenResult");
        when(SharedPreferencesManager.getString(PREFERENCE_PARTITION_PREFIX + USER)).thenReturn(new Gson().toJson(tokenResult));
        Document<String> outDatedDocument = new Document<>();
        Document<String> expectedDocument = new Document<>("123", RESOLVED_USER_PARTITION, DOCUMENT_ID);
        final String expectedResponse = new Gson().toJson(expectedDocument);
        when(mLocalDocumentStorage.read(eq(Utils.getTableName(tokenResult)), anyString(), anyString(), any(Class.class), any(ReadOptions.class))).thenReturn(outDatedDocument);
        when(mHttpClient.callAsync(contains(DOCUMENT_ID), anyString(), anyMapOf(String.class, String.class), any(HttpClient.CallTemplate.class), any(ServiceCallback.class))).then(new Answer<ServiceCall>() {

            @Override
            public ServiceCall answer(InvocationOnMock invocation) {
                ((ServiceCallback) invocation.getArguments()[4]).onCallSucceeded(expectedResponse, new HashMap<String, String>());
                return mock(ServiceCall.class);
            }
        });
        Document<String> document = Storage.read(USER, DOCUMENT_ID, String.class).get();
        verify(mHttpClient);
        assertNotNull(document.getDocument());
        assertEquals(expectedDocument.getDocument(), document.getDocument());
    }

    @Test
    public void readWhenLocalStorageContainsCreateOperation() {
        when(SharedPreferencesManager.getString(PREFERENCE_PARTITION_PREFIX + USER)).thenReturn(TOKEN_RESULT);
        Document<String> createdDocument = new Document<>("123", RESOLVED_USER_PARTITION, DOCUMENT_ID);
        createdDocument.setPendingOperation(Constants.PENDING_OPERATION_CREATE_VALUE);
        when(mLocalDocumentStorage.read(eq(USER_TABLE_NAME), anyString(), anyString(), any(Class.class), any(ReadOptions.class))).thenReturn(createdDocument);
        Document<String> document = Storage.read(USER, DOCUMENT_ID, String.class).get();
        verifyNoMoreInteractions(mHttpClient);
        assertEquals(createdDocument.getDocument(), document.getDocument());
    }

    @Test
    public void createEndToEndWithNetwork() throws JSONException {
        when(mNetworkStateHelper.isNetworkConnected()).thenReturn(true);
        WriteOptions writeOptions = new WriteOptions(12476);
        AppCenterFuture<Document<TestDocument>> doc = Storage.create(USER, DOCUMENT_ID, new TestDocument(TEST_FIELD_VALUE), TestDocument.class, writeOptions);
        verifyTokenExchangeToCosmosDbFlow(null, TOKEN_EXCHANGE_USER_PAYLOAD, METHOD_POST, COSMOS_DB_DOCUMENT_RESPONSE_PAYLOAD, null);
        assertNotNull(doc);
        Document<TestDocument> testCosmosDocument = doc.get();
        assertNotNull(testCosmosDocument);
        verify(mLocalDocumentStorage, times(1)).writeOnline(eq(USER_TABLE_NAME), refEq(testCosmosDocument), refEq(writeOptions));
        verifyNoMoreInteractions(mLocalDocumentStorage);
        assertEquals(RESOLVED_USER_PARTITION, testCosmosDocument.getPartition());
        assertEquals(DOCUMENT_ID, testCosmosDocument.getId());
        assertNull(testCosmosDocument.getDocumentError());
        assertNotNull(testCosmosDocument.getEtag());
        assertNotEquals(0L, testCosmosDocument.getTimestamp());

        TestDocument testDocument = testCosmosDocument.getDocument();
        assertNotNull(testDocument);
        assertEquals(TEST_FIELD_VALUE, testDocument.test);
    }

    @Test
    public void createWithNoNetwork() {
        when(mNetworkStateHelper.isNetworkConnected()).thenReturn(false);
        TestDocument testDocument = new TestDocument("test");
        when(SharedPreferencesManager.getString(PREFERENCE_PARTITION_PREFIX + USER)).thenReturn(TOKEN_RESULT);
        Storage.create(USER, DOCUMENT_ID, testDocument, TestDocument.class);
        verifyNoMoreInteractions(mHttpClient);
        verify(mLocalDocumentStorage).createOrUpdateOffline(
                eq(USER_TABLE_NAME),
                eq(RESOLVED_USER_PARTITION),
                eq(DOCUMENT_ID),
                eq(testDocument),
                eq(TestDocument.class),
                any(WriteOptions.class)
        );
    }

    @Test
    public void createTokenExchangeCallFails() throws JSONException {
        AppCenterFuture<Document<TestDocument>> doc = Storage.create(USER, DOCUMENT_ID, new TestDocument("test"), TestDocument.class);
        String exceptionMessage = "Call to token exchange failed for whatever reason";
        verifyTokenExchangeFlow(null, new HttpException(503, exceptionMessage));

        /*
         *  No retries and Cosmos DB does not get called.
         */
        verifyNoMoreInteractions(mHttpClient);
        assertNotNull(doc);
        assertNotNull(doc.get());
        assertNull(doc.get().getDocument());
        assertNotNull(doc.get().getDocumentError());
        assertThat(
                doc.get().getDocumentError().getError().getMessage(),
                CoreMatchers.containsString(exceptionMessage));
    }

    @Test
    public void createCosmosDbCallFails() throws JSONException {
        AppCenterFuture<Document<TestDocument>> doc = Storage.create(USER, DOCUMENT_ID, new TestDocument("test"), TestDocument.class);
        String exceptionMessage = "Call to Cosmos DB failed for whatever reason";
        verifyTokenExchangeToCosmosDbFlow(null, TOKEN_EXCHANGE_USER_PAYLOAD, METHOD_POST, null, new Exception(exceptionMessage));

        /*
         *  No retries and Cosmos DB does not get called.
         */
        verifyNoMoreInteractions(mHttpClient);
        assertNotNull(doc);
        assertNotNull(doc.get());
        assertNull(doc.get().getDocument());
        assertNotNull(doc.get().getDocumentError());
        assertThat(
                doc.get().getDocumentError().getError().getMessage(),
                CoreMatchers.containsString(exceptionMessage));
    }

    @Test
    public void deleteEndToEnd() throws JSONException {
        AppCenterFuture<Document<Void>> doc = Storage.delete(USER, DOCUMENT_ID);
        verifyTokenExchangeToCosmosDbFlow(DOCUMENT_ID, TOKEN_EXCHANGE_USER_PAYLOAD, METHOD_DELETE, "", null);
        verify(mLocalDocumentStorage, times(1)).deleteOnline(eq(USER_TABLE_NAME), eq(RESOLVED_USER_PARTITION), eq(DOCUMENT_ID));
        verifyNoMoreInteractions(mLocalDocumentStorage);
        assertNotNull(doc.get());
        assertNull(doc.get().getDocument());
        assertNull(doc.get().getDocumentError());
    }

    @Test
    public void deleteTokenExchangeCallFails() throws JSONException {
        AppCenterFuture<Document<Void>> doc = Storage.delete(USER, DOCUMENT_ID);
        String exceptionMessage = "Call to token exchange failed for whatever reason";
        verifyTokenExchangeFlow(null, new SSLException(exceptionMessage));

        /*
         *  No retries and Cosmos DB does not get called.
         */
        verifyNoMoreInteractions(mHttpClient);
        assertNotNull(doc);
        assertNotNull(doc.get());
        assertNull(doc.get().getDocument());
        assertNotNull(doc.get().getDocumentError());
        assertThat(
                doc.get().getDocumentError().getError().getMessage(),
                CoreMatchers.containsString(exceptionMessage));
    }

    @Test
    public void deleteCosmosDbCallFails() throws JSONException {
        AppCenterFuture<Document<Void>> doc = Storage.delete(USER, DOCUMENT_ID);
        String exceptionMessage = "Call to Cosmos DB failed for whatever reason";
        verifyTokenExchangeToCosmosDbFlow(DOCUMENT_ID, TOKEN_EXCHANGE_USER_PAYLOAD, METHOD_DELETE, null, new HttpException(400, exceptionMessage));

        /*
         *  No retries and Cosmos DB does not get called.
         */
        verifyNoMoreInteractions(mHttpClient);
        assertNotNull(doc);
        assertNotNull(doc.get());
        assertNull(doc.get().getDocument());
        assertNotNull(doc.get().getDocumentError());
        assertThat(
                doc.get().getDocumentError().getError().getMessage(),
                CoreMatchers.containsString(exceptionMessage));
    }

    @Test
    public void deleteWithoutNetworkSucceeds() {
        when(mNetworkStateHelper.isNetworkConnected()).thenReturn(false);
        when(mLocalDocumentStorage.markForDeletion(eq(USER_TABLE_NAME), anyString(), anyString())).thenReturn(true);
        when(SharedPreferencesManager.getString(PREFERENCE_PARTITION_PREFIX + USER)).thenReturn(TOKEN_RESULT);
        AppCenterFuture<Document<Void>> result = Storage.delete(USER, DOCUMENT_ID);
        verify(mLocalDocumentStorage, times(1)).markForDeletion(eq(USER_TABLE_NAME), eq(RESOLVED_USER_PARTITION), eq(DOCUMENT_ID));
        verifyNoMoreInteractions(mLocalDocumentStorage);
        verifyNoMoreInteractions(mHttpClient);
        assertNull(result.get().getDocumentError());
    }

    @Test
    public void deleteWithoutNetworkFails() {
        when(mNetworkStateHelper.isNetworkConnected()).thenReturn(false);
        when(SharedPreferencesManager.getString(PREFERENCE_PARTITION_PREFIX + USER)).thenReturn(TOKEN_RESULT);
        when(mLocalDocumentStorage.markForDeletion(eq(USER_TABLE_NAME), anyString(), anyString())).thenReturn(false);
        AppCenterFuture<Document<Void>> result = Storage.delete(USER, DOCUMENT_ID);
        verify(mLocalDocumentStorage, times(1)).markForDeletion(eq(USER_TABLE_NAME), eq(RESOLVED_USER_PARTITION), eq(DOCUMENT_ID));
        verifyNoMoreInteractions(mLocalDocumentStorage);
        verifyNoMoreInteractions(mHttpClient);
        assertNotNull(result.get().getDocumentError());
    }

    @Test
    public void buildAppCenterGetDbTokenBodyPayload() {
        String expectedPayload = "{\"partitions\":[\"test\"]}";
        String payload = TokenExchange.buildAppCenterGetDbTokenBodyPayload("test");
        assertEquals(expectedPayload, payload);
    }

    @Test
    public void documentDeserialization() {
        Document<TestDocument> d =
                Utils.parseDocument(COSMOS_DB_DOCUMENT_RESPONSE_PAYLOAD, TestDocument.class);
        assertEquals(DOCUMENT_ID, d.getId());
        assertEquals(RESOLVED_USER_PARTITION, d.getPartition());
        assertEquals(TEST_FIELD_VALUE, d.getDocument().test);
        assertEquals(ETAG, d.getEtag());
        assertEquals(1550881731, d.getTimestamp());
    }

    @Test
    public void documentSerialization() {
        String jsonDocument = String.format("{\"test\": \"%s\"\n" + "}", TEST_FIELD_VALUE);
        TestDocument deserializedDocument = Utils.fromJson(jsonDocument, TestDocument.class);
        assertEquals(TEST_FIELD_VALUE, deserializedDocument.test);
    }

    @Test
    public void generateHeaders() {
        Map<String, String> headers = CosmosDb.addRequiredHeaders(new HashMap<String, String>(), RESOLVED_USER_PARTITION, "token");
        assertEquals(5, headers.size());
    }

    @Test
    public void generateAdditionalHeaders() {
        Map<String, String> headers =
                CosmosDb.addRequiredHeaders(
                        new HashMap<String, String>() {{
                            put("extra", "header");
                        }},
                        RESOLVED_USER_PARTITION,
                        "token");
        assertEquals(6, headers.size());
        String extra = headers.get("extra");
        assertNotNull(extra);
        assertTrue(headers.containsKey("extra") && extra.equals("header"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void urlEncodingThrowsNonExistingEncoding() {
        CosmosDb.urlEncode("a string to encode", "An encoding that doesn't exist");
    }

    @Test
    public void cosmosDbCallFailsShouldNotThrow() {

        /* Setup after get the token from cache, all the http call will fail. */
        Calendar expirationDate = Calendar.getInstance(TimeZone.getTimeZone("GMT"));
        expirationDate.add(Calendar.SECOND, 1000);
        String tokenResult = new Gson().toJson(new TokenResult().withPartition(RESOLVED_USER_PARTITION).withExpirationTime(expirationDate.getTime()).withToken("fakeToken"));
        when(SharedPreferencesManager.getString(PREFERENCE_PARTITION_PREFIX + RESOLVED_USER_PARTITION)).thenReturn(tokenResult);
        when(mLocalDocumentStorage.read(anyString(), anyString(), anyString(), any(Class.class), any(ReadOptions.class))).thenReturn(new Document(new Exception("read error.")));
        when(mHttpClient.callAsync(anyString(), anyString(), anyMapOf(String.class, String.class), any(HttpClient.CallTemplate.class), any(ServiceCallback.class))).then(new Answer<ServiceCall>() {

            @Override
            public ServiceCall answer(InvocationOnMock invocation) {
                ((ServiceCallback) invocation.getArguments()[4]).onCallFailed(new HttpException(403, "The operation is forbidden."));
                return mock(ServiceCall.class);
            }
        });

        /* Make the call and verify. */
        Storage.read(RESOLVED_USER_PARTITION, DOCUMENT_ID, TestDocument.class);
        Storage.delete(RESOLVED_USER_PARTITION, DOCUMENT_ID);
        Storage.create(RESOLVED_USER_PARTITION, DOCUMENT_ID, new TestDocument("test"), TestDocument.class);
        Storage.list(RESOLVED_USER_PARTITION, TestDocument.class);
    }

    @Test
    public void canCancelWhenCallNotFinished() {
        mockStatic(CosmosDb.class);
        mockStatic(TokenExchange.class);
        ServiceCall mockServiceCall = mock(ServiceCall.class);
        when(TokenExchange.getDbToken(anyString(), any(HttpClient.class), anyString(), anyString(), any(TokenExchange.TokenExchangeServiceCallback.class))).thenReturn(mockServiceCall);
        AppCenterFuture<Document<TestDocument>> doc = Storage.create(USER, DOCUMENT_ID, new TestDocument(TEST_FIELD_VALUE), TestDocument.class);
        Storage.setEnabled(false).get();
        assertNull(doc.get());
        verify(mockServiceCall).cancel();
    }

    @Test
    public void shouldNotOperateOnLocalStorageWhenPartitionNotExist() {

        /* Setup the network is disconnected. */
        String failedMessage = "Unable to find partition";
        when(mNetworkStateHelper.isNetworkConnected()).thenReturn(false);

        /* Make the call to create local document in local storage. */
        Document<TestDocument> doc = Storage.create(USER, DOCUMENT_ID, new TestDocument(TEST_FIELD_VALUE), TestDocument.class).get();

        /* Local storage create document should complete with not find partition error. */
        assertNotNull(doc);
        assertNotNull(doc.getDocumentError());
        assertTrue(doc.getDocumentError().getError().getMessage().contains(failedMessage));

        /* Make the call to read local document from local storage. */
        doc = Storage.read(USER, DOCUMENT_ID, TestDocument.class).get();

        /* Local storage read document should complete with not find partition error. */
        assertNotNull(doc);
        assertNotNull(doc.getDocumentError());
        assertTrue(doc.getDocumentError().getError().getMessage().contains(failedMessage));

        /* Make the call to delete local document from local storage. */
        Document<Void> deleteDocument = Storage.delete(USER, DOCUMENT_ID).get();

        /* Local storage delete document should complete with not find partition error. */
        assertNotNull(deleteDocument);
        assertNotNull(deleteDocument.getDocumentError());
        assertTrue(deleteDocument.getDocumentError().getError().getMessage().contains(failedMessage));
    }
}