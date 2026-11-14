package id.agriattend.app;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
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
import android.webkit.JsPromptResult;
import android.widget.Toast;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewClientCompat;
import androidx.webkit.WebResourceErrorCompat;

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
    private static final String VERVAL_PASSWORD_KEY = "verval_passcode_blob";
    private static final String VERVAL_URL = "https://simkopdes.go.id/pers/asset/verval?batch_verification=1%2C2%2C3%2C4";

    private WebView webView;
    private Dialog vervalDialog;
    private WebView vervalWebView;
    private TextView vervalStatusView;
    private String vervalKabupaten = "";
    private String vervalProvinsi = "";
    private String vervalInjectedUrl = "";
    private boolean vervalCompleted = false;
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
        if (vervalDialog != null && vervalDialog.isShowing()) vervalDialog.dismiss();
        destroyVervalWebView();
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

    private void ensureKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) return;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true);
        if (Build.VERSION.SDK_INT >= 28) builder.setUnlockedDeviceRequired(false);
        generator.init(builder.build());
        generator.generateKey();
    }

    private SecretKey getSecretKey() throws Exception {
        ensureKey();
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        return (SecretKey) keyStore.getKey(KEY_ALIAS, null);
    }

    private String secureRead() {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            String stored = prefs.getString(SESSION_KEY, "");
            if (stored.isEmpty()) return "";
            JSONObject packed = new JSONObject(stored);
            byte[] iv = Base64.decode(packed.getString("iv"), Base64.NO_WRAP);
            byte[] ciphertext = Base64.decode(packed.getString("data"), Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(SESSION_KEY).apply();
            return "";
        }
    }

    private void secureWrite(String value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getSecretKey());
        byte[] encrypted = cipher.doFinal((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
        JSONObject packed = new JSONObject();
        packed.put("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP));
        packed.put("data", Base64.encodeToString(encrypted, Base64.NO_WRAP));
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(SESSION_KEY, packed.toString()).apply();
    }

    private String secureReadValue(String preferenceKey) {
        try {
            String stored = getSharedPreferences(PREFS, MODE_PRIVATE).getString(preferenceKey, "");
            if (stored.isEmpty()) return "";
            JSONObject packed = new JSONObject(stored);
            byte[] iv = Base64.decode(packed.getString("iv"), Base64.NO_WRAP);
            byte[] ciphertext = Base64.decode(packed.getString("data"), Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(preferenceKey).apply();
            return "";
        }
    }

    private boolean secureWriteValue(String preferenceKey, String value) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getSecretKey());
            byte[] encrypted = cipher.doFinal((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            JSONObject packed = new JSONObject();
            packed.put("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP));
            packed.put("data", Base64.encodeToString(encrypted, Base64.NO_WRAP));
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(preferenceKey, packed.toString()).apply();
            return true;
        } catch (Exception e) { return false; }
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

    private void openVervalPortal(String kabupaten, String provinsi) {
        String district = kabupaten == null ? "" : kabupaten.trim();
        String province = provinsi == null ? "" : provinsi.trim();
        if (district.isEmpty()) {
            sendVervalError("Kabupaten/kota penugasan dari AgriConnect belum tersedia.");
            return;
        }
        String passcode = secureReadValue(VERVAL_PASSWORD_KEY);
        if (passcode.isEmpty()) {
            sendVervalNeedPassword();
            return;
        }
        runOnUiThread(() -> showVervalPortal(district, province, passcode));
    }

    private void showVervalPortal(String kabupaten, String provinsi, String passcode) {
        try {
            if (vervalDialog != null && vervalDialog.isShowing()) vervalDialog.dismiss();
            vervalKabupaten = kabupaten;
            vervalProvinsi = provinsi;
            vervalInjectedUrl = "";
            vervalCompleted = false;

            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundColor(android.graphics.Color.WHITE);
            root.setPadding(18, 12, 18, 10);

            LinearLayout header = new LinearLayout(this);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            TextView title = new TextView(this);
            title.setText("Pantauan Verval Kopdes");
            title.setTextSize(17);
            title.setTextColor(android.graphics.Color.rgb(15, 78, 91));
            title.setTypeface(null, android.graphics.Typeface.BOLD);
            header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            Button close = new Button(this);
            close.setText("Tutup");
            header.addView(close, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            root.addView(header);

            TextView assignment = new TextView(this);
            assignment.setText("Lokasi: " + kabupaten + (provinsi.isEmpty() ? "" : " · " + provinsi));
            assignment.setTextSize(12);
            assignment.setTextColor(android.graphics.Color.DKGRAY);
            assignment.setPadding(0, 8, 0, 8);
            root.addView(assignment);

            LinearLayout progressRow = new LinearLayout(this);
            progressRow.setOrientation(LinearLayout.HORIZONTAL);
            progressRow.setGravity(Gravity.CENTER_VERTICAL);
            ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            progress.setIndeterminate(true);
            progressRow.addView(progress, new LinearLayout.LayoutParams(42, 5));
            vervalStatusView = new TextView(this);
            vervalStatusView.setText("Membuka portal Simkopdes…");
            vervalStatusView.setTextSize(11);
            vervalStatusView.setTextColor(android.graphics.Color.rgb(77, 95, 85));
            vervalStatusView.setPadding(10, 0, 0, 0);
            progressRow.addView(vervalStatusView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            root.addView(progressRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            vervalWebView = new WebView(this);
            WebSettings settings = vervalWebView.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setDatabaseEnabled(true);
            settings.setAllowFileAccess(false);
            settings.setAllowContentAccess(false);
            settings.setSupportZoom(true);
            settings.setBuiltInZoomControls(true);
            settings.setDisplayZoomControls(false);
            settings.setJavaScriptCanOpenWindowsAutomatically(false);
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
            android.webkit.CookieManager.getInstance().setAcceptCookie(true);
            vervalWebView.addJavascriptInterface(new VervalPortalBridge(), "VervalPortalBridge");
            vervalWebView.setWebChromeClient(new WebChromeClient() {
                @Override
                public boolean onJsPrompt(WebView view, String url, String message, String defaultValue, JsPromptResult result) {
                    String lower = message == null ? "" : message.toLowerCase();
                    if (lower.contains("pass") || lower.contains("password") || lower.contains("passcode") || lower.contains("kode akses")) {
                        result.confirm(passcode);
                        updateVervalStatus("Passcode verval dimasukkan otomatis ke permintaan portal…");
                        return true;
                    }
                    return false;
                }
            });
            vervalWebView.setWebViewClient(new WebViewClientCompat() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    Uri uri = request.getUrl();
                    String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
                    if ("https".equalsIgnoreCase(uri.getScheme()) &&
                            ("simkopdes.go.id".equals(host) || host.endsWith(".simkopdes.go.id"))) return false;
                    return true;
                }

                @Override
                public void onPageFinished(WebView view, String url) {
                    super.onPageFinished(view, url);
                    Uri uri = Uri.parse(url);
                    String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
                    if (!("simkopdes.go.id".equals(host) || host.endsWith(".simkopdes.go.id"))) return;
                    vervalInjectedUrl = url;
                    injectVervalAutomation(view, kabupaten, provinsi, passcode);
                }

                @Override
                public void onReceivedError(WebView view, WebResourceRequest request, WebResourceErrorCompat error) {
                    super.onReceivedError(view, request, error);
                    if (request.isForMainFrame()) {
                        CharSequence description = error.getDescription();
                        updateVervalStatus("Portal Simkopdes gagal dimuat: " + String.valueOf(description));
                    }
                }
            });

            root.addView(vervalWebView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
            vervalDialog = new Dialog(this);
            vervalDialog.setContentView(root);
            Window window = vervalDialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawableResource(android.R.color.white);
                window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            }
            close.setOnClickListener(v -> {
                if (vervalDialog != null) vervalDialog.dismiss();
            });
            vervalDialog.setCancelable(true);
            vervalDialog.setCanceledOnTouchOutside(false);
            vervalDialog.setOnDismissListener(dialog -> {
                if (!vervalCompleted) sendVervalError("Pemeriksaan verval ditutup sebelum selesai.");
                destroyVervalWebView();
            });
            vervalDialog.show();
            if (window != null) window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            vervalWebView.loadUrl(VERVAL_URL);
        } catch (Exception e) {
            sendVervalError("Tidak dapat membuka portal verval: " + (e.getMessage() == null ? "kesalahan perangkat" : e.getMessage()));
        }
    }

    private void injectVervalAutomation(WebView target, String kabupaten, String provinsi, String passcode) {
        try {
            InputStream input = getAssets().open("verval-automation.js");
            String source = new String(readAll(input), StandardCharsets.UTF_8);
            JSONObject config = new JSONObject();
            config.put("kabupaten", kabupaten);
            config.put("provinsi", provinsi);
            config.put("password", passcode);
            config.put("runId", UUID.randomUUID().toString());
            String configJson = config.toString();
            String script = source.replace("(window.__AGRI_VERVAL_CONFIG__ || {})", configJson);
            target.evaluateJavascript(script, null);
        } catch (Exception e) {
            updateVervalStatus("Otomatisasi tidak dapat disiapkan: " + (e.getMessage() == null ? "kesalahan script" : e.getMessage()));
        }
    }

    private void updateVervalStatus(String message) {
        runOnUiThread(() -> {
            if (vervalStatusView != null) vervalStatusView.setText(message == null ? "Memproses…" : message);
            if (webView != null) webView.evaluateJavascript("window.__vervalAutomationStatus && window.__vervalAutomationStatus(" + JSONObject.quote(message == null ? "" : message) + ");", null);
        });
    }

    private void sendVervalError(String message) {
        runOnUiThread(() -> {
            if (webView != null) webView.evaluateJavascript("window.__vervalAutomationError && window.__vervalAutomationError(" + JSONObject.quote(message) + ");", null);
            if (vervalStatusView != null) vervalStatusView.setText(message);
        });
    }

    private void sendVervalNeedPassword() {
        runOnUiThread(() -> {
            vervalCompleted = true;
            if (webView != null) webView.evaluateJavascript("window.__vervalAutomationNeedPassword && window.__vervalAutomationNeedPassword();", null);
            if (vervalStatusView != null) vervalStatusView.setText("Passcode belum tersimpan. Kembali ke AgriAttend untuk menyimpannya.");
            if (vervalDialog != null) vervalDialog.dismiss();
        });
    }

    private void destroyVervalWebView() {
        if (vervalWebView != null) {
            try { vervalWebView.removeJavascriptInterface("VervalPortalBridge"); vervalWebView.stopLoading(); vervalWebView.loadUrl("about:blank"); vervalWebView.destroy(); } catch (Exception ignored) {}
            vervalWebView = null;
        }
        vervalStatusView = null;
        vervalInjectedUrl = "";
    }

    private class VervalPortalBridge {
        @JavascriptInterface
        public void onStatus(String message) { updateVervalStatus(message); }

        @JavascriptInterface
        public void onError(String message) { sendVervalError(message); }

        @JavascriptInterface
        public void onNeedPassword() { sendVervalNeedPassword(); }

        @JavascriptInterface
        public void onData(String json) {
            runOnUiThread(() -> {
                vervalCompleted = true;
                if (webView != null) webView.evaluateJavascript("window.__vervalAutomationResult && window.__vervalAutomationResult(" + JSONObject.quote(json == null ? "{}" : json) + ");", null);
                if (vervalDialog != null && vervalDialog.isShowing()) vervalDialog.dismiss();
            });
        }
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
        public boolean saveVervalPassword(String password) {
            if (password == null || password.trim().isEmpty() || password.length() > 256) return false;
            return secureWriteValue(VERVAL_PASSWORD_KEY, password);
        }

        @JavascriptInterface
        public boolean hasVervalPassword() {
            return !secureReadValue(VERVAL_PASSWORD_KEY).isEmpty();
        }

        @JavascriptInterface
        public void clearVervalPassword() {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(VERVAL_PASSWORD_KEY).apply();
        }

        @JavascriptInterface
        public void openVervalPortal(String kabupaten, String provinsi) {
            MainActivity.this.openVervalPortal(kabupaten, provinsi);
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
