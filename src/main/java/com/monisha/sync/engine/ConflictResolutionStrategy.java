package com.monisha.sync.engine;

/**
 * Strategy for resolving bidirectional conflicts when both local and remote copies have changed.
 */
public enum ConflictResolutionStrategy {
    /**
     * Local version overwrites remote cloud object.
     */
    LOCAL_WINS,

    /**
     * Remote cloud object overwrites local file.
     */
    REMOTE_WINS,

    /**
     * Retains both versions: copies local version to a .conflict file and downloads remote version.
     */
    KEEP_BOTH
}
