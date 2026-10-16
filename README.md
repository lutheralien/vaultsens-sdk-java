# VaultSens Java SDK

Java 17+ client for VaultSens. It supports API key authentication, file uploads, folder management, metrics, and image transform URLs.

## Install

The library is not published to Maven Central yet. Build and install it locally:

```bash
mvn install
```

Then add it to a Maven project:

```xml
<dependency>
  <groupId>io.github.lutheralien</groupId>
  <artifactId>vaultsens-sdk</artifactId>
  <version>0.1.0</version>
</dependency>
```

The only runtime dependency is Jackson Databind. The client uses the JDK HTTP client.

## Quick start

```java
import com.vaultsens.sdk.VaultSensClient;
import java.nio.file.Path;

VaultSensClient client = new VaultSensClient(
    "https://api.vaultsens.com",
    System.getenv("VAULTSENS_API_KEY"),
    System.getenv("VAULTSENS_API_SECRET")
);

var result = client.uploadFile(Path.of("photo.png"),
    new VaultSensClient.UploadOptions().name("hero").compression("low"));
String fileId = result.path("data").path("_id").asText();
System.out.println(client.buildFileUrl(fileId));
```

Responses are Jackson `JsonNode` values with the API's `status`, `message`, and `data` fields. Request methods are synchronous.

## Authentication and configuration

The client sends `x-api-key` and `x-api-secret` on every API request. Do not put secrets in source code or in public file URLs.

```java
client.setAuth("new-key", "new-secret");
client.setTimeout(java.time.Duration.ofSeconds(15));
client.setRetries(2);
```

GET requests retry HTTP 429 and 5xx responses, timeouts, and transport failures. Writes are sent once so an upload or mutation is not duplicated after an uncertain network failure.

## Files

```java
var all = client.listFiles();
var root = client.listFiles("root");
var inFolder = client.listFiles("folder-id");
var metadata = client.getFileMetadata("file-id");

var options = new VaultSensClient.UploadOptions()
    .name("hero")
    .filename("photo.png")
    .mimeType("image/png")
    .compression("medium")
    .folderId("folder-id");
var uploaded = client.uploadFile(Path.of("photo.png"), options);
var batch = client.uploadFiles(java.util.List.of(Path.of("a.png"), Path.of("b.png")),
    new VaultSensClient.UploadOptions().compression("low"));
var batchWithTypes = client.uploadFilesWithMetadata(java.util.List.of(
    new VaultSensClient.FileUpload(Path.of("a.png"), "a.png", "image/png"),
    new VaultSensClient.FileUpload(Path.of("b.jpg"), "b.jpg", "image/jpeg")
), new VaultSensClient.UploadOptions().folderId("folder-id"));

client.updateFile("file-id", Path.of("replacement.png"),
    new VaultSensClient.UploadOptions().name("replacement"));
client.deleteFile("file-id");
```

For single uploads, `filename` and `mimeType` override the multipart filename and content type. Otherwise the filename comes from the path and the MIME type from the operating system, falling back to `application/octet-stream`. Batch uploads accept 1–20 paths. Use `uploadFilesWithMetadata` when individual files need filename or MIME overrides. `folderId` applies to new uploads; updates keep the file in its existing folder.

## Folders and metrics

```java
client.listFolders();
client.createFolder("Designs");
client.createFolder("Drafts", "parent-folder-id");
client.renameFolder("folder-id", "Archived");
client.deleteFolder("folder-id");
client.getMetrics();
```

## Image URLs

```java
import java.util.LinkedHashMap;

var transforms = new LinkedHashMap<String, Object>();
transforms.put("width", 400);
transforms.put("format", "webp");
transforms.put("quality", 80);
String url = client.buildFileUrl("file-id", transforms);
```

Supported keys: `width`, `height`, `format`, `quality`, and `fit`. This URL points to the public file endpoint and does not include API credentials.

## Errors

```java
import com.vaultsens.sdk.VaultSensException;

try {
    client.getFileMetadata("file-id");
} catch (VaultSensException e) {
    System.err.println(e.getStatus() + " " + e.getCode() + ": " + e.getMessage());
    // e.getData() contains the API error response, when available.
}
```

Error codes follow the other VaultSens SDKs, including `FILE_TOO_LARGE`, `STORAGE_LIMIT`, `MIME_TYPE_NOT_ALLOWED`, `UNAUTHORIZED`, `NOT_FOUND`, and `TIMEOUT`. Network failures have status `0` and code `UNKNOWN`.

## Build and test

```bash
mvn test
mvn package
```

The tests use a local HTTP server. No VaultSens account is needed.

## Publish to Maven Central

The release coordinates are `io.github.lutheralien:vaultsens-sdk:0.1.0`. Publishing requires the verified `io.github.lutheralien` Central Portal namespace and a PGP signing key. You can upload a signed bundle in the Central Portal without a Portal token. The signed bundle has been generated locally at `target/central-publishing/central-bundle.zip` for this release.

For future command-line releases, generate a Portal user token and configure it in your private `~/.m2/settings.xml` file (never commit it):

```xml
<settings>
  <servers>
    <server>
      <id>central</id>
      <username>PORTAL_TOKEN_USERNAME</username>
      <password>PORTAL_TOKEN_PASSWORD</password>
    </server>
  </servers>
</settings>
```

Import or create a GPG signing key, publish its public key to a key server, and make it available to `gpg`. Then run:

```bash
mvn -P release clean deploy
```

The `release` profile creates source and Javadoc JARs, signs the artifacts, uploads through Sonatype's Central Publishing plugin, and waits for publication. Check that version `0.1.0` is unused before running it; Maven Central releases cannot be replaced. A local check without uploading or signing is:

```bash
mvn -P release -Dgpg.skip=true clean verify
```
