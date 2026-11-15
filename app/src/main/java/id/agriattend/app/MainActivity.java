package id.agriattend.app;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.location.Location;
import android.location.LocationManager;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Toast;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewClientCompat;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.net.ssl.HttpsURLConnection;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

public class MainActivity extends Activity {
    private static final int REQUEST_LOCATION = 4101;
    private static final int REQUEST_FILE = 4102;
    private static final String ASSET_HOST = "appassets.androidplatform.net";
    private static final String MASTER_HOST = "agriconnect-master.starconnect.id";
    private static final String KEY_ALIAS = "AgriAttendSessionKeyV120";
    private static final String PREFS = "agriattend_secure_v120";
    private static final String SESSION_KEY = "session_blob";
    private static final String API_HOST_KEY = "tenant_api_host";
    private static final String SIMKOPDES_ACCESS_KEY = "simkopdes_api_access";
    private static final String SIMKOPDES_KEY_ALIAS = "AgriAttendSimkopdesApiKeyV161";

    private WebView webView;
    private ValueCallback<Uri[]> fileChooserCallback;
    private GeolocationPermissions.Callback pendingGeoCallback;
    private String pendingGeoOrigin;
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    private final WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
            .build();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setGeolocationEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setDatabaseEnabled(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");
        webView.setWebViewClient(new WebViewClientCompat() {
            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
                if ("https".equalsIgnoreCase(uri.getScheme()) &&
                        ASSET_HOST.equals(host)) {
                    return false;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (ActivityNotFoundException ignored) {
                    Toast.makeText(MainActivity.this, "Tidak ada aplikasi untuk membuka tautan ini.", Toast.LENGTH_SHORT).show();
                }
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                if (hasLocationPermission()) {
                    callback.invoke(origin, true, false);
                } else {
                    pendingGeoOrigin = origin;
                    pendingGeoCallback = callback;
                    requestLocationPermission();
                }
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileChooserCallback != null) fileChooserCallback.onReceiveValue(null);
                fileChooserCallback = callback;
                Intent intent = params.createIntent();
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                try {
                    startActivityForResult(intent, REQUEST_FILE);
                } catch (ActivityNotFoundException e) {
                    fileChooserCallback = null;
                    Toast.makeText(MainActivity.this, "Pengelola file tidak tersedia.", Toast.LENGTH_SHORT).show();
                    return false;
                }
                return true;
            }
        });

