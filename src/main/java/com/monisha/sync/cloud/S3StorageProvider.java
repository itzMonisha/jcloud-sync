package com.monisha.sync.cloud;

import com.monisha.sync.config.SyncConfig;
import com.monisha.sync.hash.FileHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * AWS S3 implementation of CloudStorageProvider using AWS SDK for Java v2.
 * Supports custom endpoint URLs (MinIO, LocalStack) and standard AWS S3 endpoints.
 */
public class S3StorageProvider implements CloudStorageProvider {

    private static final Logger log = LoggerFactory.getLogger(S3StorageProvider.class);
    public static final String META_SHA256 = "sha256";

    private final String bucket;
    private final S3Client s3Client;
    private final boolean ownsClient;

    public S3StorageProvider(SyncConfig config) {
        this(
            config.getS3Bucket(),
            Region.of(config.getS3Region() != null && !config.getS3Region().isBlank() ? config.getS3Region() : "us-east-1"),
            config.getS3EndpointOverride() != null && !config.getS3EndpointOverride().isBlank()
                ? URI.create(config.getS3EndpointOverride())
                : null
        );
    }

    public S3StorageProvider(String bucket, Region region, URI endpointOverride) {
        this.bucket = Objects.requireNonNull(bucket, "bucket must not be null");
        S3ClientBuilder builder = S3Client.builder().region(region);

        if (endpointOverride != null) {
            log.info("Configuring S3StorageProvider with custom endpoint [{}] (LocalStack/MinIO mode)", endpointOverride);
            builder.endpointOverride(endpointOverride);
            builder.serviceConfiguration(S3Configuration.builder()
                .pathStyleAccessEnabled(true)
                .build());
            // Supply static dev credentials if none present in environment for local endpoints
            builder.credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create("test", "test")
            ));
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.create());
        }

        this.s3Client = builder.build();
        this.ownsClient = true;
    }

    public S3StorageProvider(String bucket, S3Client s3Client) {
        this.bucket = Objects.requireNonNull(bucket, "bucket must not be null");
        this.s3Client = Objects.requireNonNull(s3Client, "s3Client must not be null");
        this.ownsClient = false;
    }

    @Override
    public RemoteObjectMetadata uploadFile(Path localFile, String remoteKey) throws IOException {
        Objects.requireNonNull(localFile, "localFile must not be null");
        Objects.requireNonNull(remoteKey, "remoteKey must not be null");

        String normalizedKey = normalizeKey(remoteKey);
        String sha256 = FileHasher.computeSha256(localFile);
        long fileSize = Files.size(localFile);

        Map<String, String> userMetadata = new HashMap<>();
        userMetadata.put(META_SHA256, sha256);

        PutObjectRequest request = PutObjectRequest.builder()
            .bucket(bucket)
            .key(normalizedKey)
            .metadata(userMetadata)
            .build();

        try {
            PutObjectResponse response = s3Client.putObject(request, RequestBody.fromFile(localFile));
            String cleanEtag = cleanEtag(response.eTag());
            log.debug("Uploaded [{}] to s3://{}/{} with ETag [{}] and SHA-256 [{}]", localFile, bucket, normalizedKey, cleanEtag, sha256);
            return new RemoteObjectMetadata(normalizedKey, cleanEtag, sha256, Instant.now(), fileSize, userMetadata);
        } catch (Exception e) {
            throw new IOException("Failed to upload file [" + localFile + "] to s3://" + bucket + "/" + normalizedKey, e);
        }
    }

    @Override
    public void downloadFile(String remoteKey, Path localDestination) throws IOException {
        Objects.requireNonNull(remoteKey, "remoteKey must not be null");
        Objects.requireNonNull(localDestination, "localDestination must not be null");

        String normalizedKey = normalizeKey(remoteKey);
        Path parent = localDestination.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }

        GetObjectRequest request = GetObjectRequest.builder()
            .bucket(bucket)
            .key(normalizedKey)
            .build();

        try {
            s3Client.getObject(request, ResponseTransformer.toFile(localDestination));
            log.debug("Downloaded s3://{}/{} to [{}]", bucket, normalizedKey, localDestination);
        } catch (Exception e) {
            throw new IOException("Failed to download s3://" + bucket + "/" + normalizedKey + " to [" + localDestination + "]", e);
        }
    }

    @Override
    public void deleteFile(String remoteKey) throws IOException {
        Objects.requireNonNull(remoteKey, "remoteKey must not be null");
        String normalizedKey = normalizeKey(remoteKey);

        DeleteObjectRequest request = DeleteObjectRequest.builder()
            .bucket(bucket)
            .key(normalizedKey)
            .build();

        try {
            s3Client.deleteObject(request);
            log.debug("Deleted s3://{}/{}", bucket, normalizedKey);
        } catch (Exception e) {
            throw new IOException("Failed to delete s3://" + bucket + "/" + normalizedKey, e);
        }
    }

    @Override
    public List<RemoteObjectMetadata> listRemoteObjects() throws IOException {
        return listRemoteObjects(null);
    }

    @Override
    public List<RemoteObjectMetadata> listRemoteObjects(String prefix) throws IOException {
        List<RemoteObjectMetadata> objects = new ArrayList<>();
        ListObjectsV2Request.Builder builder = ListObjectsV2Request.builder().bucket(bucket);
        if (prefix != null && !prefix.isBlank()) {
            builder.prefix(normalizeKey(prefix));
        }

        try {
            for (ListObjectsV2Response page : s3Client.listObjectsV2Paginator(builder.build())) {
                for (S3Object s3Object : page.contents()) {
                    objects.add(new RemoteObjectMetadata(
                        s3Object.key(),
                        cleanEtag(s3Object.eTag()),
                        null, // User metadata SHA-256 can be resolved in getObjectMetadata
                        s3Object.lastModified(),
                        s3Object.size()
                    ));
                }
            }
            return objects;
        } catch (Exception e) {
            throw new IOException("Failed to list objects in s3://" + bucket, e);
        }
    }

    @Override
    public Optional<RemoteObjectMetadata> getObjectMetadata(String remoteKey) throws IOException {
        Objects.requireNonNull(remoteKey, "remoteKey must not be null");
        String normalizedKey = normalizeKey(remoteKey);

        HeadObjectRequest request = HeadObjectRequest.builder()
            .bucket(bucket)
            .key(normalizedKey)
            .build();

        try {
            HeadObjectResponse response = s3Client.headObject(request);
            String sha256 = response.metadata().get(META_SHA256);
            return Optional.of(new RemoteObjectMetadata(
                normalizedKey,
                cleanEtag(response.eTag()),
                sha256,
                response.lastModified(),
                response.contentLength(),
                response.metadata()
            ));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (Exception e) {
            // Handle 404 in some S3 mock engines returning generic AWS exceptions
            if (e.getMessage() != null && e.getMessage().contains("Status Code: 404")) {
                return Optional.empty();
            }
            throw new IOException("Failed to get metadata for s3://" + bucket + "/" + normalizedKey, e);
        }
    }

    private String normalizeKey(String key) {
        return key.replace('\\', '/').replaceAll("^/+", "");
    }

    private String cleanEtag(String etag) {
        if (etag == null) return null;
        return etag.replaceAll("^\"|\"$", "");
    }

    public String getBucket() {
        return bucket;
    }

    public S3Client getS3Client() {
        return s3Client;
    }

    @Override
    public void close() {
        if (ownsClient && s3Client != null) {
            s3Client.close();
        }
    }
}
