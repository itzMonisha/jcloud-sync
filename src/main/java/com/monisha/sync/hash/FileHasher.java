package com.monisha.sync.hash;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Utility for computing cryptographic SHA-256 fingerprints of files and detecting content changes.
 */
public final class FileHasher {

    private static final String SHA_256 = "SHA-256";
    private static final int BUFFER_SIZE = 65536; // 64 KB stream buffer

    private FileHasher() {}

    /**
     * Creates a new SHA-256 MessageDigest instance.
     */
    public static MessageDigest createSha256Digest() {
        try {
            return MessageDigest.getInstance(SHA_256);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available in current JVM", e);
        }
    }

    /**
     * Computes the SHA-256 hash of a file as a lowercase hexadecimal string.
     *
     * @param file path to the target file
     * @return 64-character lowercase hex string
     * @throws IOException if an I/O error occurs reading the file
     */
    public static String computeSha256(Path file) throws IOException {
        Objects.requireNonNull(file, "file must not be null");
        MessageDigest digest = createSha256Digest();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * Computes the SHA-256 hash of byte array data.
     *
     * @param data bytes to hash
     * @return 64-character lowercase hex string
     */
    public static String computeSha256(byte[] data) {
        Objects.requireNonNull(data, "data must not be null");
        MessageDigest digest = createSha256Digest();
        digest.update(data);
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * Computes the SHA-256 hash of a UTF-8 string.
     *
     * @param text string to hash
     * @return 64-character lowercase hex string
     */
    public static String computeSha256(String text) {
        Objects.requireNonNull(text, "text must not be null");
        return computeSha256(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Computes the complete FileFingerprint (SHA-256, last modified time, size) of a file.
     *
     * @param file target file
     * @return FileFingerprint record
     * @throws IOException if reading file attributes or contents fails
     */
    public static FileFingerprint computeFingerprint(Path file) throws IOException {
        Objects.requireNonNull(file, "file must not be null");
        long lastModified = Files.getLastModifiedTime(file).toMillis();
        long size = Files.size(file);
        String hash = computeSha256(file);
        return new FileFingerprint(hash, lastModified, size);
    }

    /**
     * Checks if the target file's content has changed compared to a previously recorded hash.
     *
     * @param file target file
     * @param previousHash previously recorded SHA-256 hash
     * @return true if content differs or previousHash is null; false if identical
     * @throws IOException if reading file fails
     */
    public static boolean hasContentChanged(Path file, String previousHash) throws IOException {
        if (previousHash == null) {
            return true;
        }
        String currentHash = computeSha256(file);
        return !previousHash.equalsIgnoreCase(currentHash);
    }

    /**
     * Checks whether content has changed between two fingerprints.
     */
    public static boolean hasContentChanged(FileFingerprint current, FileFingerprint previous) {
        if (previous == null || current == null) {
            return true;
        }
        if (current.sizeBytes() != previous.sizeBytes()) {
            return true;
        }
        return !current.sha256Hash().equalsIgnoreCase(previous.sha256Hash());
    }

    /**
     * Detects whether an event was a metadata-only touch (e.g. timestamp update)
     * where actual file bytes and hash remained identical.
     *
     * @param current current fingerprint
     * @param previous previous fingerprint
     * @return true if hash is identical but timestamp changed
     */
    public static boolean isMetadataOnlyTouch(FileFingerprint current, FileFingerprint previous) {
        if (current == null || previous == null) {
            return false;
        }
        return current.sha256Hash().equalsIgnoreCase(previous.sha256Hash())
                && current.lastModifiedEpoch() != previous.lastModifiedEpoch();
    }
}
