package com.vaultsens.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Synchronous Java client for the VaultSens API. Responses retain the API's JSON envelope. */
public final class VaultSensClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String API = "/api/v1";
    private final String baseUrl;
    private final HttpClient http;
    private String apiKey;
    private String apiSecret;
    private Duration timeout = Duration.ofSeconds(30);
    private int retries = 2;

    public VaultSensClient(String baseUrl, String apiKey, String apiSecret) {
        this(baseUrl, apiKey, apiSecret, HttpClient.newHttpClient());
    }

    public VaultSensClient(String baseUrl, String apiKey, String apiSecret, HttpClient http) {
        String value = Objects.requireNonNull(baseUrl, "baseUrl").replaceAll("/+$", "");
        URI uri = URI.create(value);
        if (!List.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("baseUrl must be an HTTP(S) origin or base path");
        }
        this.baseUrl = value;
        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
        this.http = Objects.requireNonNull(http, "http");
    }

    public void setAuth(String apiKey, String apiSecret) {
        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
    }

    public void setTimeout(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        this.timeout = timeout;
    }

    /** Number of retries for GET requests after 429, 5xx, timeout, or transport failure. */
    public void setRetries(int retries) {
        if (retries < 0) throw new IllegalArgumentException("retries must be nonnegative");
        this.retries = retries;
    }

    public JsonNode uploadFile(Path file) { return uploadFile(file, new UploadOptions()); }

    public JsonNode uploadFile(Path file, UploadOptions options) {
        Multipart form = new Multipart();
        form.file("file", file, options);
        form.fields(options, true);
        return request("POST", API + "/files/upload", form.body(), form.contentType());
    }

    public JsonNode uploadFiles(List<Path> files) { return uploadFiles(files, new UploadOptions()); }

    /** Upload up to 20 files using each path's filename and detected MIME type. */
    public JsonNode uploadFiles(List<Path> files, UploadOptions options) {
        if (files == null || files.isEmpty() || files.size() > 20) {
            throw new IllegalArgumentException("files must contain between 1 and 20 paths");
        }
        Multipart form = new Multipart();
        for (Path file : files) form.file("files", file, new UploadOptions());
        form.fields(options, true);
        return request("POST", API + "/files/upload", form.body(), form.contentType());
    }

    /** Upload files with an optional filename and MIME type for each item. */
    public JsonNode uploadFilesWithMetadata(List<FileUpload> files, UploadOptions options) {
        if (files == null || files.isEmpty() || files.size() > 20) {
            throw new IllegalArgumentException("files must contain between 1 and 20 items");
        }
        Multipart form = new Multipart();
        for (FileUpload file : files) {
            form.file("files", file.path(), new UploadOptions()
                    .filename(file.filename()).mimeType(file.mimeType()));
        }
        form.fields(options, true);
        return request("POST", API + "/files/upload", form.body(), form.contentType());
    }

    public JsonNode listFiles() { return request("GET", API + "/files", null, null); }

    /** Pass "root" for files in the root folder. */
    public JsonNode listFiles(String folderId) {
        return request("GET", API + "/files?folderId=" + encode(folderId), null, null);
    }

    public JsonNode getFileMetadata(String fileId) {
        return request("GET", API + "/files/metadata/" + segment(fileId), null, null);
    }

    public JsonNode updateFile(String fileId, Path file) {
        return updateFile(fileId, file, new UploadOptions());
    }

    public JsonNode updateFile(String fileId, Path file, UploadOptions options) {
        Multipart form = new Multipart();
        form.file("file", file, options);
        form.fields(options, false);
        return request("PUT", API + "/files/" + segment(fileId), form.body(), form.contentType());
    }

    public JsonNode deleteFile(String fileId) {
        return request("DELETE", API + "/files/" + segment(fileId), null, null);
    }

    public JsonNode listFolders() { return request("GET", API + "/folders", null, null); }

    public JsonNode createFolder(String name) { return createFolder(name, null); }

    public JsonNode createFolder(String name, String parentId) {
        ObjectNode body = JSON.createObjectNode().put("name", Objects.requireNonNull(name, "name"));
        if (parentId != null) body.put("parentId", parentId);
        return request("POST", API + "/folders", jsonBody(body), "application/json");
    }

    public JsonNode renameFolder(String folderId, String name) {
        ObjectNode body = JSON.createObjectNode().put("name", Objects.requireNonNull(name, "name"));
        return request("PATCH", API + "/folders/" + segment(folderId), jsonBody(body), "application/json");
    }

    public JsonNode deleteFolder(String folderId) {
        return request("DELETE", API + "/folders/" + segment(folderId), null, null);
    }

    public JsonNode getMetrics() { return request("GET", API + "/metrics", null, null); }

    public String buildFileUrl(String fileId) { return buildFileUrl(fileId, Map.of()); }

    /** Build a public file URL with optional width, height, format, quality, and fit parameters. */
    public String buildFileUrl(String fileId, Map<String, ?> transforms) {
        StringBuilder url = new StringBuilder(baseUrl).append(API).append("/files/").append(segment(fileId));
        for (Map.Entry<String, ?> entry : Objects.requireNonNull(transforms, "transforms").entrySet()) {
            if (!List.of("width", "height", "format", "quality", "fit").contains(entry.getKey())) {
                throw new IllegalArgumentException("Unsupported transform: " + entry.getKey());
            }
            if (entry.getValue() != null) {
                url.append(url.indexOf("?") < 0 ? '?' : '&')
                        .append(encode(entry.getKey())).append('=').append(encode(entry.getValue().toString()));
            }
        }
        return url.toString();
    }

    private JsonNode request(String method, String path, HttpRequest.BodyPublisher body, String contentType) {
        if (apiKey == null || apiKey.isBlank() || apiSecret == null || apiSecret.isBlank()) {
            throw new VaultSensException("API key and secret are required", 401, (JsonNode) null);
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(timeout)
                .header("x-api-key", apiKey)
                .header("x-api-secret", apiSecret)
                .header("Accept", "application/json");
        if (contentType != null) builder.header("Content-Type", contentType);
        HttpRequest req = builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : body).build();
        int maxAttempts = method.equals("GET") ? retries + 1 : 1;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            try {
                HttpResponse<String> response = http.send(req, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (attempt + 1 < maxAttempts && (status == 429 || status >= 500)) {
                    delay();
                    continue;
                }
                JsonNode payload = parse(response.body());
                if (status < 200 || status >= 300) {
                    String message = payload.isObject()
                            ? payload.path("message").asText(payload.path("error").asText("HTTP " + status))
                            : "HTTP " + status;
                    throw new VaultSensException(message, status, payload);
                }
                return payload;
            } catch (HttpTimeoutException e) {
                if (attempt + 1 < maxAttempts) { delay(); continue; }
                throw new VaultSensException("Request timed out", 408, e);
            } catch (IOException e) {
                if (attempt + 1 < maxAttempts) { delay(); continue; }
                throw new VaultSensException("Request failed: " + e.getMessage(), 0, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new VaultSensException("Request interrupted", 0, e);
            }
        }
        throw new IllegalStateException("No request attempt was made");
    }

    private static JsonNode parse(String body) {
        if (body == null || body.isEmpty()) return NullNode.instance;
        try { return JSON.readTree(body); }
        catch (IOException ignored) { return TextNode.valueOf(body); }
    }

    private static HttpRequest.BodyPublisher jsonBody(JsonNode body) {
        try { return HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body)); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }

    private static String segment(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("ID is required");
        return encode(value);
    }

    private static String encode(String value) {
        return URLEncoder.encode(Objects.requireNonNull(value, "value"), StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static void delay() {
        try { Thread.sleep(400); }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VaultSensException("Request interrupted", 0, e);
        }
    }

    public static final class UploadOptions {
        private String name;
        private String compression;
        private String folderId;
        private String filename;
        private String mimeType;

        public UploadOptions name(String value) { name = value; return this; }
        public UploadOptions compression(String value) { compression = value; return this; }
        public UploadOptions folderId(String value) { folderId = value; return this; }
        public UploadOptions filename(String value) { filename = value; return this; }
        public UploadOptions mimeType(String value) { mimeType = value; return this; }
    }

    public record FileUpload(Path path, String filename, String mimeType) {
        public FileUpload {
            Objects.requireNonNull(path, "path");
        }
    }

    private static final class Multipart {
        private final String boundary = "vaultsens-" + UUID.randomUUID();
        private final List<HttpRequest.BodyPublisher> parts = new ArrayList<>();

        void file(String field, Path path, UploadOptions options) {
            Objects.requireNonNull(path, "file");
            Objects.requireNonNull(options, "options");
            if (!Files.isRegularFile(path)) throw new IllegalArgumentException("Not a regular file: " + path);
            String filename = options.filename == null ? path.getFileName().toString() : options.filename;
            String mime = options.mimeType;
            if (mime == null) {
                try { mime = Files.probeContentType(path); }
                catch (IOException ignored) { /* Use the generic binary type. */ }
            }
            if (mime == null) mime = "application/octet-stream";
            String header = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + field
                    + "\"; filename=\"" + quoted(filename) + "\"\r\nContent-Type: " + safeHeader(mime) + "\r\n\r\n";
            parts.add(bytes(header));
            try { parts.add(HttpRequest.BodyPublishers.ofFile(path)); }
            catch (IOException e) { throw new UncheckedIOException(e); }
            parts.add(bytes("\r\n"));
        }

        void fields(UploadOptions options, boolean includeFolder) {
            Objects.requireNonNull(options, "options");
            field("name", options.name);
            field("compression", options.compression);
            if (includeFolder) field("folderId", options.folderId);
        }

        void field(String name, String value) {
            if (value != null) {
                parts.add(bytes("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name
                        + "\"\r\n\r\n" + value + "\r\n"));
            }
        }

        HttpRequest.BodyPublisher body() {
            parts.add(bytes("--" + boundary + "--\r\n"));
            return HttpRequest.BodyPublishers.concat(parts.toArray(HttpRequest.BodyPublisher[]::new));
        }

        String contentType() { return "multipart/form-data; boundary=" + boundary; }

        private static HttpRequest.BodyPublisher bytes(String text) {
            return HttpRequest.BodyPublishers.ofByteArray(text.getBytes(StandardCharsets.UTF_8));
        }

        private static String quoted(String value) {
            return safeHeader(value).replace("\\", "\\\\").replace("\"", "\\\"");
        }

        private static String safeHeader(String value) {
            if (value.contains("\r") || value.contains("\n")) {
                throw new IllegalArgumentException("Multipart header contains a newline");
            }
            return value;
        }
    }
}