        webView.loadUrl("https://" + ASSET_HOST + "/assets/index.html");
    }

    private boolean hasLocationPermission() {
        // Attendance uses an accurate fix for the tenant's geofence. Approximate-only
        // permission is not sufficient for this workflow.
        return Build.VERSION.SDK_INT < 23 ||
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestLocationPermission() {
        if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQUEST_LOCATION);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_LOCATION && pendingGeoCallback != null) {
            boolean granted = hasLocationPermission();
            pendingGeoCallback.invoke(pendingGeoOrigin, granted, false);
            pendingGeoCallback = null;
            pendingGeoOrigin = null;
            if (!granted) Toast.makeText(this, "Izinkan Lokasi Tepat (Precise location) untuk absensi di kantor.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_FILE && fileChooserCallback != null) {
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    results = new Uri[count];
                    for (int i = 0; i < count; i++) results[i] = data.getClipData().getItemAt(i).getUri();
                } else if (data.getData() != null) {
                    results = new Uri[]{data.getData()};
                }
            }
            fileChooserCallback.onReceiveValue(results);
            fileChooserCallback = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        networkExecutor.shutdownNow();
        if (webView != null) {
            webView.removeJavascriptInterface("AndroidBridge");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    private boolean isPublicHost(String host) {
        try {
            if (host == null || host.isEmpty() || host.equalsIgnoreCase("localhost") || host.endsWith(".local")) return false;
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0) return false;
            for (InetAddress address : addresses) {
                if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress() ||
                        address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isAllowedApiUrl(String rawUrl) {
        try {
            URL url = new URL(rawUrl);
            if (!"https".equalsIgnoreCase(url.getProtocol()) || url.getUserInfo() != null || url.getPort() != -1 && url.getPort() != 443) return false;
            String host = url.getHost().toLowerCase();
            if (!isPublicHost(host)) return false;
            if (MASTER_HOST.equals(host)) return url.getPath().startsWith("/api/auth");
            if ("nominatim.openstreetmap.org".equals(host)) return "/search".equals(url.getPath());
            String allowedHost = getSharedPreferences(PREFS, MODE_PRIVATE).getString(API_HOST_KEY, "");
            return !allowedHost.isEmpty() && allowedHost.equalsIgnoreCase(host) && url.getPath().contains("/api/");
        } catch (Exception e) {
            return false;
        }
    }

    private void ensureKey() throws Exception { ensureKey(KEY_ALIAS); }

    private void ensureKey(String alias) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(alias)) return;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true);
        if (Build.VERSION.SDK_INT >= 28) builder.setUnlockedDeviceRequired(false);
        generator.init(builder.build());
        generator.generateKey();
    }

    private SecretKey getSecretKey() throws Exception { return getSecretKey(KEY_ALIAS); }

    private SecretKey getSecretKey(String alias) throws Exception {
        ensureKey(alias);
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        return (SecretKey) keyStore.getKey(alias, null);
    }

    private String decryptSecureValue(String stored, SecretKey key) throws Exception {
        if (stored == null || stored.isEmpty()) return "";
        JSONObject packed = new JSONObject(stored);
        byte[] iv = Base64.decode(packed.getString("iv"), Base64.NO_WRAP);
        byte[] ciphertext = Base64.decode(packed.getString("data"), Base64.NO_WRAP);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    }

    private String encryptSecureValue(String value, SecretKey key) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] encrypted = cipher.doFinal((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
        JSONObject packed = new JSONObject();
        packed.put("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP));
        packed.put("data", Base64.encodeToString(encrypted, Base64.NO_WRAP));
        return packed.toString();
    }

    private String secureRead() {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            String stored = prefs.getString(SESSION_KEY, "");
            return decryptSecureValue(stored, getSecretKey());
        } catch (Exception e) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(SESSION_KEY).apply();
            return "";
        }
    }

    private void secureWrite(String value) throws Exception {
        String packed = encryptSecureValue(value, getSecretKey());
        if (!getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(SESSION_KEY, packed).commit()) {
            throw new IllegalStateException("Sesi tidak berhasil disimpan ke perangkat.");
        }
    }

    private JSONObject readSimkopdesApiAccess() {
        try {
            String packed = getSharedPreferences(PREFS, MODE_PRIVATE).getString(SIMKOPDES_ACCESS_KEY, "");
            String plain = decryptSecureValue(packed, getSecretKey(SIMKOPDES_KEY_ALIAS));
            return plain.isEmpty() ? new JSONObject() : new JSONObject(plain);
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    private boolean writeSimkopdesApiAccess(String facilitatorId, String bearerToken) {
        try {
            if (facilitatorId == null || !facilitatorId.trim().matches("[0-9]{1,12}")) return false;
            if (bearerToken == null || bearerToken.trim().length() < 20 || bearerToken.trim().length() > 8192) return false;
            JSONObject access = new JSONObject();
            access.put("facilitatorId", facilitatorId.trim());
            access.put("bearerToken", bearerToken.trim());
            String packed = encryptSecureValue(access.toString(), getSecretKey(SIMKOPDES_KEY_ALIAS));
            boolean saved = getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(SIMKOPDES_ACCESS_KEY, packed).commit();
            if (!saved) return false;
            JSONObject verify = readSimkopdesApiAccess();
            return facilitatorId.trim().equals(verify.optString("facilitatorId", ""))
                    && bearerToken.trim().equals(verify.optString("bearerToken", ""));
        } catch (Exception e) {
            return false;
        }
    }

    private JSONObject simkopdesApiAccessInfo() {
        JSONObject saved = readSimkopdesApiAccess();
        JSONObject info = new JSONObject();
        try {
            String fid = saved.optString("facilitatorId", "");
            boolean hasToken = saved.optString("bearerToken", "").length() >= 20;
            info.put("saved", !fid.isEmpty() && hasToken);
            info.put("facilitatorId", fid);
            info.put("tokenConfigured", hasToken);
        } catch (Exception ignored) { }
        return info;
    }

    private byte[] readAll(InputStream stream) throws Exception {
        if (stream == null) return new byte[0];
        try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            return out.toByteArray();
        }
    }

    private double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private String mockLocationStatus(double latitude, double longitude, long capturedAtMs) {
        try {
            if (Build.VERSION.SDK_INT >= 23 &&
                    checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                    checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                return "unknown";
            }
            LocationManager manager = (LocationManager) getSystemService(LOCATION_SERVICE);
            if (manager == null) return "unknown";
            Location best = null;
            for (String provider : manager.getProviders(true)) {
                try {
                    Location candidate = manager.getLastKnownLocation(provider);
                    if (candidate == null) continue;
                    long age = Math.abs(capturedAtMs - candidate.getTime());
                    if (age > 120000L) continue;
                    double distance = distanceMeters(latitude, longitude, candidate.getLatitude(), candidate.getLongitude());
                    if (distance > 150.0) continue;
                    if (best == null || candidate.getTime() > best.getTime()) best = candidate;
                } catch (SecurityException ignored) {
                    // Permission may be revoked while the app is running.
                }
            }
            if (best == null) return "unknown";
            boolean mock = Build.VERSION.SDK_INT >= 31 ? best.isMock() : best.isFromMockProvider();
            return mock ? "mock" : "real";
        } catch (Exception e) {
            return "unknown";
        }
    }

    private byte[] buildMultipartBody(JSONObject data, String boundary) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String checkinout = data.optString("checkinout", "");
        writePart(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"checkinout\"\r\n\r\n" + checkinout + "\r\n");
        String photo = data.optString("photoBase64", "");
        if (!photo.isEmpty()) {
            byte[] imageBytes = Base64.decode(photo, Base64.DEFAULT);
            String filename = safeFilename(data.optString("fileName", "attendance.jpg"));
            String mime = safeMimeType(data.optString("photoMimeType", "image/jpeg"));
            writePart(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\nContent-Type: " + mime + "\r\n\r\n");
            out.write(imageBytes);
            writePart(out, "\r\n");
        }
        org.json.JSONArray files = data.optJSONArray("files");
        if (files != null) {
            for (int i = 0; i < files.length(); i++) {
                JSONObject file = files.optJSONObject(i);
                if (file == null) continue;
                String encoded = file.optString("fileBase64", "");
                if (encoded.isEmpty()) continue;
                byte[] fileBytes = Base64.decode(encoded, Base64.DEFAULT);
                String filename = safeFilename(file.optString("fileName", "attachment.bin"));
                String mime = safeMimeType(file.optString("fileMimeType", "application/octet-stream"));
                writePart(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\nContent-Type: " + mime + "\r\n\r\n");
                out.write(fileBytes);
                writePart(out, "\r\n");
            }
        }
        writePart(out, "--" + boundary + "--\r\n");
        return out.toByteArray();
    }

    private String safeFilename(String value) {
        String safe = value == null ? "attachment.bin" : value.replaceAll("[^A-Za-z0-9._-]", "_");
        return safe.isEmpty() ? "attachment.bin" : safe;
    }

    private String safeMimeType(String value) {
        String mime = value == null ? "application/octet-stream" : value.trim().toLowerCase();
        if (!mime.matches("[a-z0-9.+-]+/[a-z0-9.+-]+")) return "application/octet-stream";
        return mime;
    }

    private void writePart(ByteArrayOutputStream out, String text) throws Exception {
        out.write(text.getBytes(StandardCharsets.UTF_8));
    }

    private void apiRequest(String method, String url, String headersJson, String body, String bodyMode, String callbackId) {
        if (!isAllowedApiUrl(url)) {
            postApiResult(callbackId, 0, "{\"message\":\"Alamat API tidak diizinkan oleh pemeriksaan keamanan aplikasi.\"}");
            return;
        }
        networkExecutor.execute(() -> {
            int status = 0;
            String responseText = "";
            HttpsURLConnection connection = null;
            try {
                URL target = new URL(url);
                connection = (HttpsURLConnection) target.openConnection();
                connection.setRequestMethod(method == null ? "GET" : method.toUpperCase());
                connection.setConnectTimeout(20000);
                connection.setReadTimeout(45000);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("Accept", "application/json");
                if (headersJson != null && !headersJson.isEmpty()) {
                    JSONObject headers = new JSONObject(headersJson);
                    java.util.Iterator<String> keys = headers.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        String value = headers.optString(key, "");
                        if (!key.equalsIgnoreCase("Host") && !key.equalsIgnoreCase("Content-Length") && !key.equalsIgnoreCase("Connection")) {
                            connection.setRequestProperty(key, value);
                        }
                    }
                }
                if ("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method) || "PATCH".equalsIgnoreCase(method)) {
                    connection.setDoOutput(true);
                    byte[] bytes;
                    if ("attendance".equals(bodyMode)) {
                        String boundary = "----AgriAttend" + UUID.randomUUID().toString().replace("-", "");
                        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                        bytes = buildMultipartBody(new JSONObject(body == null ? "{}" : body), boundary);
                    } else {
                        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                        bytes = (body == null ? "{}" : body).getBytes(StandardCharsets.UTF_8);
                    }
                    connection.setFixedLengthStreamingMode(bytes.length);
                    try (OutputStream output = connection.getOutputStream()) {
                        output.write(bytes);
                    }
                }
                status = connection.getResponseCode();
                InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
                responseText = new String(readAll(stream), StandardCharsets.UTF_8);
            } catch (Exception e) {
                try {
                    JSONObject error = new JSONObject();
                    error.put("message", e.getMessage() == null ? "Koneksi API gagal." : e.getMessage());
                    responseText = error.toString();
                } catch (Exception ignored) {
                    responseText = "{\"message\":\"Koneksi API gagal.\"}";
                }
            } finally {
                if (connection != null) connection.disconnect();
            }
            postApiResult(callbackId, status, responseText);
        });
    }

    /** Fetch public SIMKOPDES asset-verification data through HTTPS API calls.
     * No nested WebView/browser is created. The public API currently does not
     * expose the Verval batch 1-4 field or the protected-page non-priority flag.
     */
    private void fetchVervalDirect(String province, String district, String assignmentCode) {
        String provinceName = province == null ? "" : province.trim();
        String districtName = district == null ? "" : district.trim();
        String assignment = assignmentCode == null ? "" : assignmentCode.trim();
        if (provinceName.isEmpty() || districtName.isEmpty() || assignment.isEmpty()) {
            JSONObject failure = new JSONObject();
            try {
                failure.put("success", false);
                failure.put("message", "Provinsi, kabupaten/kota, dan nama Kopdes penugasan harus tersedia.");
            } catch (Exception ignored) { }
            postVervalDirectResult(failure);
            return;
        }
        runOnUiThread(() -> {
            if (webView != null) webView.evaluateJavascript(
                    "window.__vervalDirectStatus && window.__vervalDirectStatus('Mengambil data aset langsung dari API publik SIMKOPDES…');", null);
        });
        networkExecutor.execute(() -> {
            JSONObject result;
            try {
                JSONObject simkopdesAccess = readSimkopdesApiAccess();
                int facilitatorId = 0;
                try { facilitatorId = Integer.parseInt(simkopdesAccess.optString("facilitatorId", "0")); } catch (Exception ignored) { }
                String facilitatorToken = simkopdesAccess.optString("bearerToken", "");
                result = new SimkopdesPublicApi().fetchAssignedCooperative(
                        provinceName, districtName, assignment, facilitatorId, facilitatorToken);
            } catch (Exception e) {
                result = new JSONObject();
                try {
                    result.put("success", false);
                    result.put("message", e.getMessage() == null ? "Gagal mengambil data langsung dari API SIMKOPDES." : e.getMessage());
                    result.put("records", new org.json.JSONArray());
                } catch (Exception ignored) { }
            }
            postVervalDirectResult(result);
        });
    }

    private void postVervalDirectResult(JSONObject result) {
        if (webView == null) return;
        String payload = result == null ? "{}" : result.toString();
        String js = "window.__vervalDirectResult && window.__vervalDirectResult(" + JSONObject.quote(payload) + ");";
        runOnUiThread(() -> {
            if (webView != null) webView.evaluateJavascript(js, null);
        });
    }

    private void postApiResult(String callbackId, int status, String responseText) {
        if (webView == null) return;
        String id = callbackId == null ? "" : callbackId.replaceAll("[^A-Za-z0-9_-]", "");
        String js = "window.__nativeApiResult && window.__nativeApiResult(" + JSONObject.quote(id) + "," + status + "," + JSONObject.quote(responseText == null ? "" : responseText) + ");";
        runOnUiThread(() -> {
            if (webView != null) webView.evaluateJavascript(js, null);
        });
    }

    private class AndroidBridge {
        @JavascriptInterface
        public void apiRequest(String method, String url, String headersJson, String body, String bodyMode, String callbackId) {
            MainActivity.this.apiRequest(method, url, headersJson, body, bodyMode, callbackId);
        }

        @JavascriptInterface
        public boolean setTenantApiHost(String host) {
            try {
                String candidate = (host == null ? "" : host.trim()).toLowerCase();
                if (candidate.isEmpty() || candidate.contains("/") || candidate.contains(":") || !isPublicHost(candidate)) return false;
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(API_HOST_KEY, candidate).apply();
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public String getSecureSession() {
            return secureRead();
        }

        @JavascriptInterface
        public boolean saveSecureSession(String sessionJson) {
            try {
                secureWrite(sessionJson);
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public void clearSecureSession() {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(SESSION_KEY).remove(API_HOST_KEY).apply();
        }

        @JavascriptInterface
        public void fetchVervalDirect(String provinsi, String kabupaten, String assignmentCode) {
            MainActivity.this.fetchVervalDirect(provinsi, kabupaten, assignmentCode);
        }

        @JavascriptInterface
        public boolean saveSimkopdesApiAccess(String facilitatorId, String bearerToken) {
            return MainActivity.this.writeSimkopdesApiAccess(facilitatorId, bearerToken);
        }

        @JavascriptInterface
        public String getSimkopdesApiAccessInfo() {
            return MainActivity.this.simkopdesApiAccessInfo().toString();
        }

        @JavascriptInterface
        public void clearSimkopdesApiAccess() {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(SIMKOPDES_ACCESS_KEY).commit();
        }

        @JavascriptInterface
        public String getMockLocationStatus(double latitude, double longitude, long capturedAtMs) {
            return mockLocationStatus(latitude, longitude, capturedAtMs);
        }

        @JavascriptInterface
        public String getDeviceInfo() {
            try {
                JSONObject info = new JSONObject();
                info.put("deviceId", Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID));
                info.put("deviceName", Build.DEVICE);
                info.put("deviceModel", Build.MODEL);
                info.put("devicePlatform", "android");
                info.put("deviceOs", "android");
                info.put("deviceOsVersion", Build.VERSION.RELEASE);
                info.put("deviceAndroidSdkVersion", Build.VERSION.SDK_INT);
                info.put("deviceManufacturer", Build.MANUFACTURER);
                info.put("deviceVirtual", Build.FINGERPRINT != null && Build.FINGERPRINT.toLowerCase().contains("generic"));
                String webviewVersion = "";
                if (Build.VERSION.SDK_INT >= 26) {
                    android.content.pm.PackageInfo webInfo = WebView.getCurrentWebViewPackage();
                    if (webInfo != null && webInfo.versionName != null) webviewVersion = webInfo.versionName;
                }
                info.put("deviceWebviewVersion", webviewVersion);
                android.content.pm.PackageInfo appInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
                info.put("appVersion", appInfo.versionName == null ? "" : appInfo.versionName);
                return info.toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        @JavascriptInterface
        public void saveTextFile(String fileName, String content) {
            try {
                String safeName = fileName == null ? "agriattend-export.json" : fileName.replaceAll("[^A-Za-z0-9._-]", "_");
                byte[] bytes = (content == null ? "" : content).getBytes(StandardCharsets.UTF_8);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Downloads.DISPLAY_NAME, safeName);
                    values.put(MediaStore.Downloads.MIME_TYPE, "application/json");
                    values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AgriAttend");
                    Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                    if (uri == null) throw new IllegalStateException("Tidak dapat membuat berkas unduhan.");
                    try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                        if (out == null) throw new IllegalStateException("Tidak dapat membuka berkas unduhan.");
                        out.write(bytes);
                    }
                } else {
                    File base = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    if (base == null) throw new IllegalStateException("Penyimpanan tidak tersedia.");
                    File dir = new File(base, "AgriAttend");
                    if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Folder unduhan gagal dibuat.");
                    try (FileOutputStream out = new FileOutputStream(new File(dir, safeName))) {
                        out.write(bytes);
                    }
                }
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Ekspor tersimpan di folder Download/AgriAttend.", Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Ekspor gagal: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }
    }
}
