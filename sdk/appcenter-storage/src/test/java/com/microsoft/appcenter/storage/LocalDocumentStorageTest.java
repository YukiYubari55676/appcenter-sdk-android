/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License.
 */

package com.microsoft.appcenter.storage;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteQueryBuilder;

import com.microsoft.appcenter.storage.models.BaseOptions;
import com.microsoft.appcenter.storage.models.Document;
import com.microsoft.appcenter.storage.models.DocumentError;
import com.microsoft.appcenter.storage.models.PendingOperation;
import com.microsoft.appcenter.storage.models.ReadOptions;
import com.microsoft.appcenter.storage.models.WriteOptions;
import com.microsoft.appcenter.utils.AppCenterLog;
import com.microsoft.appcenter.utils.context.AuthTokenContext;
import com.microsoft.appcenter.utils.storage.DatabaseManager;
import com.microsoft.appcenter.utils.storage.SQLiteUtils;

import org.hamcrest.CoreMatchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.AdditionalMatchers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.rule.PowerMockRule;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.powermock.api.mockito.PowerMockito.mockStatic;
import static org.powermock.api.mockito.PowerMockito.whenNew;

@SuppressWarnings("unused")
@PrepareForTest({
        SQLiteUtils.class,
        AppCenterLog.class,
        DatabaseManager.class,
        LocalDocumentStorage.class,
        AuthTokenContext.class})
public class LocalDocumentStorageTest {

    private static final String PARTITION = "partition";

    private static final String DOCUMENT_ID = "id";

    private static final String USER_ID = "12345";

    private static String mUserTableName = Utils.getUserTableName(USER_ID);

    @Rule
    public PowerMockRule mPowerMockRule = new PowerMockRule();

    private DatabaseManager mDatabaseManager;

    private LocalDocumentStorage mLocalDocumentStorage;

    private Cursor mCursor;

    @Mock
    private AuthTokenContext mAuthTokenContext;

    @Before
    public void setUp() throws Exception {
        mockStatic(AuthTokenContext.class);
        when(AuthTokenContext.getInstance()).thenReturn(mAuthTokenContext);
        when(mAuthTokenContext.getAccountId()).thenReturn(USER_ID);
        mockStatic(AppCenterLog.class);
        mDatabaseManager = mock(DatabaseManager.class);
        mCursor = mock(Cursor.class);
        whenNew(DatabaseManager.class).withAnyArguments().thenReturn(mDatabaseManager);

        mLocalDocumentStorage = new LocalDocumentStorage(mock(Context.class), null);
    }

    @Test
    public void getTableNameWithUserPartitionName() {
        String tableName = Utils.getTableName(Constants.USER, USER_ID);
        assertEquals(String.format(com.microsoft.appcenter.Constants.USER_TABLE_FORMAT, USER_ID), tableName);
    }

    @Test
    public void getTableNameWithReadonlyPartitionName() {
        String tableName = Utils.getTableName(Constants.READONLY, USER_ID);
        assertEquals(com.microsoft.appcenter.Constants.READONLY_TABLE, tableName);
    }

    @Test
    public void validPartitionName() {
        assertTrue(LocalDocumentStorage.isValidPartitionName(Constants.USER));
        assertTrue(LocalDocumentStorage.isValidPartitionName(Constants.READONLY));
        assertFalse(LocalDocumentStorage.isValidPartitionName("invalid"));
    }

    @Test
    public void updateGetsCalledInWrite() {
        mLocalDocumentStorage.writeOnline(mUserTableName, new Document<>("Test value", PARTITION, DOCUMENT_ID), new WriteOptions());
        ArgumentCaptor<ContentValues> argumentCaptor = ArgumentCaptor.forClass(ContentValues.class);
        verify(mDatabaseManager).replace(eq(mUserTableName), argumentCaptor.capture());
        assertNotNull(argumentCaptor.getValue());
    }

    @Test
    public void localStorageDoNotWriteWhenNotCache() {
        mLocalDocumentStorage.writeOffline(mUserTableName, new Document<>("Test", PARTITION, DOCUMENT_ID), new WriteOptions(WriteOptions.NO_CACHE));
        verify(mDatabaseManager, never()).replace(anyString(), any(ContentValues.class));
    }

    @Test
    public void updateGetsCalledInWriteWithPendingOperation() {
        mLocalDocumentStorage.writeOnline(mUserTableName, new Document<>("Test value", PARTITION, DOCUMENT_ID), new WriteOptions());
        ArgumentCaptor<ContentValues> argumentCaptor = ArgumentCaptor.forClass(ContentValues.class);
        verify(mDatabaseManager).replace(eq(mUserTableName), argumentCaptor.capture());
        assertNotNull(argumentCaptor.getValue());
    }

    @Test
    public void readReturnsErrorObjectOnDbRuntimeException() {
        when(mDatabaseManager.getCursor(anyString(), any(SQLiteQueryBuilder.class), any(String[].class), any(String[].class), anyString())).thenThrow(new RuntimeException());
        Document<String> doc = mLocalDocumentStorage.read(mUserTableName, PARTITION, DOCUMENT_ID, String.class, ReadOptions.CreateNoCacheOption());
        assertNotNull(doc);
        assertNull(doc.getDocument());
        assertTrue(doc.failed());
        assertEquals(DocumentError.class, doc.getDocumentError().getClass());
        assertThat(doc.getDocumentError().getError().getMessage(), CoreMatchers.containsString(LocalDocumentStorage.FAILED_TO_READ_FROM_CACHE));
    }

