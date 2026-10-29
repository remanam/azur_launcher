package com.azur.launcher.auth;

import com.azur.launcher.i18n.I18n;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class MicrosoftAuthService {

    public static final String SCOPE = "XboxLive.signin offline_access";

    private static final String DEVICE_CODE_GRANT = "urn:ietf:params:oauth:grant-type:device_code";

    private static final long DEVICE_CODE_MAX_WAIT_SECONDS = 900L;

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AuthEndpoints endpoints;
    private final AuthNetwork network;
    private final long retryDelayMs;

    public MicrosoftAuthService(AuthNetwork network) {
        this(AuthEndpoints.PRODUCTION, network, 1_000L);
    }

    MicrosoftAuthService(AuthEndpoints endpoints, AuthNetwork network, long retryDelayMs) {
        this.endpoints = endpoints;
        this.network = network;
        this.retryDelayMs = retryDelayMs;
    }

    public interface StageListener {
        void onStage(String message);
    }

    public static class DeviceCode {
        private final String deviceCode;
        private final String userCode;
        private final String verificationUri;
        private final int expiresIn;
        private final int interval;
        private final String message;

        public DeviceCode(String deviceCode, String userCode, String verificationUri,
                          int expiresIn, int interval, String message) {
            this.deviceCode = deviceCode == null ? "" : deviceCode;
            this.userCode = userCode == null ? "" : userCode;
            this.verificationUri = verificationUri == null ? "" : verificationUri;
            this.expiresIn = expiresIn <= 0 ? 900 : expiresIn;
            this.interval = interval <= 0 ? 5 : interval;
            this.message = message == null ? "" : message;
        }

        public String getDeviceCode() {
            return deviceCode;
        }

        public String getUserCode() {
            return userCode;
        }

        public String getVerificationUri() {
            return verificationUri;
        }

        public int getExpiresIn() {
            return expiresIn;
        }

        public int getInterval() {
            return interval;
        }

        public String getMessage() {
            return message;
        }
    }

    public void cancel() {
        cancelled.set(true);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public DeviceCode requestDeviceCode(String clientId) throws AuthException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", requireClientId(clientId));
        form.put("scope", SCOPE);

        HttpResult result = sendFormWithRetry(endpoints.deviceCode(), form, null);
        if (result.code != 200) {
            throw new AuthException("devicecode_failed",
                    I18n.tr("auth.devicecode_http", result.code, errorDescription(parse(result.body))));
        }
        JsonObject json = parse(result.body);
        String deviceCode = str(json, "device_code");
        String userCode = str(json, "user_code");
        String verificationUri = str(json, "verification_uri");
        if (deviceCode.isBlank() || userCode.isBlank() || verificationUri.isBlank()) {
            throw new AuthException("devicecode_failed", I18n.tr("auth.devicecode_fields"));
        }
        int expiresIn = (int) num(json, "expires_in", 900);
        int interval = (int) num(json, "interval", 5);
        return new DeviceCode(deviceCode, userCode, verificationUri, expiresIn, interval, str(json, "message"));
    }

    public MicrosoftAccount login(DeviceCode deviceCode, String clientId, StageListener listener)
            throws AuthException {
        String cid = requireClientId(clientId);
        notify(listener, I18n.tr("auth.wait_browser"));

        long waitSeconds = Math.max(60L, Math.min(deviceCode.getExpiresIn(), DEVICE_CODE_MAX_WAIT_SECONDS));
        long deadline = System.currentTimeMillis() + waitSeconds * 1000L;
        long interval = Math.max(1, deviceCode.getInterval()) * 1000L;

        while (true) {
            if (cancelled.get()) {
                throw new AuthException("cancelled", I18n.tr("auth.cancelled"));
            }
            if (System.currentTimeMillis() >= deadline) {
                throw new AuthException("expired_token", I18n.tr("auth.code_expired"));
            }
            if (!sleepInterruptible(interval)) {
                throw new AuthException("cancelled", I18n.tr("auth.cancelled"));
            }
            if (System.currentTimeMillis() >= deadline) {
                throw new AuthException("expired_token", I18n.tr("auth.code_expired"));
            }

            HttpResult result;
            try {
                Map<String, String> form = new LinkedHashMap<>();
                form.put("client_id", cid);
                form.put("grant_type", DEVICE_CODE_GRANT);
                form.put("device_code", deviceCode.getDeviceCode());
                result = sendForm(endpoints.token(), form);
            } catch (AuthException e) {
                interval = Math.min(interval * 2L, 30_000L);
                notify(listener, I18n.tr("auth.net_unstable"));
                continue;
            }

            if (result.code == 200) {
                JsonObject token = parse(result.body);
                String msaToken = str(token, "access_token");
                String refreshToken = str(token, "refresh_token");
                if (msaToken.isBlank()) {
                    throw new AuthException("invalid_response", I18n.tr("auth.no_access_token"));
                }
                return completeChain(msaToken, refreshToken, cid, listener);
            }

            if (result.code == 429 || result.code >= 500) {
                interval = Math.min(interval + 5_000L, 60_000L);
                notify(listener, I18n.tr("auth.ms_unavailable_retry", result.code));
                continue;
            }
            if (result.body == null || result.body.isBlank()) {
                interval = Math.min(interval + 5_000L, 60_000L);
                notify(listener, I18n.tr("auth.empty_response_retry", result.code));
                continue;
            }

            JsonObject error = parse(result.body);
            String code = str(error, "error");
            switch (code) {
                case "authorization_pending" -> {
                }
                case "slow_down" -> interval = Math.min(interval + 5_000L, 60_000L);
                case "expired_token" -> throw new AuthException("expired_token", I18n.tr("auth.code_expired"));
                case "authorization_declined" -> throw new AuthException("authorization_declined",
                        I18n.tr("auth.declined"));
                case "bad_verification_code" -> throw new AuthException("bad_verification_code",
                        I18n.tr("auth.bad_code"));
                case "invalid_grant" -> throw new AuthException("invalid_grant",
                        I18n.tr("auth.grant_invalid"));
                default -> throw new AuthException(
                        code.isBlank() ? "http_" + result.code : code,
                        I18n.tr("auth.failed", errorDescription(error)));
            }
        }
    }

    public MicrosoftAccount refresh(MicrosoftAccount account) throws AuthException {
        return refresh(account, null);
    }

    public MicrosoftAccount refresh(MicrosoftAccount account, StageListener listener) throws AuthException {
        if (account == null) {
            throw new AuthException("no_account", I18n.tr("auth.no_account"));
        }
        if (account.getRefreshToken() == null || account.getRefreshToken().isBlank()) {
            throw new AuthException("no_refresh_token", I18n.tr("auth.session_invalid"));
        }
        String cid = requireClientId(account.getClientId());
        notify(listener, I18n.tr("auth.refreshing"));

        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", cid);
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", account.getRefreshToken());
        form.put("scope", SCOPE);

        HttpResult result = sendFormWithRetry(endpoints.token(), form, listener);
        if (result.code != 200) {
            JsonObject error = parse(result.body);
            String code = str(error, "error");
            if ("invalid_grant".equals(code) || "interaction_required".equals(code)
                    || "invalid_request".equals(code) || "unauthorized_client".equals(code)) {
                throw new AuthException("invalid_grant", I18n.tr("auth.session_invalid"));
            }
            throw new AuthException(code.isBlank() ? "http_" + result.code : code,
                    I18n.tr("auth.refresh_failed", errorDescription(error)));
        }
        JsonObject token = parse(result.body);
        String msaToken = str(token, "access_token");
        String refreshToken = str(token, "refresh_token");
        if (msaToken.isBlank()) {
            throw new AuthException("invalid_response", I18n.tr("auth.no_access_token"));
        }
        if (refreshToken.isBlank()) {
            refreshToken = account.getRefreshToken();
        }
        return completeChain(msaToken, refreshToken, cid, listener);
    }

    // ------------------------------------------------------------------ Xbox / XSTS / Minecraft

    private MicrosoftAccount completeChain(String msaAccessToken, String refreshToken,
                                           String clientId, StageListener listener) throws AuthException {
        notify(listener, I18n.tr("auth.stage_xbox"));
        JsonObject props = new JsonObject();
        props.addProperty("AuthMethod", "RPS");
        props.addProperty("SiteName", "user.auth.xboxlive.com");
        props.addProperty("RpsTicket", "d=" + msaAccessToken);

        JsonObject xboxBody = new JsonObject();
        xboxBody.add("Properties", props);
        xboxBody.addProperty("RelyingParty", "http://auth.xboxlive.com");
        xboxBody.addProperty("TokenType", "JWT");

        HttpResult xboxResult = sendJson(endpoints.xboxUser(), xboxBody, Map.of("x-xbl-contract-version", "1"));
        if (xboxResult.code != 200) {
            throw new AuthException("xbox_auth_failed",
                    I18n.tr("auth.xbox_http", xboxResult.code));
        }
        JsonObject xboxJson = parse(xboxResult.body);
        String xblToken = str(xboxJson, "Token");
        String uhs = firstUhs(xboxJson);
        if (xblToken.isBlank() || uhs.isBlank()) {
            throw new AuthException("xbox_auth_failed", I18n.tr("auth.xbox_fields"));
        }

        notify(listener, I18n.tr("auth.stage_xsts"));
        JsonObject xstsProps = new JsonObject();
        xstsProps.addProperty("SandboxId", "RETAIL");
        JsonArray tokens = new JsonArray();
        tokens.add(xblToken);
        xstsProps.add("UserTokens", tokens);

        JsonObject xstsBody = new JsonObject();
        xstsBody.add("Properties", xstsProps);
        xstsBody.addProperty("RelyingParty", "rp://api.minecraftservices.com/");
        xstsBody.addProperty("TokenType", "JWT");

        HttpResult xstsResult = sendJson(endpoints.xsts(), xstsBody, null);
        if (xstsResult.code == 401) {
            JsonObject error = parse(xstsResult.body);
            throw xstsError(error);
        }
        if (xstsResult.code != 200) {
            throw new AuthException("xsts_failed", I18n.tr("auth.xsts_http", xstsResult.code));
        }
        JsonObject xstsJson = parse(xstsResult.body);
        String xstsToken = str(xstsJson, "Token");
        String xstsUhs = firstUhs(xstsJson);
        String xuid = firstClaim(xstsJson, "xid");
        if (xstsToken.isBlank()) {
            throw new AuthException("xsts_failed", I18n.tr("auth.xsts_fields"));
        }
        if (xstsUhs.isBlank() || !xstsUhs.equals(uhs)) {
            throw new AuthException("xsts_failed",
                    I18n.tr("auth.uhs_mismatch"));
        }

        notify(listener, I18n.tr("auth.stage_mc"));
        JsonObject loginBody = new JsonObject();
        loginBody.addProperty("identityToken", "XBL3.0 x=" + uhs + ";" + xstsToken);
        HttpResult loginResult = sendJson(endpoints.mcLogin(), loginBody, null);
        if (loginResult.code != 200) {
            if (loginResult.code == 401) {
                throw new AuthException("mc_login_failed", I18n.tr("auth.mc_denied"));
            }
            throw new AuthException("mc_login_failed", I18n.tr("auth.mc_http", loginResult.code));
        }
        JsonObject loginJson = parse(loginResult.body);
        String mcToken = str(loginJson, "access_token");
        if (mcToken.isBlank()) {
            throw new AuthException("mc_login_failed", I18n.tr("auth.mc_no_token"));
        }
        long expiresIn = (long) num(loginJson, "expires_in", 86_400);
        long notAfter = System.currentTimeMillis() + expiresIn * 1000L;

        notify(listener, I18n.tr("auth.stage_license"));
        HttpResult storeResult = sendGetWithRetry(endpoints.mcEntitlements(), mcToken);
        if (storeResult.code != 200) {
            throw new AuthException("no_entitlement",
                    I18n.tr("auth.license_http", storeResult.code));
        }
        if (!hasMinecraftEntitlement(parse(storeResult.body))) {
            throw new AuthException("no_entitlement", I18n.tr("auth.not_owned"));
        }

        notify(listener, I18n.tr("auth.stage_profile"));
        HttpResult profileResult = sendGetWithRetry(endpoints.mcProfile(), mcToken);
        String profileId;
        String profileName;
        if (profileResult.code == 200) {
            JsonObject profile = parse(profileResult.body);
            profileId = formatUuid(str(profile, "id"));
            profileName = str(profile, "name");
        } else if (profileResult.code == 404) {
            throw profileMissingError(mcToken);
        } else {
            throw new AuthException("profile_failed",
                    I18n.tr("auth.profile_http", profileResult.code));
        }
        if (profileName.isBlank() || profileId.isBlank()) {
            throw new AuthException("profile_failed", I18n.tr("auth.profile_fields"));
        }

        MicrosoftAccount account = new MicrosoftAccount();
        account.setName(profileName);
        account.setUuid(profileId);
        account.setAccessToken(mcToken);
        account.setRefreshToken(refreshToken == null ? "" : refreshToken);
        account.setXuid(xuid.isBlank() ? uhs : xuid);
        account.setClientId(clientId);
        account.setNotAfter(notAfter);
        account.setUserProperties("{}");
        account.setOwnershipVerifiedAt(System.currentTimeMillis());
        notify(listener, I18n.tr("auth.done", profileName));
        return account;
    }

    private AuthException profileMissingError(String mcToken) {
        HttpResult license;
        try {
            license = sendGetWithRetry(endpoints.mcLicense(), mcToken);
        } catch (AuthException e) {
            return new AuthException("no_profile", I18n.tr("auth.no_profile"));
        }
        if (license.code == 200 && hasItems(parse(license.body))) {
            return new AuthException("profile_missing",
                    I18n.tr("auth.profile_missing"));
        }
        return new AuthException("no_profile", I18n.tr("auth.no_profile"));
    }

    private AuthException xstsError(JsonObject error) {
        long xerr = (long) num(error, "XErr", 0);
        String redirect = str(error, "Redirect");
        String suffix = redirect.isBlank() ? "" : " (" + redirect + ")";
        if (xerr == 2148916233L) {
            return new AuthException("xerr_2148916233", I18n.tr("auth.xerr_2148916233") + suffix);
        }
        if (xerr == 2148916235L) {
            return new AuthException("xerr_2148916235", I18n.tr("auth.xerr_2148916235") + suffix);
        }
        if (xerr == 2148916238L) {
            return new AuthException("xerr_2148916238", I18n.tr("auth.xerr_2148916238") + suffix);
        }
        if (xerr == 2148916234L) {
            return new AuthException("xerr_2148916234", I18n.tr("auth.xerr_2148916234") + suffix);
        }
        if (xerr == 2148916236L) {
            return new AuthException("xerr_2148916236", I18n.tr("auth.xerr_2148916236") + suffix);
        }
        if (xerr == 2148916237L) {
            return new AuthException("xerr_2148916237", I18n.tr("auth.xerr_2148916237") + suffix);
        }
        if (xerr == 2148916227L) {
            return new AuthException("xerr_2148916227", I18n.tr("auth.xerr_2148916227") + suffix);
        }
        if (xerr == 2148916229L) {
            return new AuthException("xerr_2148916229", I18n.tr("auth.xerr_2148916229") + suffix);
        }
        return new AuthException("xsts_failed", I18n.tr("auth.xsts_unknown", xerr == 0 ? "" : " (XErr " + xerr + ")") + suffix);
    }

    private boolean hasMinecraftEntitlement(JsonObject json) {
        JsonElement items = json.get("items");
        if (items == null || !items.isJsonArray()) {
            return false;
        }
        for (JsonElement element : items.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            String name = str(element.getAsJsonObject(), "name");
            if ("game_minecraft".equalsIgnoreCase(name) || "product_minecraft".equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasItems(JsonObject json) {
        JsonElement items = json.get("items");
        return items != null && items.isJsonArray() && !items.getAsJsonArray().isEmpty();
    }

    // ------------------------------------------------------------------ HTTP

    private static final class HttpResult {
        private final int code;
        private final String body;

        private HttpResult(int code, String body) {
            this.code = code;
            this.body = body == null ? "" : body;
        }
    }

    private HttpResult sendForm(String url, Map<String, String> form) throws AuthException {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : form.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(entry.getValue() == null ? "" : entry.getValue(),
                            StandardCharsets.UTF_8));
        }
        return send(url, "POST", sb.toString(), "application/x-www-form-urlencoded; charset=utf-8", null);
    }

    private HttpResult sendFormWithRetry(String url, Map<String, String> form, StageListener listener)
            throws AuthException {
        AuthException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            if (attempt > 0) {
                notify(listener, I18n.tr("auth.ms_retry", attempt));
                try {
                    Thread.sleep(retryDelayMs * attempt);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AuthException("cancelled", I18n.tr("auth.cancelled"));
                }
                if (cancelled.get()) {
                    throw new AuthException("cancelled", I18n.tr("auth.cancelled"));
                }
            }
            try {
                HttpResult result = sendForm(url, form);
                if (result.code == 429 || result.code >= 500) {
                    last = new AuthException("http_" + result.code,
                            I18n.tr("auth.ms_http", result.code));
                    continue;
                }
                return result;
            } catch (AuthException e) {
                if (!"network_error".equals(e.getCode())) {
                    throw e;
                }
                last = e;
            }
        }
        throw last == null
                ? new AuthException("network_error", I18n.tr("auth.ms_net"))
                : last;
    }

    private HttpResult sendJson(String url, JsonObject body, Map<String, String> headers)
            throws AuthException {
        return send(url, "POST", body.toString(), "application/json; charset=utf-8", headers);
    }

    private HttpResult sendGet(String url, String bearerToken) throws AuthException {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + bearerToken);
        return send(url, "GET", null, null, headers);
    }

    private HttpResult sendGetWithRetry(String url, String bearerToken) throws AuthException {
        AuthException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            if (attempt > 0) {
                try {
                    Thread.sleep(retryDelayMs * attempt);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AuthException("cancelled", I18n.tr("auth.cancelled"));
                }
                if (cancelled.get()) {
                    throw new AuthException("cancelled", I18n.tr("auth.cancelled"));
                }
            }
            try {
                HttpResult result = sendGet(url, bearerToken);
                if (result.code == 429 || result.code >= 500) {
                    last = new AuthException("http_" + result.code,
                            I18n.tr("auth.mcs_http", result.code));
                    continue;
                }
                return result;
            } catch (AuthException e) {
                if (!"network_error".equals(e.getCode())) {
                    throw e;
                }
                last = e;
            }
        }
        throw last == null
                ? new AuthException("network_error", I18n.tr("auth.mcs_net"))
                : last;
    }

    private HttpResult send(String url, String method, String body, String contentType,
                            Map<String, String> headers) throws AuthException {
        HttpURLConnection conn = null;
        try {
            conn = network.open(url, method);
            if (contentType != null) {
                conn.setRequestProperty("Content-Type", contentType);
            }
            if (headers != null) {
                for (Map.Entry<String, String> header : headers.entrySet()) {
                    conn.setRequestProperty(header.getKey(), header.getValue());
                }
            }
            if (body != null) {
                conn.setDoOutput(true);
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String text = in == null ? "" : readAll(in);
            return new HttpResult(code, text);
        } catch (IOException e) {
            throw new AuthException("network_error", I18n.tr("auth.net_error", e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " (" + e.getMessage() + ")")));
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String readAll(InputStream in) throws IOException {
        try (InputStream input = in; ByteArrayOutputStream out = new ByteArrayOutputStream(4096)) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = input.read(buffer)) != -1) {
                out.write(buffer, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    private static JsonObject parse(String text) {
        try {
            if (text == null || text.isBlank()) {
                return new JsonObject();
            }
            JsonElement element = JsonParser.parseString(text);
            return element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
        } catch (Exception e) {
            return new JsonObject();
        }
    }

    private static String str(JsonObject json, String key) {
        if (json == null) {
            return "";
        }
        JsonElement element = json.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return "";
        }
        return element.getAsString();
    }

    private static double num(JsonObject json, String key, double fallback) {
        if (json == null) {
            return fallback;
        }
        JsonElement element = json.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return element.getAsDouble();
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String firstUhs(JsonObject json) {
        return firstClaim(json, "uhs");
    }

    private static String firstClaim(JsonObject json, String name) {
        JsonElement claims = json == null ? null : json.get("DisplayClaims");
        if (claims == null || !claims.isJsonObject()) {
            return "";
        }
        JsonElement xui = claims.getAsJsonObject().get("xui");
        if (xui == null || !xui.isJsonArray() || xui.getAsJsonArray().isEmpty()) {
            return "";
        }
        JsonElement first = xui.getAsJsonArray().get(0);
        return first.isJsonObject() ? str(first.getAsJsonObject(), name) : "";
    }

    private static String errorDescription(JsonObject error) {
        String description = str(error, "error_description");
        String code = str(error, "error");
        if (!description.isBlank()) {
            return description.replaceAll("\\s+", " ").trim();
        }
        return code.isBlank() ? I18n.tr("auth.unknown_error") : code;
    }

    /** A blank id is an error: the launcher ships no Azure application id of its own. */
    static String requireClientId(String clientId) throws AuthException {
        if (clientId == null || clientId.isBlank()) {
            throw new AuthException("no_client_id", I18n.tr("auth.no_client_id"));
        }
        return clientId.trim();
    }

    public static String formatUuid(String id) {
        if (id == null) {
            return "";
        }
        String raw = id.replace("-", "");
        if (raw.length() != 32) {
            return id;
        }
        return raw.substring(0, 8) + "-" + raw.substring(8, 12) + "-" + raw.substring(12, 16)
                + "-" + raw.substring(16, 20) + "-" + raw.substring(20);
    }

    private static void notify(StageListener listener, String message) {
        if (listener != null) {
            try {
                listener.onStage(message);
            } catch (Exception ignored) {
            }
        }
    }

    private boolean sleepInterruptible(long millis) {
        long remain = millis;
        while (remain > 0) {
            if (cancelled.get()) {
                return false;
            }
            long slice = Math.min(remain, 200L);
            try {
                Thread.sleep(slice);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            remain -= slice;
        }
        return !cancelled.get();
    }
}
