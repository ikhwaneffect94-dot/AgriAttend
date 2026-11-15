package id.agriattend.app;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Direct read-only client for SIMKOPDES public cooperative asset data.
 * It does not create a portal WebView, submit a passcode, or access protected
 * Verval batch data. Only fields exposed by the public API are returned.
 */
final class SimkopdesPublicApi {
    private static final String SITE = "https://simkopdes.go.id";
    private static final String API = "https://api.simkopdes.go.id/api";
    private static final int CONNECT_TIMEOUT_MS = 25000;
    private static final int READ_TIMEOUT_MS = 120000;
    private static final int MAX_RESPONSE_BYTES = 70 * 1024 * 1024;

    JSONObject fetchAssignedCooperative(String province, String district, String assignmentCode) throws Exception {
        return fetchAssignedCooperative(province, district, assignmentCode, 0, "");
    }

    /** Read-only public lookup. No Verval passcode or SIMKOPDES Bearer token is sent. */
    JSONObject fetchAssignedCooperativeByName(String province, String district,
                                              String subdistrict, String cooperativeName) throws Exception {
        String districtValue = stripAdministrativePrefix(district);
        String subdistrictValue = stripAdministrativePrefix(subdistrict);
        String targetName = cooperativeName == null ? "" : cooperativeName.trim();
        if (province == null || province.trim().isEmpty()
                || districtValue.isEmpty() || subdistrictValue.isEmpty() || targetName.isEmpty()) {
            throw new IllegalArgumentException("Provinsi, kabupaten, kecamatan, dan nama koperasi harus tersedia.");
        }
        String lookupKey = "KDKMP|" + districtValue + "|" + subdistrictValue + "|" + targetName;
        JSONObject result = fetchAssignedCooperative(province.trim(), districtValue, lookupKey, 0, "");
        result.put("requestedSubdistrict", subdistrictValue);
        result.put("publicAssetApi", true);
        result.put("passcodeRequired", false);
        result.put("batchAvailable", false);
        result.put("nonPriorityAvailable", false);
        result.put("individualVervalBatchAvailable", false);
        result.put("verificationScope", "public-asset-records");
        return result;
    }