    @Test(expected = RuntimeException.class)
    public void cursorThrowsInGetOperations() {
        Cursor cursor = mock(Cursor.class);
        when(mDatabaseManager.getCursor(anyString(), any(SQLiteQueryBuilder.class), any(String[].class), any(String[].class), anyString())).thenReturn(cursor);
        when(cursor.moveToNext()).thenThrow(new RuntimeException());
        List<PendingOperation> pendingOperations = mLocalDocumentStorage.getPendingOperations(mUserTableName);
    }

    @Test
    public void createOrUpdateReturnErrorOnDbRuntimeException() {
        when(mDatabaseManager.getCursor(anyString(), any(SQLiteQueryBuilder.class), any(String[].class), any(String[].class), anyString())).thenThrow(new RuntimeException());
        Document<String> doc = mLocalDocumentStorage.createOrUpdateOffline(mUserTableName, PARTITION, DOCUMENT_ID, "test", String.class, new WriteOptions());
        assertNotNull(doc);
        assertNull(doc.getDocument());
        assertTrue(doc.failed());
        assertEquals(DocumentError.class, doc.getDocumentError().getClass());
        assertThat(doc.getDocumentError().getError().getMessage(), CoreMatchers.containsString(LocalDocumentStorage.FAILED_TO_READ_FROM_CACHE));
    }

    @Test
    public void createOrUpdateFailedToWriteException() {
        when(mDatabaseManager.getCursor(anyString(), any(SQLiteQueryBuilder.class), any(String[].class), any(String[].class), anyString())).thenReturn(mCursor);
        when(mDatabaseManager.nextValues(mCursor)).thenReturn(null);
        when(mDatabaseManager.replace(anyString(), any(ContentValues.class))).thenReturn(-1L);
        Document<String> doc = mLocalDocumentStorage.createOrUpdateOffline(mUserTableName, PARTITION, DOCUMENT_ID, "test", String.class, new WriteOptions());
        assertNotNull(doc);
        assertNotNull(doc.getDocumentError().getError());
    }

    @Test
    public void deleteReturnsErrorObjectOnDbRuntimeException() {
        doThrow(new RuntimeException()).when(mDatabaseManager).delete(anyString(), anyString(), any(String[].class));
        mLocalDocumentStorage.deleteOnline(mUserTableName, PARTITION, DOCUMENT_ID);
        verify(mDatabaseManager).delete(eq(mUserTableName), anyString(), AdditionalMatchers.aryEq(new String[]{PARTITION, DOCUMENT_ID}));
    }

    @Test
    public void writeDeleteFails() {
        when(mDatabaseManager.replace(anyString(), any(ContentValues.class))).thenReturn(-1L);
        boolean isSuccess = mLocalDocumentStorage.markForDeletion(mUserTableName, PARTITION, DOCUMENT_ID);
        ArgumentCaptor<ContentValues> argumentCaptor = ArgumentCaptor.forClass(ContentValues.class);
        verify(mDatabaseManager).replace(eq(mUserTableName), argumentCaptor.capture());
        assertNotNull(argumentCaptor.getValue());
        assertFalse(isSuccess);
    }

    @Test
    public void writeDeleteSucceeds() {
        when(mDatabaseManager.replace(anyString(), any(ContentValues.class))).thenReturn(1L);
        boolean isSuccess = mLocalDocumentStorage.markForDeletion(mUserTableName, PARTITION, DOCUMENT_ID);
        ArgumentCaptor<ContentValues> argumentCaptor = ArgumentCaptor.forClass(ContentValues.class);
        verify(mDatabaseManager).replace(eq(mUserTableName), argumentCaptor.capture());
        assertNotNull(argumentCaptor.getValue());
        assertTrue(isSuccess);
    }

    @Test
    public void verifyOptionsConstructors() {
        assertEquals(BaseOptions.INFINITE, ReadOptions.CreateInfiniteCacheOption().getDeviceTimeToLive());
        assertEquals(BaseOptions.NO_CACHE, ReadOptions.CreateNoCacheOption().getDeviceTimeToLive());
        assertEquals(BaseOptions.INFINITE, WriteOptions.CreateInfiniteCacheOption().getDeviceTimeToLive());
        assertEquals(BaseOptions.NO_CACHE, WriteOptions.CreateNoCacheOption().getDeviceTimeToLive());
    }

    @Test(expected = IllegalArgumentException.class)
    public void verifyBaseOptionsWithNegativeTtl() {
        ReadOptions readOptions = new ReadOptions(-100);
    }

    @Test
    public void optionsExpirationTest() {
        ReadOptions readOptions = new ReadOptions(1);
        assertTrue(ReadOptions.isExpired(-1));
    }

    @Test
    public void getPendingOperationsOnNonUserTable() {
        List<PendingOperation> operations = mLocalDocumentStorage.getPendingOperations(null);
        assertNotNull(operations);
        assertEquals(0, operations.size());
    }
}
