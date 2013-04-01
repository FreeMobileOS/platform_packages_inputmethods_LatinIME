/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.android.inputmethod.dictionarypack;

import android.app.DownloadManager;
import android.app.DownloadManager.Query;
import android.app.DownloadManager.Request;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.util.Log;

import com.android.inputmethod.compat.ConnectivityManagerCompatUtils;
import com.android.inputmethod.compat.DownloadManagerCompatUtils;
import com.android.inputmethod.latin.R;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Handler for the update process.
 *
 * This class is in charge of coordinating the update process for the various dictionaries
 * stored in the dictionary pack.
 */
public final class UpdateHandler {
    static final String TAG = "DictionaryProvider:" + UpdateHandler.class.getSimpleName();
    private static final boolean DEBUG = DictionaryProvider.DEBUG;

    // Used to prevent trying to read the id of the downloaded file before it is written
    static final Object sSharedIdProtector = new Object();

    // Value used to mean this is not a real DownloadManager downloaded file id
    // DownloadManager uses as an ID numbers returned out of an AUTOINCREMENT column
    // in SQLite, so it should never return anything < 0.
    public static final int NOT_AN_ID = -1;
    public static final int MAXIMUM_SUPPORTED_FORMAT_VERSION = 2;

    // Arbitrary. Probably good if it's a power of 2, and a couple thousand bytes long.
    private static final int FILE_COPY_BUFFER_SIZE = 8192;

    // Table fixed values for metadata / downloads
    final static String METADATA_NAME = "metadata";
    final static int METADATA_TYPE = 0;
    final static int WORDLIST_TYPE = 1;

    // Suffix for generated dictionary files
    private static final String DICT_FILE_SUFFIX = ".dict";
    // Name of the category for the main dictionary
    public static final String MAIN_DICTIONARY_CATEGORY = "main";

    // The id for the "dictionary available" notification.
    static final int DICT_AVAILABLE_NOTIFICATION_ID = 1;

    /**
     * An interface for UIs or services that want to know when something happened.
     *
     * This is chiefly used by the dictionary manager UI.
     */
    public interface UpdateEventListener {
        public void downloadedMetadata(boolean succeeded);
        public void wordListDownloadFinished(String wordListId, boolean succeeded);
        public void updateCycleCompleted();
    }

    /**
     * The list of currently registered listeners.
     */
    private static List<UpdateEventListener> sUpdateEventListeners
            = Collections.synchronizedList(new LinkedList<UpdateEventListener>());

    /**
     * Register a new listener to be notified of updates.
     *
     * Don't forget to call unregisterUpdateEventListener when done with it, or
     * it will leak the register.
     */
    public static void registerUpdateEventListener(final UpdateEventListener listener) {
        sUpdateEventListeners.add(listener);
    }

    /**
     * Unregister a previously registered listener.
     */
    public static void unregisterUpdateEventListener(final UpdateEventListener listener) {
        sUpdateEventListeners.remove(listener);
    }

    private static final String DOWNLOAD_OVER_METERED_SETTING_PREFS_KEY = "downloadOverMetered";

    /**
     * Write the DownloadManager ID of the currently downloading metadata to permanent storage.
     *
     * @param context to open shared prefs
     * @param uri the uri of the metadata
     * @param downloadId the id returned by DownloadManager
     */
    private static void writeMetadataDownloadId(final Context context, final String uri,
            final long downloadId) {
        MetadataDbHelper.registerMetadataDownloadId(context, uri, downloadId);
    }

    public static final int DOWNLOAD_OVER_METERED_SETTING_UNKNOWN = 0;
    public static final int DOWNLOAD_OVER_METERED_ALLOWED = 1;
    public static final int DOWNLOAD_OVER_METERED_DISALLOWED = 2;

    /**
     * Sets the setting that tells us whether we may download over a metered connection.
     */
    public static void setDownloadOverMeteredSetting(final Context context,
            final boolean shouldDownloadOverMetered) {
        final SharedPreferences prefs = CommonPreferences.getCommonPreferences(context);
        final SharedPreferences.Editor editor = prefs.edit();
        editor.putInt(DOWNLOAD_OVER_METERED_SETTING_PREFS_KEY, shouldDownloadOverMetered
                ? DOWNLOAD_OVER_METERED_ALLOWED : DOWNLOAD_OVER_METERED_DISALLOWED);
        editor.apply();
    }

    /**
     * Gets the setting that tells us whether we may download over a metered connection.
     *
     * This returns one of the constants above.
     */
    public static int getDownloadOverMeteredSetting(final Context context) {
        final SharedPreferences prefs = CommonPreferences.getCommonPreferences(context);
        final int setting = prefs.getInt(DOWNLOAD_OVER_METERED_SETTING_PREFS_KEY,
                DOWNLOAD_OVER_METERED_SETTING_UNKNOWN);
        return setting;
    }

    /**
     * Download latest metadata from the server through DownloadManager for all known clients
     * @param context The context for retrieving resources
     * @param updateNow Whether we should update NOW, or respect bandwidth policies
     */
    public static void update(final Context context, final boolean updateNow) {
        // TODO: loop through all clients instead of only doing the default one.
        final TreeSet<String> uris = new TreeSet<String>();
        final Cursor cursor = MetadataDbHelper.queryClientIds(context);
        if (null == cursor) return;
        try {
            if (!cursor.moveToFirst()) return;
            do {
                final String clientId = cursor.getString(0);
                if (TextUtils.isEmpty(clientId)) continue; // This probably can't happen
                final String metadataUri =
                        MetadataDbHelper.getMetadataUriAsString(context, clientId);
                PrivateLog.log("Update for clientId " + Utils.s(clientId), context);
                Utils.l("Update for clientId", clientId, " which uses URI ", metadataUri);
                uris.add(metadataUri);
            } while (cursor.moveToNext());
        } finally {
            cursor.close();
        }
        for (final String metadataUri : uris) {
            if (!TextUtils.isEmpty(metadataUri)) {
                // If the metadata URI is empty, that means we should never update it at all.
                // It should not be possible to come here with a null metadata URI, because
                // it should have been rejected at the time of client registration; if there
                // is a bug and it happens anyway, doing nothing is the right thing to do.
                // For more information, {@see DictionaryProvider#insert(Uri, ContentValues)}.
                updateClientsWithMetadataUri(context, updateNow, metadataUri);
            }
        }
    }

