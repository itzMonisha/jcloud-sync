package com.monisha.sync.cloud;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Enterprise multi-cloud storage provider interface (FDE-style multi-cloud adapter pattern).
 * Defines common primitives for cloud providers (AWS S3, MinIO, LocalStack, GCP Cloud Storage, Azure Blob).
 */
public interface CloudStorageProvider extends AutoCloseable {

    /**
     * Uploads a local file to the cloud storage provider under the specified remote key.
     *
     * @param localFile path to the local file
     * @param remoteKey remote storage object key
     * @return metadata of the uploaded object
     * @throws IOException on transfer or network errors
     */
    RemoteObjectMetadata uploadFile(Path localFile, String remoteKey) throws IOException;

    /**
     * Downloads a remote object to a local destination path.
     *
     * @param remoteKey remote storage object key
     * @param localDestination path where downloaded file should be written
     * @throws IOException on transfer or network errors
     */
    void downloadFile(String remoteKey, Path localDestination) throws IOException;

    /**
     * Deletes a remote object by key.
     *
     * @param remoteKey remote storage object key
     * @throws IOException on transfer or network errors
     */
    void deleteFile(String remoteKey) throws IOException;

    /**
     * Lists all remote objects in the storage root.
     *
     * @return list of remote object metadata
     * @throws IOException on transfer or network errors
     */
    List<RemoteObjectMetadata> listRemoteObjects() throws IOException;

    /**
     * Lists remote objects matching a key prefix.
     *
     * @param prefix key prefix (e.g. folder path)
     * @return list of remote object metadata
     * @throws IOException on transfer or network errors
     */
    List<RemoteObjectMetadata> listRemoteObjects(String prefix) throws IOException;

    /**
     * Retrieves metadata for a specific remote object.
     *
     * @param remoteKey remote storage object key
     * @return Optional containing metadata if exists, or empty if not found
     * @throws IOException on transfer or network errors
     */
    Optional<RemoteObjectMetadata> getObjectMetadata(String remoteKey) throws IOException;

    /**
     * Checks if a remote object exists.
     */
    default boolean exists(String remoteKey) throws IOException {
        return getObjectMetadata(remoteKey).isPresent();
    }

    @Override
    default void close() throws Exception {}
}
