package com.monisha.sync.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Result summary of a sync reconciliation pass, capturing categorized file lists and counts.
 */
public record SyncResult(
    List<String> uploaded,
    List<String> downloaded,
    List<String> skipped,
    List<String> conflicts,
    List<String> deletedLocal,
    List<String> deletedRemote,
    List<String> errors
) {
    public SyncResult {
        uploaded = uploaded != null ? List.copyOf(uploaded) : Collections.emptyList();
        downloaded = downloaded != null ? List.copyOf(downloaded) : Collections.emptyList();
        skipped = skipped != null ? List.copyOf(skipped) : Collections.emptyList();
        conflicts = conflicts != null ? List.copyOf(conflicts) : Collections.emptyList();
        deletedLocal = deletedLocal != null ? List.copyOf(deletedLocal) : Collections.emptyList();
        deletedRemote = deletedRemote != null ? List.copyOf(deletedRemote) : Collections.emptyList();
        errors = errors != null ? List.copyOf(errors) : Collections.emptyList();
    }

    public SyncResult(int uploadedCount,
                      int downloadedCount,
                      int conflictsResolvedCount,
                      int deletedLocalCount,
                      int deletedRemoteCount,
                      List<String> conflicts,
                      List<String> errors) {
        this(
            makeMockList(uploadedCount),
            makeMockList(downloadedCount),
            Collections.emptyList(),
            conflicts != null ? conflicts : Collections.emptyList(),
            makeMockList(deletedLocalCount),
            makeMockList(deletedRemoteCount),
            errors != null ? errors : Collections.emptyList()
        );
    }

    private static List<String> makeMockList(int count) {
        if (count <= 0) return Collections.emptyList();
        List<String> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add("item-" + i);
        }
        return list;
    }

    public static SyncResult empty() {
        return new SyncResult(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    public int uploadedCount() {
        return uploaded.size();
    }

    public int downloadedCount() {
        return downloaded.size();
    }

    public int skippedCount() {
        return skipped.size();
    }

    public int conflictsResolvedCount() {
        return conflicts.size();
    }

    public int deletedLocalCount() {
        return deletedLocal.size();
    }

    public int deletedRemoteCount() {
        return deletedRemote.size();
    }

    public int totalProcessed() {
        return uploaded.size() + downloaded.size() + skipped.size() + conflicts.size() + deletedLocal.size() + deletedRemote.size();
    }

    public boolean isSuccessful() {
        return errors.isEmpty();
    }
}