    /**
     * Download latest metadata from the server through DownloadManager for all relevant clients
     *
     * @param context The context for retrieving resources
     * @param updateNow Whether we should update NOW, or respect bandwidth policies
     * @param metadataUri The client to update
     */
    private static void updateClientsWithMetadataUri(final Context context,
            final boolean updateNow, final String metadataUri) {
        PrivateLog.log("Update for metadata URI " + Utils.s(metadataUri), context);
        final Request metadataRequest = new Request(Uri.parse(metadataUri));
        Utils.l("Request =", metadataRequest);

        final Resources res = context.getResources();
        // By default, download over roaming is allowed and all network types are allowed too.
        if (!updateNow) {
            final boolean allowedOverMetered = res.getBoolean(R.bool.allow_over_metered);
            // If we don't have to update NOW, then only do it over non-metered connections.
            if (DownloadManagerCompatUtils.hasSetAllowedOverMetered()) {
                DownloadManagerCompatUtils.setAllowedOverMetered(metadataRequest,
                        allowedOverMetered);
            } else if (!allowedOverMetered) {
                metadataRequest.setAllowedNetworkTypes(Request.NETWORK_WIFI);
            }
            metadataRequest.setAllowedOverRoaming(res.getBoolean(R.bool.allow_over_roaming));
        }
        final boolean notificationVisible = updateNow
                ? res.getBoolean(R.bool.display_notification_for_user_requested_update)
                : res.getBoolean(R.bool.display_notification_for_auto_update);

        metadataRequest.setTitle(res.getString(R.string.download_description));
        metadataRequest.setNotificationVisibility(notificationVisible
                ? Request.VISIBILITY_VISIBLE : Request.VISIBILITY_HIDDEN);
        metadataRequest.setVisibleInDownloadsUi(
                res.getBoolean(R.bool.metadata_downloads_visible_in_download_UI));

        final DownloadManager manager =
                (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (null == manager) {
            // Download manager is not installed or disabled.
            // TODO: fall back to self-managed download?
            return;
        }
        cancelUpdateWithDownloadManager(context, metadataUri, manager);
        final long downloadId;
        synchronized (sSharedIdProtector) {
            downloadId = manager.enqueue(metadataRequest);
            Utils.l("Metadata download requested with id", downloadId);
            // If there is already a download in progress, it's been there for a while and
            // there is probably something wrong with download manager. It's best to just
            // overwrite the id and request it again. If the old one happens to finish
            // anyway, we don't know about its ID any more, so the downloadFinished
            // method will ignore it.
            writeMetadataDownloadId(context, metadataUri, downloadId);
        }
        PrivateLog.log("Requested download with id " + downloadId, context);
    }

    /**
     * Cancels a pending update, if there is one.
     *
     * If none, this is a no-op.
     *
     * @param context the context to open the database on
     * @param clientId the id of the client
     * @param manager an instance of DownloadManager
     */
    private static void cancelUpdateWithDownloadManager(final Context context,
            final String clientId, final DownloadManager manager) {
        synchronized (sSharedIdProtector) {
            final long metadataDownloadId =
                    MetadataDbHelper.getMetadataDownloadIdForClient(context, clientId);
            if (NOT_AN_ID == metadataDownloadId) return;
            manager.remove(metadataDownloadId);
            writeMetadataDownloadId(context,
                    MetadataDbHelper.getMetadataUriAsString(context, clientId), NOT_AN_ID);
        }
        // Consider a cancellation as a failure. As such, inform listeners that the download
        // has failed.
        for (UpdateEventListener listener : linkedCopyOfList(sUpdateEventListeners)) {
            listener.downloadedMetadata(false);
        }
    }

    /**
     * Cancels a pending update, if there is one.
     *
     * If there is none, this is a no-op. This is a helper method that gets the
     * download manager service.
     *
     * @param context the context, to get an instance of DownloadManager
     * @param clientId the ID of the client we want to cancel the update of
     */
    public static void cancelUpdate(final Context context, final String clientId) {
        final DownloadManager manager =
                    (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (null != manager) cancelUpdateWithDownloadManager(context, clientId, manager);
    }

    /**
     * Registers a download request and flags it as downloading in the metadata table.
     *
     * This is a helper method that exists to avoid race conditions where DownloadManager might
     * finish downloading the file before the data is committed to the database.
     * It registers the request with the DownloadManager service and also updates the metadata
     * database directly within a synchronized section.
     * This method has no intelligence about the data it commits to the database aside from the
     * download request id, which is not known before submitting the request to the download
     * manager. Hence, it only updates the relevant line.
     *
     * @param manager the download manager service to register the request with.
     * @param request the request to register.
     * @param db the metadata database.
     * @param id the id of the word list.
     * @param version the version of the word list.
     * @return the download id returned by the download manager.
     */
    public static long registerDownloadRequest(final DownloadManager manager, final Request request,
            final SQLiteDatabase db, final String id, final int version) {
        Utils.l("RegisterDownloadRequest for word list id : ", id, ", version ", version);
        final long downloadId;
        synchronized (sSharedIdProtector) {
            downloadId = manager.enqueue(request);
            Utils.l("Download requested with id", downloadId);
            MetadataDbHelper.markEntryAsDownloading(db, id, version, downloadId);
        }
        return downloadId;
    }

    /**
     * Retrieve information about a specific download from DownloadManager.
     */
    private static CompletedDownloadInfo getCompletedDownloadInfo(final DownloadManager manager,
            final long downloadId) {
        final Query query = new Query().setFilterById(downloadId);
        final Cursor cursor = manager.query(query);

        if (null == cursor) {
            return new CompletedDownloadInfo(null, downloadId, DownloadManager.STATUS_FAILED);
        }
        try {
            final String uri;
            final int status;
            if (cursor.moveToNext()) {
                final int columnStatus = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
                final int columnError = cursor.getColumnIndex(DownloadManager.COLUMN_REASON);
                final int columnUri = cursor.getColumnIndex(DownloadManager.COLUMN_URI);
                final int error = cursor.getInt(columnError);
                status = cursor.getInt(columnStatus);
                uri = cursor.getString(columnUri);
                if (DownloadManager.STATUS_SUCCESSFUL != status) {
                    Log.e(TAG, "Permanent failure of download " + downloadId
                            + " with error code: " + error);
                }
            } else {
                uri = null;
                status = DownloadManager.STATUS_FAILED;
            }
            return new CompletedDownloadInfo(uri, downloadId, status);
        } finally {
            cursor.close();
        }
    }

    private static ArrayList<DownloadRecord> getDownloadRecordsForCompletedDownloadInfo(
            final Context context, final CompletedDownloadInfo downloadInfo) {
        // Get and check the ID of the file we are waiting for, compare them to downloaded ones
        synchronized(sSharedIdProtector) {
            final ArrayList<DownloadRecord> downloadRecords =
                    MetadataDbHelper.getDownloadRecordsForDownloadId(context,
                            downloadInfo.mDownloadId);
            // If any of these is metadata, we should update the DB
            boolean hasMetadata = false;
            for (DownloadRecord record : downloadRecords) {
                if (null == record.mAttributes) {
                    hasMetadata = true;
                    break;
                }
            }
            if (hasMetadata) {
                writeMetadataDownloadId(context, downloadInfo.mUri, NOT_AN_ID);
                MetadataDbHelper.saveLastUpdateTimeOfUri(context, downloadInfo.mUri);
            }
            return downloadRecords;
        }
    }

    /**
     * Take appropriate action after a download finished, in success or in error.
     *
     * This is called by the system upon broadcast from the DownloadManager that a file
     * has been downloaded successfully.
     * After a simple check that this is actually the file we are waiting for, this
     * method basically coordinates the parsing and comparison of metadata, and fires
     * the computation of the list of actions that should be taken then executes them.
     *
     * @param context The context for this action.
     * @param intent The intent from the DownloadManager containing details about the download.
     */
    /* package */ static void downloadFinished(final Context context, final Intent intent) {
        // Get and check the ID of the file that was downloaded
        final long fileId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, NOT_AN_ID);
        PrivateLog.log("Download finished with id " + fileId, context);
        Utils.l("DownloadFinished with id", fileId);
        if (NOT_AN_ID == fileId) return; // Spurious wake-up: ignore

        final DownloadManager manager =
                (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        final CompletedDownloadInfo downloadInfo = getCompletedDownloadInfo(manager, fileId);

        final ArrayList<DownloadRecord> recordList =
                getDownloadRecordsForCompletedDownloadInfo(context, downloadInfo);
        if (null == recordList) return; // It was someone else's download.
        Utils.l("Received result for download ", fileId);

        // TODO: handle gracefully a null pointer here. This is practically impossible because
        // we come here only when DownloadManager explicitly called us when it ended a
        // download, so we are pretty sure it's alive. It's theoretically possible that it's
        // disabled right inbetween the firing of the intent and the control reaching here.

        for (final DownloadRecord record : recordList) {
            // downloadSuccessful is not final because we may still have exceptions from now on
            boolean downloadSuccessful = false;
            try {
                if (downloadInfo.wasSuccessful()) {
                    downloadSuccessful = handleDownloadedFile(context, record, manager, fileId);
                }
            } finally {
                if (record.isMetadata()) {
                    publishUpdateMetadataCompleted(context, downloadSuccessful);
                } else {
                    final SQLiteDatabase db = MetadataDbHelper.getDb(context, record.mClientId);
                    publishUpdateWordListCompleted(context, downloadSuccessful, fileId,
                            db, record.mAttributes, record.mClientId);
                }
            }
        }
        // Now that we're done using it, we can remove this download from DLManager
        manager.remove(fileId);
    }

    /**
     * Sends a broadcast informing listeners that the dictionaries were updated.
     *
     * This will call all local listeners through the UpdateEventListener#downloadedMetadata
     * callback (for example, the dictionary provider interface uses this to stop the Loading
     * animation) and send a broadcast about the metadata having been updated. For a client of
     * the dictionary pack like Latin IME, this means it should re-query the dictionary pack
     * for any relevant new data.
     *
     * @param context the context, to send the broadcast.
     * @param downloadSuccessful whether the download of the metadata was successful or not.
     */
    public static void publishUpdateMetadataCompleted(final Context context,
            final boolean downloadSuccessful) {
        // We need to warn all listeners of what happened. But some listeners may want to
        // remove themselves or re-register something in response. Hence we should take a
        // snapshot of the listener list and warn them all. This also prevents any
        // concurrent modification problem of the static list.
        for (UpdateEventListener listener : linkedCopyOfList(sUpdateEventListeners)) {
            listener.downloadedMetadata(downloadSuccessful);
        }
        publishUpdateCycleCompletedEvent(context);
    }

    private static void publishUpdateWordListCompleted(final Context context,
            final boolean downloadSuccessful, final long fileId,
            final SQLiteDatabase db, final ContentValues downloadedFileRecord,
            final String clientId) {
        synchronized(sSharedIdProtector) {
            if (downloadSuccessful) {
                final ActionBatch actions = new ActionBatch();
                actions.add(new ActionBatch.InstallAfterDownloadAction(clientId,
                        downloadedFileRecord));
                actions.execute(context, new LogProblemReporter(TAG));
            } else {
                MetadataDbHelper.deleteDownloadingEntry(db, fileId);
            }
        }
        // See comment above about #linkedCopyOfLists
        for (UpdateEventListener listener : linkedCopyOfList(sUpdateEventListeners)) {
            listener.wordListDownloadFinished(downloadedFileRecord.getAsString(
                            MetadataDbHelper.WORDLISTID_COLUMN), downloadSuccessful);
        }
        publishUpdateCycleCompletedEvent(context);
    }

    private static void publishUpdateCycleCompletedEvent(final Context context) {
        // Even if this is not successful, we have to publish the new state.
        PrivateLog.log("Publishing update cycle completed event", context);
        Utils.l("Publishing update cycle completed event");
        for (UpdateEventListener listener : linkedCopyOfList(sUpdateEventListeners)) {
            listener.updateCycleCompleted();
        }
        signalNewDictionaryState(context);
    }

    private static boolean handleDownloadedFile(final Context context,
            final DownloadRecord downloadRecord, final DownloadManager manager,
            final long fileId) {
        try {
            // {@link handleWordList(Context,InputStream,ContentValues)}.
            // Handle the downloaded file according to its type
            if (downloadRecord.isMetadata()) {
                Utils.l("Data D/L'd is metadata for", downloadRecord.mClientId);
                // #handleMetadata() closes its InputStream argument
                handleMetadata(context, new ParcelFileDescriptor.AutoCloseInputStream(
                        manager.openDownloadedFile(fileId)), downloadRecord.mClientId);
            } else {
                Utils.l("Data D/L'd is a word list");
                final int wordListStatus = downloadRecord.mAttributes.getAsInteger(
                        MetadataDbHelper.STATUS_COLUMN);
                if (MetadataDbHelper.STATUS_DOWNLOADING == wordListStatus) {
                    // #handleWordList() closes its InputStream argument
                    handleWordList(context, new ParcelFileDescriptor.AutoCloseInputStream(
                            manager.openDownloadedFile(fileId)), downloadRecord);
                } else {
                    Log.e(TAG, "Spurious download ended. Maybe a cancelled download?");
                }
            }
            return true;
        } catch (FileNotFoundException e) {
            Log.e(TAG, "A file was downloaded but it can't be opened", e);
        } catch (IOException e) {
            // Can't read the file... disk damage?
            Log.e(TAG, "Can't read a file", e);
            // TODO: Check with UX how we should warn the user.
        } catch (IllegalStateException e) {
            // The format of the downloaded file is incorrect. We should maybe report upstream?
            Log.e(TAG, "Incorrect data received", e);
        } catch (BadFormatException e) {
            // The format of the downloaded file is incorrect. We should maybe report upstream?
            Log.e(TAG, "Incorrect data received", e);
        }
        return false;
    }

    /**
     * Returns a copy of the specified list, with all elements copied.
     *
     * This returns a linked list.
     */
    private static <T> List<T> linkedCopyOfList(final List<T> src) {
        // Instantiation of a parameterized type is not possible in Java, so it's not possible to
        // return the same type of list that was passed - probably the same reason why Collections
        // does not do it. So we need to decide statically which concrete type to return.
        return new LinkedList<T>(src);
    }

    /**
     * Warn Android Keyboard that the state of dictionaries changed and it should refresh its data.
     */
    private static void signalNewDictionaryState(final Context context) {
        final Intent newDictBroadcast =
                new Intent(DictionaryPackConstants.NEW_DICTIONARY_INTENT_ACTION);
        context.sendBroadcast(newDictBroadcast);
    }

    /**
     * Parse metadata and take appropriate action (that is, upgrade dictionaries).
     * @param context the context to read settings.
     * @param stream an input stream pointing to the downloaded data. May not be null.
     *  Will be closed upon finishing.
     * @param clientId the ID of the client to update
     * @throws BadFormatException if the metadata is not in a known format.
     * @throws IOException if the downloaded file can't be read from the disk
     */
    private static void handleMetadata(final Context context, final InputStream stream,
            final String clientId) throws IOException, BadFormatException {
        Utils.l("Entering handleMetadata");
        final List<WordListMetadata> newMetadata;
        final InputStreamReader reader = new InputStreamReader(stream);
        try {
            // According to the doc InputStreamReader buffers, so no need to add a buffering layer
            newMetadata = MetadataHandler.readMetadata(reader);
        } finally {
            reader.close();
        }

        Utils.l("Downloaded metadata :", newMetadata);
        PrivateLog.log("Downloaded metadata\n" + newMetadata, context);

        final ActionBatch actions = computeUpgradeTo(context, clientId, newMetadata);
        // TODO: Check with UX how we should report to the user
        // TODO: add an action to close the database
        actions.execute(context, new LogProblemReporter(TAG));
    }

    /**
     * Handle a word list: put it in its right place, and update the passed content values.
     * @param context the context for opening files.
     * @param inputStream an input stream pointing to the downloaded data. May not be null.
     *  Will be closed upon finishing.
     * @param downloadRecord the content values to fill the file name in.
     * @throws IOException if files can't be read or written.
     * @throws BadFormatException if the md5 checksum doesn't match the metadata.
     */
    private static void handleWordList(final Context context,
            final InputStream inputStream, final DownloadRecord downloadRecord)
            throws IOException, BadFormatException {

        // DownloadManager does not have the ability to put the file directly where we want
        // it, so we had it download to a temporary place. Now we move it. It will be deleted
        // automatically by DownloadManager.
        Utils.l("Downloaded a new word list :", downloadRecord.mAttributes.getAsString(
                MetadataDbHelper.DESCRIPTION_COLUMN), "for", downloadRecord.mClientId);
        PrivateLog.log("Downloaded a new word list with description : "
                + downloadRecord.mAttributes.getAsString(MetadataDbHelper.DESCRIPTION_COLUMN)
                + " for " + downloadRecord.mClientId, context);

        final String locale =
                downloadRecord.mAttributes.getAsString(MetadataDbHelper.LOCALE_COLUMN);
        final String destinationFile = getTempFileName(context, locale);
        downloadRecord.mAttributes.put(MetadataDbHelper.LOCAL_FILENAME_COLUMN, destinationFile);

        FileOutputStream outputStream = null;
        try {
            outputStream = context.openFileOutput(destinationFile, Context.MODE_PRIVATE);
            copyFile(inputStream, outputStream);
        } finally {
            inputStream.close();
            if (outputStream != null) {
                outputStream.close();
            }
        }

        // TODO: Consolidate this MD5 calculation with file copying above.
        // We need to reopen the file because the inputstream bytes have been consumed, and there
        // is nothing in InputStream to reopen or rewind the stream
        FileInputStream copiedFile = null;
        final String md5sum;
        try {
            copiedFile = context.openFileInput(destinationFile);
            md5sum = MD5Calculator.checksum(copiedFile);
        } finally {
            if (copiedFile != null) {
                copiedFile.close();
            }
        }
        if (TextUtils.isEmpty(md5sum)) {
            return; // We can't compute the checksum anyway, so return and hope for the best
        }
        if (!md5sum.equals(downloadRecord.mAttributes.getAsString(
                MetadataDbHelper.CHECKSUM_COLUMN))) {
            context.deleteFile(destinationFile);
            throw new BadFormatException("MD5 checksum check failed : \"" + md5sum + "\" <> \""
                    + downloadRecord.mAttributes.getAsString(MetadataDbHelper.CHECKSUM_COLUMN)
                    + "\"");
        }
    }

    /**
     * Copies in to out using FileChannels.
     *
     * This tries to use channels for fast copying. If it doesn't work, fall back to
     * copyFileFallBack below.
     *
     * @param in the stream to copy from.
     * @param out the stream to copy to.
     * @throws IOException if both the normal and fallback methods raise exceptions.
     */
    private static void copyFile(final InputStream in, final OutputStream out)
            throws IOException {
        Utils.l("Copying files");
        if (!(in instanceof FileInputStream) || !(out instanceof FileOutputStream)) {
            Utils.l("Not the right types");
            copyFileFallback(in, out);
        } else {
            try {
                final FileChannel sourceChannel = ((FileInputStream) in).getChannel();
                final FileChannel destinationChannel = ((FileOutputStream) out).getChannel();
                sourceChannel.transferTo(0, Integer.MAX_VALUE, destinationChannel);
            } catch (IOException e) {
                // Can't work with channels, or something went wrong. Copy by hand.
                Utils.l("Won't work");
                copyFileFallback(in, out);
            }
        }
    }

    /**
     * Copies in to out with read/write methods, not FileChannels.
     *
     * @param in the stream to copy from.
     * @param out the stream to copy to.
     * @throws IOException if a read or a write fails.
     */
    private static void copyFileFallback(final InputStream in, final OutputStream out)
            throws IOException {
        Utils.l("Falling back to slow copy");
        final byte[] buffer = new byte[FILE_COPY_BUFFER_SIZE];
        for (int readBytes = in.read(buffer); readBytes >= 0; readBytes = in.read(buffer))
            out.write(buffer, 0, readBytes);
    }

    /**
     * Creates and returns a new file to store a dictionary
     * @param context the context to use to open the file.
     * @param locale the locale for this dictionary, to make the file name more readable.
     * @return the file name, or throw an exception.
     * @throws IOException if the file cannot be created.
     */
    private static String getTempFileName(final Context context, final String locale)
            throws IOException {
        Utils.l("Entering openTempFileOutput");
        final File dir = context.getFilesDir();
        final File f = File.createTempFile(locale + "___", DICT_FILE_SUFFIX, dir);
        Utils.l("File name is", f.getName());
        return f.getName();
    }

    /**
     * Compare metadata (collections of word lists).
     *
     * This method takes whole metadata sets directly and compares them, matching the wordlists in
     * each of them on the id. It creates an ActionBatch object that can be .execute()'d to perform
     * the actual upgrade from `from' to `to'.
     *
     * @param context the context to open databases on.
     * @param clientId the id of the client.
     * @param from the dictionary descriptor (as a list of wordlists) to upgrade from.
     * @param to the dictionary descriptor (as a list of wordlists) to upgrade to.
     * @return an ordered list of runnables to be called to upgrade.
     */
    private static ActionBatch compareMetadataForUpgrade(final Context context,
            final String clientId, List<WordListMetadata> from, List<WordListMetadata> to) {
        final ActionBatch actions = new ActionBatch();
        // Upgrade existing word lists
        Utils.l("Comparing dictionaries");
        final Set<String> wordListIds = new TreeSet<String>();
        // TODO: Can these be null?
        if (null == from) from = new ArrayList<WordListMetadata>();
        if (null == to) to = new ArrayList<WordListMetadata>();
        for (WordListMetadata wlData : from) wordListIds.add(wlData.mId);
        for (WordListMetadata wlData : to) wordListIds.add(wlData.mId);
        for (String id : wordListIds) {
            final WordListMetadata currentInfo = MetadataHandler.findWordListById(from, id);
            final WordListMetadata metadataInfo = MetadataHandler.findWordListById(to, id);
            // TODO: Remove the following unnecessary check, since we are now doing the filtering
            // inside findWordListById.
            final WordListMetadata newInfo = null == metadataInfo
                    || metadataInfo.mFormatVersion > MAXIMUM_SUPPORTED_FORMAT_VERSION
                            ? null : metadataInfo;
            Utils.l("Considering updating ", id, "currentInfo =", currentInfo);

            if (null == currentInfo && null == newInfo) {
                // This may happen if a new word list appeared that we can't handle.
                if (null == metadataInfo) {
                    // What happened? Bug in Set<>?
                    Log.e(TAG, "Got an id for a wordlist that is neither in from nor in to");
                } else {
                    // We may come here if there is a new word list that we can't handle.
                    Log.i(TAG, "Can't handle word list with id '" + id + "' because it has format"
                            + " version " + metadataInfo.mFormatVersion + " and the maximum version"
                            + "we can handle is " + MAXIMUM_SUPPORTED_FORMAT_VERSION);
                }
                continue;
            } else if (null == currentInfo) {
                // This is the case where a new list that we did not know of popped on the server.
                // Make it available.
                actions.add(new ActionBatch.MakeAvailableAction(clientId, newInfo));
            } else if (null == newInfo) {
                // This is the case where an old list we had is not in the server data any more.
                // Pass false to ForgetAction: this may be installed and we still want to apply
                // a forget-like action (remove the URL) if it is, so we want to turn off the
                // status == AVAILABLE check. If it's DELETING, this is the right thing to do,
                // as we want to leave the record as long as Android Keyboard has not deleted it ;
                // the record will be removed when the file is actually deleted.
                actions.add(new ActionBatch.ForgetAction(clientId, currentInfo, false));
            } else {
                final SQLiteDatabase db = MetadataDbHelper.getDb(context, clientId);
                if (newInfo.mVersion == currentInfo.mVersion) {
                    // If it's the same id/version, we update the DB with the new values.
                    // It doesn't matter too much if they didn't change.
                    actions.add(new ActionBatch.UpdateDataAction(clientId, newInfo));
                } else if (newInfo.mVersion > currentInfo.mVersion) {
                    // If it's a new version, it's a different entry in the database. Make it
                    // available, and if it's installed, also start the download.
                    final ContentValues values = MetadataDbHelper.getContentValuesByWordListId(db,
                            currentInfo.mId, currentInfo.mVersion);
                    final int status = values.getAsInteger(MetadataDbHelper.STATUS_COLUMN);
                    actions.add(new ActionBatch.MakeAvailableAction(clientId, newInfo));
                    if (status == MetadataDbHelper.STATUS_INSTALLED
                            || status == MetadataDbHelper.STATUS_DISABLED) {
                        actions.add(new ActionBatch.StartDownloadAction(clientId, newInfo, false));
                    } else {
                        // Pass true to ForgetAction: this is indeed an update to a non-installed
                        // word list, so activate status == AVAILABLE check
                        // In case the status is DELETING, this is the right thing to do. It will
                        // leave the entry as DELETING and remove its URL so that Android Keyboard
                        // can delete it the next time it starts up.
                        actions.add(new ActionBatch.ForgetAction(clientId, currentInfo, true));
                    }
                } else if (DEBUG) {
                    Log.i(TAG, "Not updating word list " + id
                            + " : current list timestamp is " + currentInfo.mLastUpdate
                                    + " ; new list timestamp is " + newInfo.mLastUpdate);
                }
            }
        }
        return actions;
    }

    /**
     * Computes an upgrade from the current state of the dictionaries to some desired state.
     * @param context the context for reading settings and files.
     * @param clientId the id of the client.
     * @param newMetadata the state we want to upgrade to.
     * @return the upgrade from the current state to the desired state, ready to be executed.
     */
    public static ActionBatch computeUpgradeTo(final Context context, final String clientId,
            final List<WordListMetadata> newMetadata) {
        final List<WordListMetadata> currentMetadata =
                MetadataHandler.getCurrentMetadata(context, clientId);
        return compareMetadataForUpgrade(context, clientId, currentMetadata, newMetadata);
    }

    /**
     * Shows the notification that informs the user a dictionary is available.
     *
     * When this notification is clicked, the dialog for downloading the dictionary
     * over a metered connection is shown.
     */
    private static void showDictionaryAvailableNotification(final Context context,
            final String clientId, final ContentValues installCandidate) {
        final String localeString = installCandidate.getAsString(MetadataDbHelper.LOCALE_COLUMN);
        final Intent intent = new Intent();
        intent.setClass(context, DownloadOverMeteredDialog.class);
        intent.putExtra(DownloadOverMeteredDialog.CLIENT_ID_KEY, clientId);
        intent.putExtra(DownloadOverMeteredDialog.WORDLIST_TO_DOWNLOAD_KEY,
                installCandidate.getAsString(MetadataDbHelper.WORDLISTID_COLUMN));
        intent.putExtra(DownloadOverMeteredDialog.SIZE_KEY,
                installCandidate.getAsInteger(MetadataDbHelper.FILESIZE_COLUMN));
        intent.putExtra(DownloadOverMeteredDialog.LOCALE_KEY, localeString);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        final PendingIntent notificationIntent = PendingIntent.getActivity(context,
                0 /* requestCode */, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_ONE_SHOT);
        final NotificationManager notificationManager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        // None of those are expected to happen, but just in case...
        if (null == notificationIntent || null == notificationManager) return;

        final Locale locale = LocaleUtils.constructLocaleFromString(localeString);
        final String language = (null == locale ? "" : locale.getDisplayLanguage());
        final String titleFormat = context.getString(R.string.dict_available_notification_title);
        final String notificationTitle = String.format(titleFormat, language);
        final Notification notification = new Notification.Builder(context)
                .setAutoCancel(true)
                .setContentIntent(notificationIntent)
                .setContentTitle(notificationTitle)
                .setContentText(context.getString(R.string.dict_available_notification_description))
                .setTicker(notificationTitle)
                .setOngoing(false)
                .setOnlyAlertOnce(true)
                .setSmallIcon(R.drawable.ic_notify_dictionary)
                .getNotification();
        notificationManager.notify(DICT_AVAILABLE_NOTIFICATION_ID, notification);
    }

    /**
     * Installs a word list if it has never been requested.
     *
     * This is called when a word list is requested, and is available but not installed. It checks
     * the conditions for auto-installation: if the dictionary is a main dictionary for this
     * language, and it has never been opted out through the dictionary interface, then we start
     * installing it. For the user who enables a language and uses it for the first time, the
     * dictionary should magically start being used a short time after they start typing.
     * The mayPrompt argument indicates whether we should prompt the user for a decision to
     * download or not, in case we decide we are in the case where we should download - this
     * roughly happens when the current connectivity is 3G. See
     * DictionaryProvider#getDictionaryWordListsForContentUri for details.
     */
    // As opposed to many other methods, this method does not need the version of the word
    // list because it may only install the latest version we know about for this specific
    // word list ID / client ID combination.
    public static void installIfNeverRequested(final Context context, final String clientId,
            final String wordlistId, final boolean mayPrompt) {
        final String[] idArray = wordlistId.split(DictionaryProvider.ID_CATEGORY_SEPARATOR);
        // If we have a new-format dictionary id (category:manual_id), then use the
        // specified category. Otherwise, it is a main dictionary, so force the
        // MAIN category upon it.
        final String category = 2 == idArray.length ? idArray[0] : MAIN_DICTIONARY_CATEGORY;
        if (!MAIN_DICTIONARY_CATEGORY.equals(category)) {
            // Not a main dictionary. We only auto-install main dictionaries, so we can return now.
            return;
        }
        if (CommonPreferences.getCommonPreferences(context).contains(wordlistId)) {
            // If some kind of settings has been done in the past for this specific id, then
            // this is not a candidate for auto-install. Because it already is either true,
            // in which case it may be installed or downloading or whatever, and we don't
            // need to care about it because it's already handled or being handled, or it's false
            // in which case it means the user explicitely turned it off and don't want to have
            // it installed. So we quit right away.
            return;
        }

        final SQLiteDatabase db = MetadataDbHelper.getDb(context, clientId);
        final ContentValues installCandidate =
                MetadataDbHelper.getContentValuesOfLatestAvailableWordlistById(db, wordlistId);
        if (MetadataDbHelper.STATUS_AVAILABLE
                != installCandidate.getAsInteger(MetadataDbHelper.STATUS_COLUMN)) {
            // If it's not "AVAILABLE", we want to stop now. Because candidates for auto-install
            // are lists that we know are available, but we also know have never been installed.
            // It does obviously not concern already installed lists, or downloading lists,
            // or those that have been disabled, flagged as deleting... So anything else than
            // AVAILABLE means we don't auto-install.
            return;
        }

        if (mayPrompt
                && DOWNLOAD_OVER_METERED_SETTING_UNKNOWN
                        == getDownloadOverMeteredSetting(context)) {
            final ConnectivityManager cm =
                    (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (ConnectivityManagerCompatUtils.isActiveNetworkMetered(cm)) {
                showDictionaryAvailableNotification(context, clientId, installCandidate);
                return;
            }
        }

        // We decided against prompting the user for a decision. This may be because we were
        // explicitly asked not to, or because we are currently on wi-fi anyway, or because we
        // already know the answer to the question. We'll enqueue a request ; StartDownloadAction
        // knows to use the correct type of network according to the current settings.

        // Also note that once it's auto-installed, a word list will be marked as INSTALLED. It will
        // thus receive automatic updates if there are any, which is what we want. If the user does
        // not want this word list, they will have to go to the settings and change them, which will
        // change the shared preferences. So there is no way for a word list that has been
        // auto-installed once to get auto-installed again, and that's what we want.
        final ActionBatch actions = new ActionBatch();
        actions.add(new ActionBatch.StartDownloadAction(clientId,
                WordListMetadata.createFromContentValues(installCandidate), false));
        final String localeString = installCandidate.getAsString(MetadataDbHelper.LOCALE_COLUMN);
        // We are in a content provider: we can't do any UI at all. We have to defer the displaying
        // itself to the service. Also, we only display this when the user does not have a
        // dictionary for this language already: we know that from the mayPrompt argument.
        if (mayPrompt) {
            final Intent intent = new Intent();
            intent.setClass(context, DictionaryService.class);
            intent.setAction(DictionaryService.SHOW_DOWNLOAD_TOAST_INTENT_ACTION);
            intent.putExtra(DictionaryService.LOCALE_INTENT_ARGUMENT, localeString);
            context.startService(intent);
        }
        actions.execute(context, new LogProblemReporter(TAG));
    }

    /**
     * Marks the word list with the passed id as used.
     *
     * This will download/install the list as required. The action will see that the destination
     * word list is a valid list, and take appropriate action - in this case, mark it as used.
     * @see ActionBatch.Action#execute
     *
     * @param context the context for using action batches.
     * @param clientId the id of the client.
     * @param wordlistId the id of the word list to mark as installed.
     * @param version the version of the word list to mark as installed.
     * @param status the current status of the word list.
     * @param allowDownloadOnMeteredData whether to download even on metered data connection
     */
    // The version argument is not used yet, because we don't need it to retrieve the information
    // we need. However, the pair (id, version) being the primary key to a word list in the database
    // it feels better for consistency to pass it, and some methods retrieving information about a
    // word list need it so we may need it in the future.
    public static void markAsUsed(final Context context, final String clientId,
            final String wordlistId, final int version,
            final int status, final boolean allowDownloadOnMeteredData) {
        final List<WordListMetadata> currentMetadata =
                MetadataHandler.getCurrentMetadata(context, clientId);
        WordListMetadata wordList = MetadataHandler.findWordListById(currentMetadata, wordlistId);
        if (null == wordList) return;
        final ActionBatch actions = new ActionBatch();
        if (MetadataDbHelper.STATUS_DISABLED == status
                || MetadataDbHelper.STATUS_DELETING == status) {
            actions.add(new ActionBatch.EnableAction(clientId, wordList));
        } else if (MetadataDbHelper.STATUS_AVAILABLE == status) {
            actions.add(new ActionBatch.StartDownloadAction(clientId, wordList,
                    allowDownloadOnMeteredData));
        } else {
            Log.e(TAG, "Unexpected state of the word list for markAsUsed : " + status);
        }
        actions.execute(context, new LogProblemReporter(TAG));
        signalNewDictionaryState(context);
    }

    /**
     * Marks the word list with the passed id as unused.
     *
     * This leaves the file on the disk for ulterior use. The action will see that the destination
     * word list is null, and take appropriate action - in this case, mark it as unused.
     * @see ActionBatch.Action#execute
     *
     * @param context the context for using action batches.
     * @param clientId the id of the client.
     * @param wordlistId the id of the word list to mark as installed.
     * @param version the version of the word list to mark as installed.
     * @param status the current status of the word list.
     */
    // The version and status arguments are not used yet, but this method matches its interface to
    // markAsUsed for consistency.
    public static void markAsUnused(final Context context, final String clientId,
            final String wordlistId, final int version, final int status) {
        final List<WordListMetadata> currentMetadata =
                MetadataHandler.getCurrentMetadata(context, clientId);
        final WordListMetadata wordList =
                MetadataHandler.findWordListById(currentMetadata, wordlistId);
        if (null == wordList) return;
        final ActionBatch actions = new ActionBatch();
        actions.add(new ActionBatch.DisableAction(clientId, wordList));
        actions.execute(context, new LogProblemReporter(TAG));
        signalNewDictionaryState(context);
    }

    /**
     * Marks the word list with the passed id as deleting.
     *
     * This basically means that on the next chance there is (right away if Android Keyboard
     * happens to be up, or the next time it gets up otherwise) the dictionary pack will
     * supply an empty dictionary to it that will replace whatever dictionary is installed.
     * This allows to release the space taken by a dictionary (except for the few bytes the
     * empty dictionary takes up), and override a built-in default dictionary so that we
     * can fake delete a built-in dictionary.
     *
     * @param context the context to open the database on.
     * @param clientId the id of the client.
     * @param wordlistId the id of the word list to mark as deleted.
     * @param version the version of the word list to mark as deleted.
     * @param status the current status of the word list.
     */
    public static void markAsDeleting(final Context context, final String clientId,
            final String wordlistId, final int version, final int status) {
        final List<WordListMetadata> currentMetadata =
                MetadataHandler.getCurrentMetadata(context, clientId);
        final WordListMetadata wordList =
                MetadataHandler.findWordListById(currentMetadata, wordlistId);
        if (null == wordList) return;
        final ActionBatch actions = new ActionBatch();
        actions.add(new ActionBatch.DisableAction(clientId, wordList));
        actions.add(new ActionBatch.StartDeleteAction(clientId, wordList));
        actions.execute(context, new LogProblemReporter(TAG));
        signalNewDictionaryState(context);
    }

    /**
     * Marks the word list with the passed id as actually deleted.
     *
     * This reverts to available status or deletes the row as appropriate.
     *
     * @param context the context to open the database on.
     * @param clientId the id of the client.
     * @param wordlistId the id of the word list to mark as deleted.
     * @param version the version of the word list to mark as deleted.
     * @param status the current status of the word list.
     */
    public static void markAsDeleted(final Context context, final String clientId,
            final String wordlistId, final int version, final int status) {
        final List<WordListMetadata> currentMetadata =
                MetadataHandler.getCurrentMetadata(context, clientId);
        final WordListMetadata wordList =
                MetadataHandler.findWordListById(currentMetadata, wordlistId);
        if (null == wordList) return;
        final ActionBatch actions = new ActionBatch();
        actions.add(new ActionBatch.FinishDeleteAction(clientId, wordList));
        actions.execute(context, new LogProblemReporter(TAG));
        signalNewDictionaryState(context);
    }

    /**
     * Marks the word list with the passed id as broken.
     *
     * This effectively deletes the entry from the metadata. It doesn't prevent the same
     * word list to be downloaded again at a later time if the same or a new version is
     * available the next time we download the metadata.
     *
     * @param context the context to open the database on.
     * @param clientId the id of the client.
     * @param wordlistId the id of the word list to mark as broken.
     * @param version the version of the word list to mark as deleted.
     */
    public static void markAsBroken(final Context context, final String clientId,
            final String wordlistId, final int version) {
        // TODO: do this on another thread to avoid blocking the UI.
        MetadataDbHelper.deleteEntry(MetadataDbHelper.getDb(context, clientId),
                wordlistId, version);
    }
}
