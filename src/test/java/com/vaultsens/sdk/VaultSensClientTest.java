package com.vaultsens.sdk;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VaultSensClientTest {
    private HttpServer server;
    private VaultSensClient client;
    private String method;
    private String path;
    private String body;
    private String contentType;
    private String key;
    private String secret;
    private final AtomicInteger calls = new AtomicInteger();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        client = new VaultSensClient("http://127.0.0.1:" + server.getAddress().getPort(), "key", "secret");
        client.setRetries(0);
    }

    @AfterEach
    void stop() { server.stop(0); }

    private void handle(HttpExchange exchange) throws IOException {
        calls.incrementAndGet();
        method = exchange.getRequestMethod();
        path = exchange.getRequestURI().toString();
        body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        key = exchange.getRequestHeaders().getFirst("x-api-key");
        secret = exchange.getRequestHeaders().getFirst("x-api-secret");
        int status = path.contains("error") ? 413 : path.contains("retry") && calls.get() == 1 ? 503 : 200;
        String response = status == 413
                ? "{\"message\":\"Storage limit exceeded\",\"data\":null}"
                : "{\"status\":200,\"message\":\"ok\",\"data\":{\"_id\":\"abc\"}}";
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var stream = exchange.getResponseBody()) { stream.write(bytes); }
    }

    @Test
    void sendsCredentialsAndEncodesPaths() {
        assertEquals("abc", client.listFiles("root folder").path("data").path("_id").asText());
        assertEquals("GET", method);
        assertEquals("/api/v1/files?folderId=root%20folder", path);
        assertEquals("key", key);
        assertEquals("secret", secret);
        client.renameFolder("a/b", "New name");
        assertEquals("PATCH", method);
        assertEquals("/api/v1/folders/a%2Fb", path);
        assertEquals("application/json", contentType);
        assertTrue(body.contains("\"name\":\"New name\""));
    }

    @Test
    void uploadsFilesWithMultipartFields() throws IOException {
        Path file = Files.createTempFile("vaultsens-test", ".png");
        try {
            Files.writeString(file, "png-data");
            client.uploadFile(file, new VaultSensClient.UploadOptions()
                    .filename("photo.png").mimeType("image/png")
                    .name("hero").compression("low").folderId("folder-1"));
            assertEquals("POST", method);
            assertEquals("/api/v1/files/upload", path);
            assertTrue(contentType.startsWith("multipart/form-data; boundary="));
            assertTrue(body.contains("filename=\"photo.png\""));
            assertTrue(body.contains("Content-Type: image/png"));
            assertTrue(body.contains("png-data"));
            assertTrue(body.contains("name=\"folderId\"\r\n\r\nfolder-1"));
            client.uploadFiles(List.of(file, file));
            assertEquals(2, body.split("name=\"files\"", -1).length - 1);
            client.uploadFilesWithMetadata(List.of(
                    new VaultSensClient.FileUpload(file, "one.png", "image/png"),
                    new VaultSensClient.FileUpload(file, "two.jpg", "image/jpeg")),
                    new VaultSensClient.UploadOptions().folderId("folder-1"));
            assertTrue(body.contains("filename=\"two.jpg\"\r\nContent-Type: image/jpeg"));
            client.updateFile("abc", file, new VaultSensClient.UploadOptions().name("new"));
            assertEquals("PUT", method);
            assertEquals("/api/v1/files/abc", path);
            assertFalse(body.contains("name=\"folderId\""));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void exposesErrorsAndRetriesReads() {
        VaultSensException error = assertThrows(VaultSensException.class, () -> client.getFileMetadata("error"));
        assertEquals(413, error.getStatus());
        assertEquals("STORAGE_LIMIT", error.getCode());
        assertEquals("Storage limit exceeded", error.getData().path("message").asText());
        assertEquals(1, calls.get());

        calls.set(0);
        client.setRetries(1);
        assertEquals(200, client.listFiles("retry").path("status").asInt());
        assertEquals(2, calls.get());
    }

    @Test
    void buildsEncodedTransformUrlWithoutCredentials() {
        Map<String, Object> transforms = new LinkedHashMap<>();
        transforms.put("width", 400);
        transforms.put("format", "webp");
        assertEquals("http://127.0.0.1:" + server.getAddress().getPort()
                        + "/api/v1/files/a%2Fb?width=400&format=webp",
                client.buildFileUrl("a/b", transforms));
        assertEquals(0, calls.get());
    }
}
