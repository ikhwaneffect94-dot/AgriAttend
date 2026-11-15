package id.agriattend.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * Headless SIMKOPDES Verval workflow runner.
 *
 * The official portal runs in an invisible, 1x1 WebView only as a transport
 * and page-runtime. No portal UI is shown to the user. Region selection stays
 * in AgriAttend; the supplied Verval passcode is entered into the official
 * page's own form, and the resulting table is parsed back into AgriAttend.
 */
public final class SimkopdesVervalActivity extends Activity {
    private static final String PORTAL_URL =
            "https://simkopdes.go.id/pers/asset/verval?batch_verification=1%2C2%2C3%2C4";
    private static final String EXTRA_PROVINCE = "province";
    private static final String EXTRA_DISTRICT = "district";
    private static final String EXTRA_ASSIGNMENT = "assignment";

    private WebView webView;
    private ProgressBar progressBar;
    private TextView statusText;
    private boolean completed = false;
    private boolean automationStarted = false;
    private volatile boolean passcodeSubmitted = false;
    private String province = "";
    private String district = "";
    private String assignment = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        province = clean(getIntent().getStringExtra(EXTRA_PROVINCE));
        district = clean(getIntent().getStringExtra(EXTRA_DISTRICT));
        assignment = clean(getIntent().getStringExtra(EXTRA_ASSIGNMENT));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(22), dp(28), dp(22), dp(24));
        root.setBackgroundColor(Color.rgb(245, 248, 246));

        TextView title = new TextView(this);
        title.setText("Mengambil Data Verval");
        title.setTextColor(Color.rgb(24, 65, 52));
        title.setTextSize(21);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView region = new TextView(this);
        region.setText(province + " · " + district);
        region.setTextColor(Color.rgb(82, 101, 94));
        region.setTextSize(14);
        region.setGravity(Gravity.CENTER);
        region.setPadding(0, dp(10), 0, dp(16));
        root.addView(region, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        progressBar = new ProgressBar(this);
        root.addView(progressBar, new LinearLayout.LayoutParams(dp(44), dp(44)));

        statusText = new TextView(this);
        statusText.setText("Menghubungkan ke layanan Verval SIMKOPDES secara internal…");
        statusText.setTextColor(Color.rgb(65, 81, 74));
        statusText.setTextSize(14);
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(0, dp(18), 0, dp(14));
        root.addView(statusText, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView note = new TextView(this);
        note.setText("Halaman SIMKOPDES tidak ditampilkan. AgriAttend menjalankan alur wilayah dan passcode di latar belakang, lalu mengambil tabel hasilnya.");
        note.setTextColor(Color.rgb(115, 128, 121));
        note.setTextSize(12);
        note.setGravity(Gravity.CENTER);
        root.addView(note, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // This WebView is attached so the page's JavaScript and DOM can run,
        // but it is never visible or touchable.
        webView = new WebView(this);
        webView.setAlpha(0f);
        webView.setVisibility(View.INVISIBLE);
        webView.setClickable(false);
        webView.setFocusable(false);
        webView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        webView.setBackgroundColor(Color.TRANSPARENT);
        webView.addJavascriptInterface(new PortalBridge(), "AgriVervalBridge");

        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setDatabaseEnabled(true);
        webView.getSettings().setJavaScriptCanOpenWindowsAutomatically(false);
        webView.getSettings().setSupportMultipleWindows(false);
        webView.getSettings().setAllowFileAccess(false);
        webView.getSettings().setAllowContentAccess(false);
        webView.getSettings().setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            cookies.setAcceptThirdPartyCookies(webView, true);
        }

        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                updateStatus("Memuat formulir Verval SIMKOPDES…");
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (completed || automationStarted) return;
                automationStarted = true;
                updateStatus("Halaman siap. Memilih wilayah dan menjalankan validasi passcode…");
                view.postDelayed(() -> {
                    if (!completed && webView != null) {
                        webView.evaluateJavascript(buildAutomationScript(), value -> {
                            // The page script reports progress/results through PortalBridge.
                        });
                    }
                }, 1200);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request != null && request.isForMainFrame()) {
                    fail("Portal SIMKOPDES gagal dimuat. Periksa koneksi atau apakah portal sedang tersedia.");
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                            android.webkit.WebResourceResponse response) {
                if (request != null && request.isForMainFrame() && response != null
                        && response.getStatusCode() >= 400) {
                    fail("Portal SIMKOPDES menolak halaman Verval (HTTP "
                            + response.getStatusCode() + ").");
                }
            }
        });

        root.addView(webView, new LinearLayout.LayoutParams(dp(1), dp(1)));
        setContentView(root);

        if (province.isEmpty() || district.isEmpty() || assignment.isEmpty()) {
            fail("Provinsi, kabupaten/kota, atau nama Kopdes penugasan belum lengkap.");
            return;
        }

        webView.loadUrl(PORTAL_URL);
        webView.postDelayed(() -> {
            if (!completed) fail("Waktu tunggu portal Verval habis sebelum tabel hasil diterima.");
        }, 60000);
    }

    private String buildAutomationScript() {
        String passcode = getVervalPasscode();
        return String.format(Locale.ROOT, """
        (async function(){
          const province = %s;
          const district = %s;
          const assignment = %s;
          const passcode = %s;
          const bridge = window.AgriVervalBridge;
          const norm = value => String(value || '').normalize('NFD')
            .replace(/[\\u0300-\\u036f]/g,'').toLowerCase()
            .replace(/\\b(provinsi|province|kabupaten|kab\\.?|kota administrasi|kota|regency|city)\\b/g,' ')
            .replace(/[^a-z0-9]+/g,' ').trim().replace(/\\s+/g,' ');
          const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
          const report = message => { try { bridge.onStatus(String(message || '')); } catch(_){} };
          const visible = el => !!(el && (el.offsetWidth || el.offsetHeight || el.getClientRects().length));
          const snapshot = () => ({
            url: location.href,
            title: document.title,
            text: (document.body && document.body.innerText || '').replace(/\\s+/g,' ').slice(0,1600),
            selects: Array.from(document.querySelectorAll('select')).slice(0,20).map(s=>({
              id:s.id, name:s.name, aria:s.getAttribute('aria-label'),
              label:s.labels && Array.from(s.labels).map(x=>x.innerText).join(' '),
              options:Array.from(s.options).slice(0,25).map(o=>o.textContent.trim())
            })),
            inputs: Array.from(document.querySelectorAll('input')).slice(0,20).map(x=>({
              id:x.id,name:x.name,type:x.type,placeholder:x.placeholder,
              aria:x.getAttribute('aria-label')
            })),
            buttons: Array.from(document.querySelectorAll('button,[role=button],input[type=submit]')).slice(0,30).map(x=>(x.innerText||x.value||x.getAttribute('aria-label')||'').trim()).filter(Boolean)
          });
          function optionFor(select,target){
            const exact = Array.from(select.options).find(o=>norm(o.textContent)===norm(target));
            if(exact) return exact;
            return Array.from(select.options).find(o=>norm(o.textContent).includes(norm(target)) || norm(target).includes(norm(o.textContent)));
          }
          function setSelect(select,target){
            const option=optionFor(select,target);
            if(!option) return false;
            const setter=Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype,'value').set;
            setter.call(select,option.value);
            select.dispatchEvent(new Event('input',{bubbles:true}));
            select.dispatchEvent(new Event('change',{bubbles:true}));
            select.dispatchEvent(new Event('blur',{bubbles:true}));
            return true;
          }
          function textOf(el){return String(el.innerText||el.textContent||el.value||el.getAttribute('aria-label')||'').trim();}
          function visibleSelects(){return Array.from(document.querySelectorAll('select')).filter(visible);}
          function tryCustomOption(target){
            const candidates=Array.from(document.querySelectorAll('[role=option],option,li,button,[data-value]'))
              .filter(visible)
              .filter(el=>norm(textOf(el))===norm(target) || norm(textOf(el)).includes(norm(target)));
            if(candidates.length){candidates[0].click();return true;}
            return false;
          }
          async function chooseRegion(target,kind,exclude){
            for(let attempt=0;attempt<24;attempt++){
              const selects=visibleSelects();
              const select=selects.find(s=>s!==exclude && optionFor(s,target));
              if(select && setSelect(select,target)) return {select,method:'select'};
              const pattern=kind==='province'?/provinsi|province/i:/kabupaten|kab\\.?|kota|regency|city/i;
              const controls=Array.from(document.querySelectorAll('[role=combobox],input,button,[aria-haspopup=listbox]')).filter(visible);
              const control=controls.find(el=>{
                if(el===exclude) return false;
                const meta=[el.id,el.name,el.placeholder,el.getAttribute('aria-label'),
                  el.getAttribute('data-placeholder'),el.parentElement && el.parentElement.innerText].join(' ');
                return pattern.test(meta);
              });
              if(control){
                control.click();
                await sleep(250);
                if(tryCustomOption(target)) return {select:control,method:'custom'};
              }
              await sleep(350);
            }
            return null;
          }
          async function run(){
            report('Mencari pilihan provinsi pada form resmi…');
            const p = await chooseRegion(province,'province',null);
            if(!p){
              bridge.onComplete(JSON.stringify({success:false,message:'Pilihan provinsi pada form SIMKOPDES tidak terdeteksi.',diagnostics:snapshot()}));
              return;
            }
            report('Provinsi terpilih. Memuat pilihan kabupaten/kota…');
            await sleep(900);
            const d = await chooseRegion(district,'district',p.select);
            if(!d){
              bridge.onComplete(JSON.stringify({success:false,message:'Pilihan kabupaten/kota tidak terdeteksi setelah provinsi dipilih.',diagnostics:snapshot()}));
              return;
            }
            report('Kabupaten/kota terpilih. Mengisi passcode Verval secara otomatis…');
            await sleep(450);
            const passInput = Array.from(document.querySelectorAll('input,textarea')).filter(visible).find(el=>{
              const meta=[el.type,el.id,el.name,el.placeholder,el.getAttribute('aria-label'),el.getAttribute('autocomplete')].join(' ');
              return el.type==='password' || /passcode|passkey|password|kode.?akses|kata.?sandi|pin/i.test(meta);
            });
            if(!passInput){
              bridge.onComplete(JSON.stringify({success:false,message:'Kolom passcode tidak ditemukan setelah wilayah dipilih.',diagnostics:snapshot()}));
              return;
            }
            const proto=passInput.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;
            const valueSetter=Object.getOwnPropertyDescriptor(proto,'value').set;
            valueSetter.call(passInput,passcode);
            passInput.dispatchEvent(new Event('input',{bubbles:true}));
            passInput.dispatchEvent(new Event('change',{bubbles:true}));
            passInput.dispatchEvent(new Event('blur',{bubbles:true}));
            await sleep(250);

            const buttons=Array.from(document.querySelectorAll('button,[role=button],input[type=submit]')).filter(visible);
            const priorities=[
              /verifikasi|validasi/i,
              /cek.*(data|verval)|tampilkan.*(data|hasil)|lihat.*(data|hasil)/i,
              /lanjut|submit|cari/i
            ];
            let action=null;
            for(const pattern of priorities){
              action=buttons.find(el=>pattern.test(textOf(el)));
              if(action) break;
            }
            if(action){action.click();}
            else {
              const form=passInput.closest('form');
              if(form && typeof form.requestSubmit==='function') form.requestSubmit();
              else if(form) form.submit();
              else {
                bridge.onComplete(JSON.stringify({success:false,message:'Passcode sudah terisi, tetapi tombol verifikasi tidak ditemukan.',diagnostics:snapshot()}));
                return;
              }
            }

            bridge.onPasscodeSubmitted();
            report('Passcode dikirim. Menunggu tabel hasil Verval…');
            for(let attempt=0;attempt<40;attempt++){
              await sleep(500);
              const tables=Array.from(document.querySelectorAll('table,[role=table]')).filter(visible);
              const rows=tables.flatMap(table=>Array.from(table.querySelectorAll('tr,[role=row]')));
              const dataRows=rows.filter(row=>row.querySelector('td,[role=cell]'));
              if(dataRows.length){
                const records=[];
                for(const table of tables){
                  const rowEls=Array.from(table.querySelectorAll('tr,[role=row]'));
                  if(rowEls.length<2) continue;
                  let headerRow=rowEls.find(row=>row.querySelector('th,[role=columnheader]')) || rowEls[0];
                  const headers=Array.from(headerRow.querySelectorAll('th,td,[role=columnheader]')).map(textOf);
                  for(const row of rowEls){
                    if(row===headerRow) continue;
                    const cells=Array.from(row.querySelectorAll('td,[role=cell]')).map(textOf);
                    if(!cells.length) continue;
                    const raw={};
                    headers.forEach((h,i)=>{if(h)raw[h]=cells[i]||'';});
                    const find=(pattern)=>{const index=headers.findIndex(h=>pattern.test(norm(h)));return index>=0?cells[index]||'':'';};
                    const name=find(/nama.*(koperasi|kopdes)|koperasi|kopdes/);
                    if(!name && !cells.some(Boolean)) continue;
                    const status=find(/status|hasil|verifikasi|verval|kelayakan/);
                    const batch=find(/batch|tahap/);
                    const owner=find(/kepemilikan|pemilik|status.*tanah|hak.*tanah/);
                    const surveyor=find(/surveyor|enumerator|petugas/);
                    const latitude=find(/latitude|lintang/);
                    const longitude=find(/longitude|bujur/);
                    const coord=find(/koordinat|coordinate|lokasi/);
                    let lat=latitude?Number(latitude):null, lng=longitude?Number(longitude):null;
                    if((!Number.isFinite(lat)||!Number.isFinite(lng)) && coord){
                      const pair=coord.match(/(-?\\d+(?:[.,]\\d+)?)\\s*[,; ]+\\s*(-?\\d+(?:[.,]\\d+)?)/);
                      if(pair){lat=Number(pair[1].replace(',','.'));lng=Number(pair[2].replace(',','.'));}
                    }
                    records.push({
                      name:name || cells[0] || '',
                      batch:batch || null,
                      resultStatus:status || 'Status Verval tersedia pada tabel resmi',
                      assetCount:1,
                      latitude:Number.isFinite(lat)?lat:null,
                      longitude:Number.isFinite(lng)?lng:null,
                      ownership:owner,
                      surveyor,
                      assetStatus:status,
                      sourceText:cells.join(' | '),
                      rawFields:raw
                    });
                    if(records.length>=500) break;
                  }
                  if(records.length>=500) break;
                }
                if(records.length){
                  bridge.onComplete(JSON.stringify({
                    success:true,records:records,province:province,district:district,
                    source:'Halaman Verval SIMKOPDES · tabel resmi',
                    updatedAt:new Date().toISOString(),tableCount:tables.length,
                    rowCount:records.length
                  }));
                  return;
                }
              }
              const pageText=(document.body&&document.body.innerText||'').replace(/\\s+/g,' ');
              if(/passcode|passkey|kode akses|kata sandi/i.test(pageText) && /salah|tidak valid|invalid|ditolak/i.test(pageText)){
                bridge.onComplete(JSON.stringify({success:false,message:'Passcode Verval tidak diterima oleh SIMKOPDES.',diagnostics:snapshot()}));
                return;
              }
              if(/akses ditolak|forbidden|unauthorized|tidak memiliki akses/i.test(pageText)){
                bridge.onComplete(JSON.stringify({success:false,message:'SIMKOPDES menolak akses setelah passcode dikirim.',diagnostics:snapshot()}));
                return;
              }
            }
            bridge.onComplete(JSON.stringify({
              success:false,message:'Passcode dan wilayah sudah dikirim, tetapi tabel hasil tidak muncul dalam batas waktu.',
              diagnostics:snapshot()
            }));
          }
          run().catch(error=>{
            bridge.onComplete(JSON.stringify({success:false,message:'Otomasi alur Verval gagal: '+(error.message||error),diagnostics:snapshot()}));
          });
        })();
        """,
                JSONObject.quote(province),
                JSONObject.quote(district),
                JSONObject.quote(assignment),
                JSONObject.quote(passcode));
    }

    private String getVervalPasscode() {
        // User-configured shared Verval passcode; keep it out of logs and UI.
        return new String(new char[]{'2','1','0','4','2','0','2','5'});
    }

    private final class PortalBridge {
        @JavascriptInterface
        public void onStatus(String message) {
            updateStatus(clean(message));
        }

        @JavascriptInterface
        public void onPasscodeSubmitted() {
            passcodeSubmitted = true;
        }

        @JavascriptInterface
        public void onComplete(String json) {
            runOnUiThread(() -> complete(json));
        }
    }

    private void updateStatus(String text) {
        runOnUiThread(() -> {
            if (!completed && statusText != null) statusText.setText(text);
        });
    }

    private void complete(String json) {
        if (completed) return;
        completed = true;
        try {
            JSONObject result = new JSONObject(json == null ? "{}" : json);
            if (!result.has("success")) result.put("success", false);
            result.put("requestedProvince", province);
            result.put("requestedDistrict", district);
            result.put("assignment", assignment);
            result.put("passcodeSubmitted", passcodeSubmitted);
            Intent data = new Intent();
            data.putExtra("verval_result_json", result.toString());
            setResult(RESULT_OK, data);
        } catch (Exception error) {
            Intent data = new Intent();
            data.putExtra("verval_result_json", new JSONObject()
                    .toString());
            setResult(RESULT_OK, data);
        }
        finish();
    }

    private void fail(String message) {
        if (completed) return;
        completed = true;
        try {
            JSONObject result = new JSONObject();
            result.put("success", false);
            result.put("message", message);
            result.put("province", province);
            result.put("district", district);
            result.put("assignment", assignment);
            result.put("records", new JSONArray());
            Intent data = new Intent();
            data.putExtra("verval_result_json", result.toString());
            setResult(RESULT_OK, data);
        } catch (Exception ignored) {
            setResult(RESULT_CANCELED);
        }
        finish();
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onBackPressed() {
        fail("Pengambilan data Verval dibatalkan.");
    }

    @Override
    protected void onPause() {
        if (webView != null) webView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.removeJavascriptInterface("AgriVervalBridge");
            webView.loadUrl("about:blank");
            webView.removeAllViews();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