    /**
     * Read-only lookup combining the public asset tree and aggregate Verval
     * endpoints. If the user has separately supplied an authorized SIMKOPDES
     * Bearer token and their own regional-facilitator id, the facilitator-scoped
     * asset endpoint is queried as an additional source. The shared Verval page
     * passcode is NOT treated as an API token.
     */
    JSONObject fetchAssignedCooperative(String province, String district, String assignmentCode,
                                        int facilitatorId, String facilitatorBearer) throws Exception {
        String target = targetFromAssignment(assignmentCode);
        String subdistrictHint = subdistrictFromAssignment(assignmentCode);
        if (target.isEmpty()) throw new IllegalArgumentException("Nama Kopdes penugasan tidak terbaca dari AgriConnect.");

        // Read the same dashboard bundle entry point used by the working
        // public SIMKOPDES extractor. Dynamic parameters must be tried first:
        // the key embedded in an older uploaded bundle may already be rotated.
        List<CipherParameters> candidates = new ArrayList<>();
        LinkedHashSet<String> seenCipherPairs = new LinkedHashSet<>();
        try {
            candidates.addAll(discoverCipherCandidates());
            for (CipherParameters item : candidates) {
                // Candidates from discovery are already ordered as found in
                // the dashboard bundle; keep that order for validation.
            }
        } catch (Exception discoveryError) {
            // The previously observed public pair remains a compatibility
            // fallback when the dashboard is temporarily unavailable.
        }
        addCipherCandidate("Bf7QmWxYzKpLnRsTuVcHdJgEiAoNpLqR", "Hx49RtWmNpQkSvYb",
                candidates, seenCipherPairs);
        if (candidates.isEmpty()) {
            throw new IllegalStateException("Parameter AES SIMKOPDES tidak ditemukan pada bundle dashboard dan tidak ada parameter cadangan.");
        }
        String apiBearer = facilitatorBearer == null ? "" : facilitatorBearer.trim();
        CipherParameters workingCipher = discoverWorkingCipher(candidates, apiBearer);
        List<CipherParameters> parameters = new ArrayList<>();
        if (workingCipher != null) parameters.add(workingCipher);
        else parameters.addAll(candidates);

        // Primary Verval data comes from the public land-asset tree. Fetch it
        // immediately after cipher validation; optional statistics must never
        // prevent an individual cooperative result from being rendered.
        JSONArray records = new JSONArray();
        String assetApiNote = "";
        StringBuilder endpointNotes = new StringBuilder();
        try {
            JSONObject tree = getDecrypted("/cooperative-assets/get-all", parameters, apiBearer);
            records = extractAssets(tree, province, district, subdistrictHint, target);
        } catch (Exception assetError) {
            assetApiNote = " Data aset individual tidak berhasil ditarik: " + safeMessage(assetError) + ".";
        }

        boolean directoryMatch = false;
        if (records.length() == 0) {
            try {
                JSONObject directory = getDecrypted("/cooperatives/get-all-nested", parameters, apiBearer);
                JSONObject cooperative = findCooperativeRecord(directory, province, district, subdistrictHint, target);
                if (cooperative != null) {
                    JSONObject record = new JSONObject();
                    record.put("name", firstNonEmpty(readName(cooperative.opt("name")), target, target));
                    long cooperativeId = cooperative.optLong("cooperative_id", cooperative.optLong("id", 0));
                    record.put("cooperativeId", cooperativeId > 0 ? cooperativeId : JSONObject.NULL);
                    record.put("assetCount", JSONObject.NULL);
                    record.put("batch", JSONObject.NULL);
                    record.put("priorityType", "");
                    record.put("category", "");
                    record.put("assetStatus", "");
                    record.put("resultStatus", "Koperasi ditemukan pada direktori publik SIMKOPDES. Data bidang aset individual belum tersedia pada endpoint aset publik.");
                    record.put("ownership", "");
                    record.put("surveyor", "");
                    record.put("latitude", JSONObject.NULL);
                    record.put("longitude", JSONObject.NULL);
                    record.put("mapUrl", "");
                    Object latitude = cooperative.opt("latitude");
                    Object longitude = cooperative.opt("longitude");
                    if (isNumber(latitude) && isNumber(longitude)) {
                        double lat = Double.parseDouble(String.valueOf(latitude));
                        double lon = Double.parseDouble(String.valueOf(longitude));
                        if (lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180) {
                            record.put("latitude", lat);
                            record.put("longitude", lon);
                            record.put("mapUrl", "https://www.google.com/maps?q=" + lat + "," + lon);
                        }
                    }
                    record.put("province", province);
                    record.put("district", district);
                    record.put("subdistrict", subdistrictHint);
                    record.put("sourceText", "Direktori koperasi publik SIMKOPDES · data aset individual belum tersedia");
                    records.put(record);
                    directoryMatch = true;
                }
            } catch (Exception directoryError) {
                assetApiNote += " Direktori koperasi publik tidak dapat diperiksa: " + safeMessage(directoryError) + ".";
            }
        }

        // Optional province statistics are fetched only after the primary asset
        // request. The phase-2 route is deliberately omitted: the current server
        // responds HTTP 403 for it, and it is not part of the public extractor's
        // documented bulk-data flow.
        JSONObject verificationStats = optionalDecryptedObject(
                "/statistics/asset-verification-validation", parameters, apiBearer, endpointNotes);
        JSONObject verificationProvince = findVerificationProvince(verificationStats, province);
        JSONArray phase2Rows = new JSONArray();

        JSONArray facilitatorRecords = new JSONArray();
        String facilitatorNote = "";
        if (facilitatorId > 0 && facilitatorBearer != null && !facilitatorBearer.trim().isEmpty()) {
            try {
                JSONObject cooperativeTree = getDecrypted("/cooperatives/get-all-nested", parameters, apiBearer);
                long cooperativeId = findCooperativeId(cooperativeTree, province, district, subdistrictHint, target);
                if (cooperativeId > 0) {
                    Object scoped = getDecryptedPayload(
                            "/cooperative-assets/by-regional-facilitator-id/" + facilitatorId,
                            parameters, facilitatorBearer.trim());
                    facilitatorRecords = extractFacilitatorAssets(scoped, cooperativeId, province, district, target);
                    if (facilitatorRecords.length() > 0) {
                        records = facilitatorRecords;
                        facilitatorNote = "Data aset juga cocok dari endpoint khusus pendamping.";
                    } else {
                        facilitatorNote = "Endpoint khusus pendamping berhasil dipanggil, tetapi tidak mengembalikan aset untuk koperasi penugasan ini.";
                    }
                } else {
                    facilitatorNote = "ID koperasi tidak berhasil dipetakan pada daftar SIMKOPDES; dipakai data aset publik umum.";
                }
            } catch (Exception scopedError) {
                // Keep the public read-only dataset available even when the optional
                // account-scoped route rejects/has an expired token.
                facilitatorNote = "Endpoint khusus pendamping tidak dapat dibaca (" + safeMessage(scopedError) + "); data publik umum tetap dipakai.";
            }
        }

        JSONObject result = new JSONObject();
        boolean hasRegionalStats = verificationProvince != null || phase2Rows.length() > 0;
        result.put("success", records.length() > 0 || hasRegionalStats);
        result.put("records", records);
        result.put("updatedAt", System.currentTimeMillis());
        result.put("source", "API publik SIMKOPDES: cooperative-assets/get-all + cooperatives/get-all-nested; tanpa token");
        result.put("publicAssetApi", true);
        result.put("directoryMatch", directoryMatch);
        result.put("assetRecordCount", directoryMatch && records.length() == 1 ? 0 : records.length());
        result.put("verificationScope", "public-asset-records");
        result.put("passcodeRequired", false);
        result.put("batchAvailable", false);
        result.put("nonPriorityAvailable", false);
        result.put("individualVervalBatchAvailable", false);
        result.put("assetApiNote", assetApiNote);
        result.put("endpointNotes", endpointNotes.toString());
        result.put("verificationStatsAvailable", verificationProvince != null);
        result.put("phase2StatsAvailable", phase2Rows.length() > 0);
        result.put("batchAvailable", false);
        result.put("nonPriorityAvailable", false);
        result.put("passcodeRequired", false);
        result.put("targetName", target);
        result.put("province", province);
        result.put("district", district);
        result.put("verificationStats", verificationProvince == null ? JSONObject.NULL : verificationProvince);
        result.put("phase2Rows", phase2Rows);
        result.put("regionalFacilitatorUsed", facilitatorRecords.length() > 0);
        result.put("regionalFacilitatorNote", facilitatorNote);
        if (records.length() == 0 && hasRegionalStats) {
            result.put("message", "Statistik Verval tingkat provinsi berhasil dibaca, tetapi data aset individual Kopdes penugasan belum ditemukan." + assetApiNote);
        } else if (records.length() == 0) {
            result.put("message", "Nama koperasi tidak ditemukan pada direktori maupun data aset publik SIMKOPDES." + assetApiNote);
        } else if (directoryMatch) {
            result.put("message", "Koperasi ditemukan pada direktori publik, tetapi belum ada baris bidang aset individual yang cocok. Kategori Batch 1–4 dan Non Prioritas tidak tersedia pada API publik." + assetApiNote);
        } else {
            result.put("message", "Data bidang aset publik koperasi berhasil dibaca. API publik tidak menyediakan kategori Batch 1–4 atau Non Prioritas individual." + assetApiNote);
        }
        return result;
    }

