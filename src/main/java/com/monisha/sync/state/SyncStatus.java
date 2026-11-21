package com.monisha.sync.state;

/**
 * Synchronization status of a local file with the remote cloud storage.
 */
public enum SyncStatus {
    SYNCED,
    PENDING,
    CONFLICT
}
