package com.fonline.newdawn.storage;

import com.fonline.newdawn.common.ApiException;
import com.fonline.newdawn.config.AppProperties;
import com.fonline.newdawn.update.LegacyCrc32;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@Service
public class StorageService {
    private final S3Client client;
    private final S3Presigner presigner;
    private final AppProperties properties;

    public StorageService(S3Client client, S3Presigner presigner, AppProperties properties) {
        this.client = client;
        this.presigner = presigner;
        this.properties = properties;
    }

    public void ensureBucket() {
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(properties.storage().bucket()).build());
        } catch (NoSuchBucketException exception) {
            client.createBucket(CreateBucketRequest.builder().bucket(properties.storage().bucket()).build());
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) {
                client.createBucket(CreateBucketRequest.builder().bucket(properties.storage().bucket()).build());
            } else {
                throw exception;
            }
        }
    }

    public UploadTicket presignUpload(String objectKey, String contentType, long sizeBytes, String sha256Hex) {
        PutObjectRequest objectRequest = PutObjectRequest.builder()
                .bucket(properties.storage().bucket()).key(objectKey).contentType(contentType).contentLength(sizeBytes)
                .checksumSHA256(Base64.getEncoder().encodeToString(HexFormat.of().parseHex(sha256Hex))).build();
        var request = PutObjectPresignRequest.builder()
                .signatureDuration(properties.storage().uploadUrlTtl()).putObjectRequest(objectRequest).build();
        var signed = presigner.presignPutObject(request);
        return new UploadTicket(URI.create(signed.url().toString()), signed.signedHeaders());
    }

    public UploadTicket presignUpload(String objectKey, String contentType, long sizeBytes) {
        PutObjectRequest objectRequest = PutObjectRequest.builder()
                .bucket(properties.storage().bucket()).key(objectKey).contentType(contentType).contentLength(sizeBytes).build();
        var request = PutObjectPresignRequest.builder()
                .signatureDuration(properties.storage().uploadUrlTtl()).putObjectRequest(objectRequest).build();
        var signed = presigner.presignPutObject(request);
        return new UploadTicket(URI.create(signed.url().toString()), signed.signedHeaders());
    }

    public URI presignDownload(String objectKey, String downloadFileName) {
        GetObjectRequest objectRequest = GetObjectRequest.builder()
                .bucket(properties.storage().bucket()).key(objectKey)
                .responseContentDisposition("attachment; filename=\"" + asciiFileName(downloadFileName) + "\"")
                .build();
        var request = GetObjectPresignRequest.builder()
                .signatureDuration(properties.storage().downloadUrlTtl()).getObjectRequest(objectRequest).build();
        return URI.create(presigner.presignGetObject(request).url().toString());
    }

    public URI presignInline(String objectKey) {
        GetObjectRequest objectRequest = GetObjectRequest.builder()
                .bucket(properties.storage().bucket()).key(objectKey).build();
        var request = GetObjectPresignRequest.builder()
                .signatureDuration(properties.storage().downloadUrlTtl()).getObjectRequest(objectRequest).build();
        return URI.create(presigner.presignGetObject(request).url().toString());
    }

    public StoredObject head(String objectKey) {
        try {
            HeadObjectResponse object = client.headObject(HeadObjectRequest.builder()
                    .bucket(properties.storage().bucket()).key(objectKey).build());
            return new StoredObject(object.contentLength(), object.contentType(), object.eTag());
        } catch (NoSuchKeyException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "UPLOAD_NOT_FOUND", "The uploaded object does not exist in storage.");
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) {
                throw new ApiException(HttpStatus.CONFLICT, "UPLOAD_NOT_FOUND", "The uploaded object does not exist in storage.");
            }
            throw exception;
        }
    }

    public ObjectDigests calculateDigests(String objectKey) {
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(properties.storage().bucket()).key(objectKey).build();
        try (ResponseInputStream<GetObjectResponse> input = client.getObject(request)) {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            LegacyCrc32 legacyCrc32 = new LegacyCrc32();
            byte[] buffer = new byte[1024 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                sha256.update(buffer, 0, count);
                legacyCrc32.update(buffer, 0, count);
            }
            return new ObjectDigests(HexFormat.of().formatHex(sha256.digest()), legacyCrc32.value());
        } catch (NoSuchKeyException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "UPLOAD_NOT_FOUND", "The uploaded object does not exist in storage.");
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) {
                throw new ApiException(HttpStatus.CONFLICT, "UPLOAD_NOT_FOUND", "The uploaded object does not exist in storage.");
            }
            throw exception;
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "STORAGE_READ_FAILED", "The uploaded object could not be verified.");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable in this Java runtime.", exception);
        }
    }

    public void writeTo(String objectKey, OutputStream output) throws IOException {
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(properties.storage().bucket()).key(objectKey).build();
        try (ResponseInputStream<GetObjectResponse> input = client.getObject(request)) {
            input.transferTo(output);
        } catch (NoSuchKeyException exception) {
            throw new ApiException(HttpStatus.NOT_FOUND, "UPDATE_FILE_NOT_FOUND",
                    "The update object does not exist in storage.");
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) {
                throw new ApiException(HttpStatus.NOT_FOUND, "UPDATE_FILE_NOT_FOUND",
                        "The update object does not exist in storage.");
            }
            throw exception;
        }
    }

    public void writeWikiObjectTo(String objectKey, OutputStream output) throws IOException {
        validateWikiKey(objectKey);
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(properties.storage().bucket()).key(objectKey).build();
        try (ResponseInputStream<GetObjectResponse> input = client.getObject(request)) {
            input.transferTo(output);
        } catch (NoSuchKeyException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "WIKI_ASSET_NOT_FOUND",
                    "A wiki asset included in the backup is missing from storage.");
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) {
                throw new ApiException(HttpStatus.CONFLICT, "WIKI_ASSET_NOT_FOUND",
                        "A wiki asset included in the backup is missing from storage.");
            }
            throw exception;
        }
    }

    public boolean wikiObjectExists(String objectKey) {
        validateWikiKey(objectKey);
        try {
            client.headObject(HeadObjectRequest.builder()
                    .bucket(properties.storage().bucket()).key(objectKey).build());
            return true;
        } catch (NoSuchKeyException exception) {
            return false;
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) return false;
            throw exception;
        }
    }

    public void putWikiObject(String objectKey, String contentType, long sizeBytes, InputStream input) {
        validateWikiKey(objectKey);
        try {
            client.putObject(PutObjectRequest.builder()
                            .bucket(properties.storage().bucket()).key(objectKey)
                            .contentType(contentType).contentLength(sizeBytes).build(),
                    RequestBody.fromInputStream(input, sizeBytes));
        } catch (S3Exception exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "STORAGE_IMPORT_FAILED",
                    "A wiki backup asset could not be restored to storage.");
        }
    }

    public void deleteObject(String objectKey) {
        validateDistributionKey(objectKey, false);
        deleteExactObject(objectKey);
    }

    public void deleteWikiObject(String objectKey) {
        validateWikiKey(objectKey);
        deleteExactObject(objectKey);
    }

    private void validateWikiKey(String objectKey) {
        if (objectKey == null || !objectKey.startsWith("wiki/") || objectKey.contains("..")
                || objectKey.startsWith("/") || objectKey.endsWith("/")) {
            throw new IllegalArgumentException("Refusing to use an unsafe wiki storage key.");
        }
    }

    private void deleteExactObject(String objectKey) {
        try {
            client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(properties.storage().bucket()).key(objectKey).build());
        } catch (S3Exception exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "STORAGE_DELETE_FAILED",
                    "The distribution object could not be deleted from storage.");
        }
    }

    public int deletePrefix(String prefix) {
        validateDistributionKey(prefix, true);
        try {
            List<ObjectIdentifier> objects = new ArrayList<>();
            client.listObjectsV2Paginator(ListObjectsV2Request.builder()
                            .bucket(properties.storage().bucket()).prefix(prefix).build())
                    .contents().forEach(object -> objects.add(ObjectIdentifier.builder().key(object.key()).build()));
            for (int offset = 0; offset < objects.size(); offset += 1000) {
                List<ObjectIdentifier> batch = objects.subList(offset, Math.min(offset + 1000, objects.size()));
                var response = client.deleteObjects(DeleteObjectsRequest.builder()
                        .bucket(properties.storage().bucket())
                        .delete(Delete.builder().objects(batch).quiet(true).build())
                        .build());
                if (!response.errors().isEmpty()) {
                    throw new ApiException(HttpStatus.BAD_GATEWAY, "STORAGE_DELETE_FAILED",
                            "One or more update objects could not be deleted from storage.");
                }
            }
            return objects.size();
        } catch (ApiException exception) {
            throw exception;
        } catch (S3Exception exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "STORAGE_DELETE_FAILED",
                    "The update objects could not be deleted from storage.");
        }
    }

    private void validateDistributionKey(String value, boolean prefix) {
        boolean allowedRoot = value != null && (value.startsWith("updates/") || value.startsWith("releases/"));
        if (!allowedRoot || value.contains("..") || value.startsWith("/") || (prefix && !value.endsWith("/"))) {
            throw new IllegalArgumentException("Refusing to delete an unsafe storage key or prefix.");
        }
    }

    public String safeFileName(String value) {
        String normalized = value == null ? "file" : value.replaceAll("[^a-zA-Z0-9._-]", "_");
        return normalized.isBlank() ? "file" : normalized.substring(0, Math.min(normalized.length(), 180));
    }

    private String asciiFileName(String value) {
        return safeFileName(value).replace("\"", "");
    }

    public record UploadTicket(URI url, Map<String, List<String>> headers) {}
    public record StoredObject(long sizeBytes, String contentType, String etag) {}
    public record ObjectDigests(String sha256, int legacyCrc32) {}
}