    private JSONObject optionalDecryptedObject(String endpoint, List<CipherParameters> candidates, String bearerToken, StringBuilder notes) {
        try {
            Object payload = getDecryptedPayload(endpoint, candidates, bearerToken);
            if (!(payload instanceof JSONObject)) {
                if (notes != null) notes.append(endpoint).append(": respons bukan objek JSON; ");
                return new JSONObject();
            }
            JSONObject object = (JSONObject) payload;
            JSONObject nested = object.optJSONObject("data");
            return nested != null ? nested : object;
        } catch (Exception error) {
            if (notes != null) notes.append(endpoint).append(": ").append(safeMessage(error)).append("; ");
            return new JSONObject();
        }
    }

    /**
     * Validate the discovered AES candidates against the smallest available
     * statistics response first. The previous implementation tried candidates
     * against /cooperative-assets/get-all (a very large response), which made a
     * wrong candidate expensive and hid the useful failure point. The returned
     * candidate must decrypt into a recognisable SIMKOPDES response shape.
     */
    /**
     * Validate candidates against the public cooperative hierarchy endpoint
     * first. The known-good extractor uses this endpoint before the larger
     * asset tree; it has a stable and distinctive response shape (grouped[]).
     * Statistics routes are optional and are checked only after the two bulk
     * public data routes.
     */
    private CipherParameters discoverWorkingCipher(List<CipherParameters> candidates, String bearerToken) throws Exception {
        String[] endpoints = new String[]{
                "/cooperatives/get-all-nested",
                "/cooperative-assets/get-all"
        };
        String lastError = "Tidak ada respons API yang dapat dipakai untuk memvalidasi parameter dekripsi.";
        for (String endpoint : endpoints) {
            try {
                String responseText = httpGetWithRetry(API + endpoint, bearerToken);
                JSONObject envelope = new JSONObject(responseText);
                Object data = envelope.opt("data");
                if (data instanceof JSONObject || data instanceof JSONArray) {
                    if (isExpectedPayload(endpoint, data)) return null;
                    lastError = "Respons " + endpoint + " berupa JSON, tetapi struktur grouped/statistik tidak dikenali.";
                    continue;
                }
                if (!(data instanceof String)) {
                    lastError = "Respons " + endpoint + " tidak memiliki field data (HTTP sukses tetapi envelope tidak sesuai).";
                    continue;
                }

                String encoded = ((String) data).replaceAll("\\s+", "");
                if (encoded.startsWith("{") || encoded.startsWith("[")) {
                    Object parsed = parseJsonPayload(encoded);
                    if (isExpectedPayload(endpoint, parsed)) return null;
                    lastError = "Respons " + endpoint + " berupa JSON teks, tetapi struktur grouped/statistik tidak dikenali.";
                    continue;
                }
                byte[] ciphertext;
                try {
                    ciphertext = Base64.decode(encoded, Base64.DEFAULT);
                } catch (Exception decodeError) {
                    lastError = "Field data pada " + endpoint + " bukan Base64 yang valid.";
                    continue;
                }

                int decryptableButUnexpected = 0;
                String cryptoProblem = "padding/dekripsi tidak cocok";
                for (CipherParameters candidate : candidates) {
                    try {
                        Object parsed = decryptPayload(ciphertext, candidate);
                        if (isExpectedPayload(endpoint, parsed)) return candidate;
                        decryptableButUnexpected++;
                    } catch (Exception decryptError) {
                        if (decryptError.getMessage() != null) cryptoProblem = decryptError.getMessage();
                    }
                }
                if (decryptableButUnexpected > 0) {
                    lastError = "AES berhasil membuka respons " + endpoint + ", tetapi skema JSON tidak sesuai ("
                            + decryptableButUnexpected + " kandidat menghasilkan JSON).";
                } else {
                    lastError = "Tidak ada kandidat AES yang memvalidasi respons " + endpoint
                            + " (" + candidates.size() + " kandidat, ciphertext " + encoded.length()
                            + " karakter; " + cryptoProblem + ").";
                }
            } catch (Exception error) {
                lastError = endpoint + ": " + safeMessage(error);
            }
        }
        throw new IllegalStateException("SIMKOPDES menolak atau belum menerima akses API. " + lastError
                + (bearerToken == null || bearerToken.trim().isEmpty()
                ? " Bila endpoint ini sekarang memerlukan autentikasi, simpan Bearer token API SIMKOPDES yang sah pada bagian Akses API Pendamping. Password AgriConnect dan kode halaman Verval bukan Bearer token."
                : " Periksa apakah Bearer token API SIMKOPDES masih aktif dan memiliki izin baca."));
    }

    private boolean isExpectedPayload(String endpoint, Object parsed) {
        if (!(parsed instanceof JSONObject)) return false;
        JSONObject object = (JSONObject) parsed;
        JSONObject nested = object.optJSONObject("data");
        if (nested != null) object = nested;
        if (endpoint.contains("cooperatives/get-all-nested")) {
            return object.optJSONArray("grouped") != null;
        }
        if (endpoint.contains("cooperative-assets/get-all")) {
            return object.optJSONObject("grouped") != null;
        }
        if (endpoint.contains("asset-verification-validation")) {
            return object.optJSONArray("provinces") != null || object.has("statistic");
        }
        if (endpoint.contains("national/phase-2")) {
            return object.has("national_totals") || object.has("province_distribution")
                    || (object.optJSONObject("data") != null
                    && (object.optJSONObject("data").has("national_totals") || object.optJSONObject("data").has("province_distribution")));
        }
        if (endpoint.contains("by-regional-facilitator-id")) {
            return object.optJSONArray("data") != null || object.optJSONArray("assets") != null
                    || object instanceof JSONObject;
        }
        return false;
    }

