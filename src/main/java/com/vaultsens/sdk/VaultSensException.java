package com.vaultsens.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Locale;

/** An HTTP or transport error from VaultSens. */
public final class VaultSensException extends RuntimeException {
    private final int status;
    private final String code;
    private final JsonNode data;

    public VaultSensException(String message, int status, JsonNode data) {
        super(message);
        this.status = status;
        this.code = resolveCode(status, message);
        this.data = data;
    }

    public VaultSensException(String message, int status, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = resolveCode(status, message);
        this.data = null;
    }

    public int getStatus() { return status; }
    public String getCode() { return code; }
    public JsonNode getData() { return data; }

    private static String resolveCode(int status, String message) {
        String m = message.toLowerCase(Locale.ROOT);
        if (status == 413 && m.contains("storage limit")) return "STORAGE_LIMIT";
        if (status == 413) return "FILE_TOO_LARGE";
        if (status == 415) return "MIME_TYPE_NOT_ALLOWED";
        if (status == 402) return "SUBSCRIPTION_INACTIVE";
        if (status == 403 && m.contains("compression")) return "COMPRESSION_NOT_ALLOWED";
        if (status == 403 && m.contains("folder")) return "FOLDER_COUNT_LIMIT";
        if (status == 403 && (m.contains("file") || m.contains("maximum"))) return "FILE_COUNT_LIMIT";
        if (status == 403 && m.contains("email")) return "EMAIL_NOT_VERIFIED";
        if (status == 400 && m.contains("already registered")) return "EMAIL_ALREADY_REGISTERED";
        if (status == 400 && (m.contains("invalid email or password") || m.contains("invalid credentials"))) return "INVALID_CREDENTIALS";
        if (status == 400 && m.contains("otp")) return "INVALID_OTP";
        if (status == 401) return "UNAUTHORIZED";
        if (status == 404) return "NOT_FOUND";
        if (status == 408) return "TIMEOUT";
        return "UNKNOWN";
    }
}