    private Object parseJsonPayload(String value) throws Exception {
        String plain = value == null ? "" : value.replaceFirst("^\\uFEFF", "").trim();
        if (plain.startsWith("{")) return new JSONObject(plain);
        if (plain.startsWith("[")) return new JSONArray(plain);
        throw new IllegalStateException("Payload bukan JSON.");
    }

    private Object decryptPayload(byte[] ciphertext, CipherParameters candidate) throws Exception {
        if (candidate == null || candidate.key.length != 32 || candidate.iv.length != 16) {
            throw new IllegalStateException("Parameter AES tidak lengkap.");
        }
        if (ciphertext == null || ciphertext.length == 0 || ciphertext.length % 16 != 0) {
            throw new IllegalStateException("Panjang ciphertext bukan kelipatan blok AES 16 byte.");
        }
        // Match the SIMKOPDES extractor byte-for-byte: AES-256-CBC/NoPadding,
        // followed by explicit PKCS7 removal (the website's CryptoJS Pkcs7).
        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(candidate.key, "AES"), new IvParameterSpec(candidate.iv));
        byte[] padded = cipher.doFinal(ciphertext);
        if (padded.length == 0) throw new IllegalStateException("Hasil dekripsi kosong.");
        // Match the public extractor's PKCS7 behavior exactly: CryptoJS/Python
        // use the final byte as the unpadding length and do not verify every
        // preceding padding byte. This avoids rejecting payloads that the
        // SIMKOPDES browser client accepts.
        int padding = padded[padded.length - 1] & 0xff;
        byte[] plainBytes = (padding >= 1 && padding <= 16 && padding <= padded.length)
                ? Arrays.copyOf(padded, padded.length - padding)
                : padded;
        String plain = new String(plainBytes, StandardCharsets.UTF_8).replaceFirst("^\\uFEFF", "").trim();
        return parseJsonPayload(plain);
    }

    private JSONObject findVerificationProvince(JSONObject response, String province) throws Exception {
        JSONArray rows = response == null ? null : response.optJSONArray("provinces");
        if (rows == null) return null;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row != null && sameRegion(row.optString("province_name", ""), province)) {
                JSONObject copy = new JSONObject(row.toString());
                JSONObject detail = row.optJSONObject("verification_detail");
                copy.put("verification_detail", detail == null ? new JSONObject() : detail);
                return copy;
            }
        }
        return null;
    }

    private JSONArray findPhase2ProvinceRows(JSONObject response, String province) {
        JSONObject data = response == null ? null : response.optJSONObject("data");
        if (data == null) data = response;
        JSONArray rows = data == null ? null : data.optJSONArray("province_distribution");
        JSONArray output = new JSONArray();
        if (rows == null) return output;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row != null && sameRegion(row.optString("province_name", ""), province)) output.put(row);
        }
        return output;
    }

    private JSONObject findCooperativeRecord(JSONObject root, String province, String district,
                                              String subdistrictHint, String target) {
        JSONArray provinces = root == null ? null : root.optJSONArray("grouped");
        if (provinces == null) return null;
        for (int p = 0; p < provinces.length(); p++) {
            JSONObject pNode = provinces.optJSONObject(p);
            if (pNode == null || !sameRegion(pNode.optString("province", ""), province)) continue;
            JSONArray districts = pNode.optJSONArray("districts");
            if (districts == null) continue;
            for (int d = 0; d < districts.length(); d++) {
                JSONObject dNode = districts.optJSONObject(d);
                if (dNode == null || !sameRegion(dNode.optString("district", ""), district)) continue;
                JSONArray subdistricts = dNode.optJSONArray("subdistricts");
                if (subdistricts == null) continue;
                for (int s = 0; s < subdistricts.length(); s++) {
                    JSONObject sNode = subdistricts.optJSONObject(s);
                    if (sNode == null) continue;
                    if (subdistrictHint != null && !subdistrictHint.isEmpty()
                            && !sameRegion(sNode.optString("subdistrict", ""), subdistrictHint)) continue;
                    JSONArray cooperatives = sNode.optJSONArray("cooperatives");
                    if (cooperatives == null) continue;
                    for (int c = 0; c < cooperatives.length(); c++) {
                        JSONObject coop = cooperatives.optJSONObject(c);
                        if (coop != null && sameCooperative(readName(coop.opt("name")), target)) return coop;
                    }
                }
            }
        }
        return null;
    }

    private long findCooperativeId(JSONObject root, String province, String district,
                                    String subdistrictHint, String target) {
        JSONObject cooperative = findCooperativeRecord(root, province, district, subdistrictHint, target);
        return cooperative == null ? 0 : cooperative.optLong("cooperative_id", cooperative.optLong("id", 0));
    }

    private JSONArray extractFacilitatorAssets(Object payload, long cooperativeId,
                                               String province, String district, String target) throws Exception {
        JSONArray rows = payload instanceof JSONArray ? (JSONArray) payload : null;
        if (payload instanceof JSONObject) {
            JSONObject object = (JSONObject) payload;
            rows = object.optJSONArray("data");
            if (rows == null) rows = object.optJSONArray("assets");
        }
        JSONArray output = new JSONArray();
        if (rows == null) return output;
        JSONObject groupedRecord = null;
        int matchedAssets = 0;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject asset = rows.optJSONObject(i);
            if (asset == null || asset.optLong("cooperative_id", 0) != cooperativeId) continue;
            if (groupedRecord == null) groupedRecord = new JSONObject();
            matchedAssets++;
            JSONObject record = new JSONObject();
            record.put("name", target);
            record.put("cooperativeId", cooperativeId);
            record.put("assetId", asset.opt("cooperative_asset_id"));
            record.put("resultStatus", firstNonEmpty(asset.optString("status", ""), asset.optString("asset_status", ""), "Status aset tidak tersedia"));
            record.put("assetStatus", asset.optString("asset_status", ""));
            record.put("ownership", asset.optString("ownership", ""));
            record.put("surveyor", asset.optString("surveyor", ""));
            record.put("address", asset.optString("address", ""));
            record.put("latitude", JSONObject.NULL);
            record.put("longitude", JSONObject.NULL);
            record.put("mapUrl", "");
            Object lat = asset.opt("latitude"), lon = asset.opt("longitude");
            if (isNumber(lat) && isNumber(lon)) {
                double latitude = Double.parseDouble(String.valueOf(lat));
                double longitude = Double.parseDouble(String.valueOf(lon));
                if (latitude >= -90 && latitude <= 90 && longitude >= -180 && longitude <= 180) {
                    record.put("latitude", latitude); record.put("longitude", longitude);
                    record.put("mapUrl", "https://www.google.com/maps?q=" + latitude + "," + longitude);
                }
            }
            record.put("province", province); record.put("district", district);
            record.put("sourceText", "API khusus pendamping SIMKOPDES · cooperative-assets/by-regional-facilitator-id");
            if (groupedRecord.optString("name", "").isEmpty()) {
                groupedRecord = new JSONObject(record.toString());
            } else {
                String currentStatus = groupedRecord.optString("resultStatus", "");
                String nextStatus = record.optString("resultStatus", "");
                if (!nextStatus.isEmpty() && !currentStatus.contains(nextStatus)) {
                    groupedRecord.put("resultStatus", currentStatus + " · " + nextStatus);
                }
                if (groupedRecord.isNull("latitude") && !record.isNull("latitude")) {
                    groupedRecord.put("latitude", record.opt("latitude"));
                    groupedRecord.put("longitude", record.opt("longitude"));
                    groupedRecord.put("mapUrl", record.optString("mapUrl", ""));
                }
            }
        }
        if (groupedRecord != null) {
            groupedRecord.put("assetCount", matchedAssets);
            output.put(groupedRecord);
        }
        return output;
    }

    private static String firstNonEmpty(String first, String second, String fallback) {
        if (first != null && !first.trim().isEmpty()) return first.trim();
        if (second != null && !second.trim().isEmpty()) return second.trim();
        return fallback;
    }

    private static String safeMessage(Exception error) {
        String value = error == null ? "akses ditolak" : error.getMessage();
        if (value == null || value.trim().isEmpty()) return "akses ditolak";
        String safe = value.replaceAll("(?i)(bearer\\s+)\\S+", "$1[disamarkan]");
        return safe.substring(0, Math.min(140, safe.length()));
    }

    /**
     * SIMKOPDES is a Next.js site. Older builds exposed the decrypt call in a
     * dashboard chunk as (0, x.C)(payload, "secret", "iv"). Newer builds can
     * move the decrypt helper into the protected Verval route chunk or change
     * the minified function name. Discover scripts from both routes and collect
     * candidate (secret, IV) pairs; the actual API response is used below to
     * validate candidates before any record is accepted.
     */
    private List<CipherParameters> discoverCipherCandidates() throws Exception {
        // Match the working public extractor: /pers/dashboard is the source
        // of the actual API decrypt helper and its runtime key/IV arguments.
        LinkedHashSet<String> scripts = new LinkedHashSet<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<String> scriptBodies = new ArrayList<>();

        String dashboardHtml = httpGetWithRetry(SITE + "/pers/dashboard");
        for (String piece : dashboardHtml.split("\"")) {
            if (piece.startsWith("/_next/static/chunks/") && piece.endsWith(".js")) {
                scripts.add(piece);
            }
        }

        if (scripts.isEmpty()) {
            Pattern attrScript = Pattern.compile("(?i)(?:src|href)\\s*=\\s*['\\\"]([^'\\\"]+\\.m?js(?:\\?[^'\\\"]*)?)['\\\"]");
            Pattern nextScript = Pattern.compile("['\\\"]([^'\\\"]*_next/static/[^'\\\"]+\\.js(?:\\?[^'\\\"]*)?)['\\\"]");
            Pattern oldChunk = Pattern.compile("['\\\"](/_next/static/chunks/[^'\\\"]+\\.js)['\\\"]");
            collectScripts(attrScript, dashboardHtml, scripts);
            collectScripts(nextScript, dashboardHtml, scripts);
            collectScripts(oldChunk, dashboardHtml, scripts);
        }

        if (scripts.isEmpty()) {
            String[] fallbackPages = new String[]{
                    SITE + "/pers/asset/verval?batch_verification=1%2C2%2C3%2C4",
                    SITE + "/"
            };
            Pattern attrScript = Pattern.compile("(?i)(?:src|href)\\s*=\\s*['\\\"]([^'\\\"]+\\.m?js(?:\\?[^'\\\"]*)?)['\\\"]");
            Pattern nextScript = Pattern.compile("['\\\"]([^'\\\"]*_next/static/[^'\\\"]+\\.js(?:\\?[^'\\\"]*)?)['\\\"]");
            Pattern oldChunk = Pattern.compile("['\\\"](/_next/static/chunks/[^'\\\"]+\\.js)['\\\"]");
            for (String page : fallbackPages) {
                try {
                    String html = httpGetWithRetry(page);
                    collectScripts(attrScript, html, scripts);
                    collectScripts(nextScript, html, scripts);
                    collectScripts(oldChunk, html, scripts);
                } catch (Exception ignored) { }
            }
        }
        if (scripts.isEmpty()) {
            throw new IllegalStateException("Bundle JavaScript SIMKOPDES tidak ditemukan dari /pers/dashboard.");
        }

        int scanned = 0;
        for (String script : scripts) {
            if (scanned++ >= 300) break;
            try { scriptBodies.add(httpGetWithRetry(toSiteUrl(script))); }
            catch (Exception ignored) { }
        }

        // Important: scan ALL chunks for the exact .C)( marker before any
        // generic regex. Vendor chunks may contain unrelated decrypt helpers;
        // accepting one early can hide the real SIMKOPDES key in a later chunk.
        List<CipherParameters> candidates = new ArrayList<>();
        for (String js : scriptBodies) {
            collectLegacyCipherCalls(js, candidates, seen);
            if (!candidates.isEmpty()) return candidates;
        }

        // Only use renamed-helper patterns if the exact public marker has
        // disappeared from the current build.
        Pattern oldCall = Pattern.compile("(?s)\\.([A-Za-z_$][\\w$]*)\\)\\s*\\(\\s*[^,]{1,1200},\\s*['\"]([^'\"]{16,})['\"]\\s*,\\s*['\"]([^'\"]{16})['\"]");
        Pattern namedCall = Pattern.compile("(?is)\\b(?:decrypt|decrRes|decryptResponse|decryptData)\\w*\\s*\\(\\s*[^,]{1,1200},\\s*['\"]([^'\"]{16,})['\"]\\s*,\\s*['\"]([^'\"]{16})['\"]");
        Pattern genericCall = Pattern.compile("(?s)(?:\\(\\s*0\\s*,\\s*[A-Za-z_$][\\w$.$]*\\s*\\)\\s*\\(|\\b[A-Za-z_$][\\w$.$]*\\s*\\()\\s*[^,]{1,1200},\\s*['\"]([^'\"]{16,})['\"]\\s*,\\s*['\"]([^'\"]{16})['\"]");
        for (String js : scriptBodies) {
            collectCandidates(oldCall, js, candidates, seen);
            collectCandidates(namedCall, js, candidates, seen);
            collectCandidates(genericCall, js, candidates, seen);
            if (candidates.size() >= 512) break;
        }
        if (candidates.isEmpty()) {
            throw new IllegalStateException("Bundle dashboard ditemukan, tetapi pemanggilan dekripsi AES .C)( maupun fallback tidak ditemukan.");
        }
        return candidates;
    }

    private void collectLegacyCipherCalls(String js, List<CipherParameters> output, LinkedHashSet<String> seen) throws Exception {
        String marker = ".C)(";
        int at = js.indexOf(marker);
        while (at >= 0 && output.size() < 512) {
            int start = at + marker.length();
            int end = Math.min(js.length(), start + 700);
            String segment = js.substring(start, end);
            String[] parts = segment.split("\"");
            if (parts.length >= 4) addCipherCandidate(parts[1], parts[3], output, seen);
            at = js.indexOf(marker, at + marker.length());
        }
    }

    private void collectScripts(Pattern pattern, String text, LinkedHashSet<String> output) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find() && output.size() < 300) {
            String path = matcher.group(1);
            if (path == null || path.isEmpty()) continue;
            if (path.startsWith("http://") || path.startsWith("https://") || path.startsWith("/")) output.add(path);
            else if (path.startsWith("_next/")) output.add("/" + path);
        }
    }

    private String toSiteUrl(String path) throws Exception {
        if (path.startsWith("https://") || path.startsWith("http://")) {
            URL parsed = new URL(path);
            String host = parsed.getHost().toLowerCase(Locale.ROOT);
            if (!(host.equals("simkopdes.go.id") || host.endsWith(".simkopdes.go.id"))) {
                throw new SecurityException("Bundle SIMKOPDES berada di host yang tidak diizinkan.");
            }
            return path;
        }
        return SITE + (path.startsWith("/") ? path : "/" + path);
    }

    private void collectCandidates(Pattern pattern, String js, List<CipherParameters> output, LinkedHashSet<String> seen) throws Exception {
        Matcher matcher = pattern.matcher(js);
        while (matcher.find() && output.size() < 512) {
            int secretGroup = matcher.groupCount() >= 3 ? 2 : 1;
            int ivGroup = matcher.groupCount() >= 3 ? 3 : 2;
            addCipherCandidate(matcher.group(secretGroup), matcher.group(ivGroup), output, seen);
        }
    }

    private void addCipherCandidate(String secret, String ivText, List<CipherParameters> output,
                                    LinkedHashSet<String> seen) throws Exception {
        if (secret == null || ivText == null || secret.length() < 16) return;
        byte[] iv = ivText.getBytes(StandardCharsets.UTF_8);
        if (iv.length != 16) return;
        String fingerprint = secret + "|" + ivText;
        if (!seen.add(fingerprint)) return;
        byte[] key = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
        // Also compare the derived bytes against existing candidates. The
        // fallback key may be identical to the live bundle key even if it was
        // added through a separate discovery set.
        for (CipherParameters existing : output) {
            if (Arrays.equals(existing.key, key) && Arrays.equals(existing.iv, iv)) return;
        }
        output.add(new CipherParameters(key, iv));
    }

    private JSONObject getDecrypted(String endpoint, List<CipherParameters> candidates, String bearerToken) throws Exception {
        Object value = getDecryptedPayload(endpoint, candidates, bearerToken);
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            JSONObject nested = object.optJSONObject("data");
            return nested != null ? nested : object;
        }
        throw new IllegalStateException("Respons " + endpoint + " bukan objek JSON yang dikenali.");
    }

    private Object getDecryptedPayload(String endpoint, List<CipherParameters> candidates, String bearerToken) throws Exception {
        String responseText = httpGetWithRetry(API + endpoint, bearerToken);
        JSONObject envelope = new JSONObject(responseText);
        Object data = envelope.opt("data");
        if (data instanceof JSONObject || data instanceof JSONArray) return data;
        if (!(data instanceof String)) throw new IllegalStateException("Respons API SIMKOPDES tidak memiliki data yang dikenali.");

        String encoded = ((String) data).replaceAll("\\s+", "");
        if (encoded.startsWith("{") || encoded.startsWith("[")) return parseJsonPayload(encoded);
        byte[] ciphertext = Base64.decode(encoded, Base64.DEFAULT);
        String lastProblem = "Kandidat cipher tidak cocok.";
        for (CipherParameters candidate : candidates) {
            try {
                Object parsed = decryptPayload(ciphertext, candidate);
                if (isExpectedPayload(endpoint, parsed) || parsed instanceof JSONArray) return parsed;
                lastProblem = "JSON terdekripsi tidak memiliki struktur SIMKOPDES yang dikenali.";
            } catch (Exception error) {
                lastProblem = error.getMessage() == null ? lastProblem : error.getMessage();
            }
        }
        throw new IllegalStateException("Payload API SIMKOPDES gagal divalidasi. " + lastProblem);
    }

    private JSONArray extractAssets(JSONObject root, String province, String district, String subdistrictHint, String target) throws Exception {
        JSONObject grouped = root.optJSONObject("grouped");
        JSONArray output = new JSONArray();
        LinkedHashMap<String, JSONObject> byCooperative = new LinkedHashMap<>();
        if (grouped == null) throw new IllegalStateException("Struktur data aset SIMKOPDES berubah: bagian grouped tidak ditemukan.");

        Iterator<String> provinceKeys = grouped.keys();
        while (provinceKeys.hasNext()) {
            String provinceKey = provinceKeys.next();
            if (!sameRegion(provinceKey, province)) continue;
            JSONObject provinceNode = grouped.optJSONObject(provinceKey);
            JSONObject districts = provinceNode == null ? null : provinceNode.optJSONObject("districts");
            if (districts == null) continue;

            Iterator<String> districtKeys = districts.keys();
            while (districtKeys.hasNext()) {
                String districtKey = districtKeys.next();
                if (!sameRegion(districtKey, district)) continue;
                JSONObject districtNode = districts.optJSONObject(districtKey);
                JSONObject subdistricts = districtNode == null ? null : districtNode.optJSONObject("subdistricts");
                if (subdistricts == null) continue;

                Iterator<String> subdistrictKeys = subdistricts.keys();
                while (subdistrictKeys.hasNext()) {
                    String subdistrictKey = subdistrictKeys.next();
                    if (subdistrictHint != null && !subdistrictHint.isEmpty()
                            && !sameRegion(subdistrictKey, subdistrictHint)) continue;
                    JSONObject subdistrictNode = subdistricts.optJSONObject(subdistrictKey);
                    JSONObject villages = subdistrictNode == null ? null : subdistrictNode.optJSONObject("villages");
                    if (villages == null) continue;

                    Iterator<String> villageKeys = villages.keys();
                    while (villageKeys.hasNext()) {
                        String villageKey = villageKeys.next();
                        JSONObject villageNode = villages.optJSONObject(villageKey);
                        JSONArray assets = villageNode == null ? null : villageNode.optJSONArray("assets");
                        if (assets == null) continue;
                        for (int i = 0; i < assets.length(); i++) {
                            JSONObject asset = assets.optJSONObject(i);
                            if (asset == null) continue;
                            String cooperative = readName(asset.opt("cooperative"));
                            if (!sameCooperative(cooperative, target)) continue;
                            JSONObject record = new JSONObject();
                            record.put("name", cooperative);
                            long cooperativeNumericId = asset.optLong("cooperative_id", 0);
                            String cooperativeId = cooperativeNumericId > 0 ? String.valueOf(cooperativeNumericId) : normalize(cooperative);
                            record.put("cooperativeId", cooperativeNumericId > 0 ? cooperativeNumericId : JSONObject.NULL);
                            record.put("assetId", asset.opt("asset_id"));
                            record.put("assetCount", 1);
                            record.put("batch", JSONObject.NULL);
                            String status = readName(asset.opt("status"));
                            record.put("resultStatus", status.isEmpty() ? "Status aset tidak tersedia pada API publik" : status);
                            record.put("assetStatus", readName(asset.opt("asset_status")));
                            record.put("surveyor", readName(asset.opt("surveyor")));
                            Object latitude = asset.opt("latitude");
                            Object longitude = asset.opt("longitude");
                            if (isNumber(latitude) && isNumber(longitude)) {
                                double lat = Double.parseDouble(String.valueOf(latitude));
                                double lon = Double.parseDouble(String.valueOf(longitude));
                                if (lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180) {
                                    record.put("latitude", lat);
                                    record.put("longitude", lon);
                                    record.put("mapUrl", "https://www.google.com/maps?q=" + lat + "," + lon);
                                } else {
                                    record.put("latitude", JSONObject.NULL);
                                    record.put("longitude", JSONObject.NULL);
                                    record.put("mapUrl", "");
                                }
                            } else {
                                record.put("latitude", JSONObject.NULL);
                                record.put("longitude", JSONObject.NULL);
                                record.put("mapUrl", "");
                            }
                            record.put("province", provinceKey);
                            record.put("district", districtKey);
                            record.put("subdistrict", subdistrictKey);
                            record.put("village", villageKey);
                            record.put("sourceText", "Data aset publik SIMKOPDES · " + provinceKey + " · " + districtKey + " · " + subdistrictKey + " · " + villageKey);
                            JSONObject existing = byCooperative.get(cooperativeId);
                            if (existing == null) {
                                byCooperative.put(cooperativeId, record);
                            } else {
                                existing.put("assetCount", existing.optInt("assetCount", 1) + 1);
                                String currentStatus = existing.optString("resultStatus", "");
                                if (!status.isEmpty() && !currentStatus.contains(status)) existing.put("resultStatus", currentStatus + " · " + status);
                                if (existing.isNull("latitude") && !record.isNull("latitude")) {
                                    existing.put("latitude", record.opt("latitude"));
                                    existing.put("longitude", record.opt("longitude"));
                                    existing.put("mapUrl", record.optString("mapUrl", ""));
                                }
                            }
                        }
                    }
                }
            }
        }
        for (JSONObject cooperativeRecord : byCooperative.values()) output.put(cooperativeRecord);
        return output;
    }

    private String httpGet(String address) throws Exception {
        return httpGet(address, "");
    }

    private String httpGet(String address, String bearerToken) throws Exception {
        URL url = new URL(address);
        String host = url.getHost().toLowerCase(Locale.ROOT);
        if (!(host.equals("simkopdes.go.id") || host.endsWith(".simkopdes.go.id"))) {
            throw new SecurityException("Host data SIMKOPDES tidak diizinkan.");
        }
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        boolean apiHost = "api.simkopdes.go.id".equals(host);
        connection.setRequestProperty("Accept", apiHost ? "application/json" : "text/html,application/javascript,*/*");
        connection.setRequestProperty("User-Agent", "AgriAttend-Android/1.6.5 (SIMKOPDES read-only integration)");
        if (apiHost) {
            connection.setRequestProperty("Origin", SITE);
            connection.setRequestProperty("Referer", SITE + "/pers/dashboard");
        }
        if (bearerToken != null && !bearerToken.trim().isEmpty()) {
            if (!"api.simkopdes.go.id".equals(host)) throw new SecurityException("Bearer token hanya boleh dikirim ke API SIMKOPDES resmi.");
            connection.setRequestProperty("Authorization", "Bearer " + bearerToken.trim());
        }
        try {
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String text = readLimited(stream);
            if (status < 200 || status >= 300) throw new IllegalStateException("SIMKOPDES HTTP " + status + " pada " + url.getPath() + (status == 403 ? " (Forbidden: server menolak akses API sebelum proses dekripsi)." : "") + (text.isEmpty() ? "" : ": " + text.substring(0, Math.min(180, text.length()))));
            return text;
        } finally {
            connection.disconnect();
        }
    }

    private String httpGetWithRetry(String address) throws Exception {
        return httpGetWithRetry(address, "");
    }

    private String httpGetWithRetry(String address, String bearerToken) throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            try {
                return httpGet(address, bearerToken);
            } catch (Exception error) {
                last = error;
                if (attempt < 3) {
                    try { Thread.sleep(400L * (attempt + 1)); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw interrupted;
                    }
                }
            }
        }
        throw last == null ? new IllegalStateException("Permintaan SIMKOPDES gagal.") : last;
    }

    private String readLimited(InputStream input) throws Exception {
        if (input == null) return "";
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16384];
            int count;
            int total = 0;
            while ((count = in.read(buffer)) != -1) {
                total += count;
                if (total > MAX_RESPONSE_BYTES) throw new IllegalStateException("Respons SIMKOPDES melebihi batas aman 70 MB.");
                out.write(buffer, 0, count);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String targetFromAssignment(String assignment) {
        String raw = assignment == null ? "" : assignment.trim();
        if (raw.isEmpty()) return "";
        String[] parts = raw.split("[_|,;]+");
        if (parts.length >= 3 && (parts[0].equalsIgnoreCase("KDKMP") || parts[0].equalsIgnoreCase("KDMP"))) {
            return parts[parts.length - 1].trim();
        }
        return raw.replace('_', ' ').trim();
    }

    private static String subdistrictFromAssignment(String assignment) {
        if (assignment == null || assignment.trim().isEmpty()) return "";
        String[] parts = assignment.trim().split("[_|,;]+");
        if (parts.length >= 4 && (parts[0].equalsIgnoreCase("KDKMP") || parts[0].equalsIgnoreCase("KDMP"))) {
            return parts[parts.length - 2].trim();
        }
        return "";
    }

    private static boolean sameRegion(String left, String right) {
        String prefixes = "\\b(kabupaten|kab|kota|administrasi|provinsi|prov|kecamatan|kec|district|subdistrict)\\b";
        String a = normalize(left).replaceAll(prefixes, " ").replaceAll("\\s+", " ").trim();
        String b = normalize(right).replaceAll(prefixes, " ").replaceAll("\\s+", " ").trim();
        return !a.isEmpty() && a.equals(b);
    }

    private static String stripAdministrativePrefix(String value) {
        return normalize(value == null ? "" : value)
                .replaceAll("^(kabupaten|kab|kota|administrasi|provinsi|prov|kecamatan|kec|district|subdistrict)\\s*", "")
                .replaceAll("\\s+", " ").trim();
    }

    private static boolean sameCooperative(String cooperative, String target) {
        String a = normalize(cooperative).replaceAll("\\b(koperasi|desa|kelurahan|merah|putih|kdkmp|kdmp|kopdes)\\b", " ").replaceAll("\\s+", " ").trim();
        String b = normalize(target).replaceAll("\\b(koperasi|desa|kelurahan|merah|putih|kdkmp|kdmp|kopdes)\\b", " ").replaceAll("\\s+", " ").trim();
        return !a.isEmpty() && a.equals(b);
    }

    private static String normalize(String value) {
        String decomposed = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim().replaceAll("\\s+", " ");
    }

    private static String readName(Object value) {
        if (value == null || value == JSONObject.NULL) return "";
        if (value instanceof JSONObject) {
            JSONObject obj = (JSONObject) value;
            for (String key : new String[]{"name", "cooperative_name", "nama", "label", "status_name"}) {
                String candidate = obj.optString(key, "").trim();
                if (!candidate.isEmpty()) return candidate;
            }
            return obj.toString();
        }
        return String.valueOf(value).trim();
    }

    private static boolean isNumber(Object value) {
        if (value == null || value == JSONObject.NULL) return false;
        try { Double.parseDouble(String.valueOf(value)); return true; }
        catch (Exception ignored) { return false; }
    }

    private static final class CipherParameters {
        final byte[] key;
        final byte[] iv;
        CipherParameters(byte[] key, byte[] iv) { this.key = key; this.iv = iv; }
    }
}
