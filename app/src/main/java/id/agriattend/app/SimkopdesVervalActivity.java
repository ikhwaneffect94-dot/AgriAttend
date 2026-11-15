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
    private static final String PORTAL_URL = "https://simkopdes.go.id/pers/asset/verval";
    private static final String EXTRA_PROVINCE = "province";
    private static final String EXTRA_REGENCY = "regency";
    private static final String EXTRA_SUBDISTRICT = "subdistrict";
    private static final String EXTRA_ASSIGNMENT = "assignment";
    private static final String EXTRA_SEARCH_NAME = "search_name";

    private WebView webView;
    private ProgressBar progressBar;
    private TextView statusText;
    private boolean completed = false;
    private boolean automationStarted = false;
    private volatile boolean passcodeSubmitted = false;
    private String province = "";
    private String regency = "";
    private String subdistrict = "";
    private String assignment = "";
    private String searchName = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        province = clean(getIntent().getStringExtra(EXTRA_PROVINCE));
        regency = clean(getIntent().getStringExtra(EXTRA_REGENCY));
        subdistrict = clean(getIntent().getStringExtra(EXTRA_SUBDISTRICT));
        assignment = clean(getIntent().getStringExtra(EXTRA_ASSIGNMENT));
        searchName = clean(getIntent().getStringExtra(EXTRA_SEARCH_NAME));

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
        region.setText(province + " · " + regency + " · Kecamatan " + subdistrict);
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
        // Keep a real viewport so the portal's responsive form and table use
        // the same layout dimensions as a normal device browser. The WebView
        // remains visually transparent and cannot receive user interaction.
        webView.setAlpha(0f);
        webView.setVisibility(View.VISIBLE);
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
                }, 2200);
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

        root.addView(webView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        if (province.isEmpty() || regency.isEmpty() || subdistrict.isEmpty() || assignment.isEmpty() || searchName.isEmpty()) {
            fail("Provinsi, kabupaten, kecamatan, atau nama koperasi penugasan belum lengkap.");
            return;
        }

        webView.loadUrl(PORTAL_URL);
        webView.postDelayed(() -> {
            if (!completed) fail("Waktu tunggu 60 detik habis. Portal tidak menyelesaikan pemuatan data. Coba lagi; proses tidak dibiarkan berputar tanpa batas.");
        }, 90000);
    }

    private String buildAutomationScript() {
        String passcode = getVervalPasscode();
        return String.format(Locale.ROOT, """
        (async function(){
          const province = %s;
          const regency = %s;
          const subdistrict = %s;
          const assignment = %s;
          const assignmentSearchName = %s;
          const passcode = %s;
          const bridge = window.AgriVervalBridge;
          const norm = value => String(value || '').normalize('NFD')
            .replace(/[\\u0300-\\u036f]/g,'').toLowerCase()
            .replace(/\\b(provinsi|province|kabupaten|kab\\.?|kota administrasi|kota|regency|city|kecamatan|kec\\.?|district|subdistrict)\\b/g,' ')
            .replace(/[^a-z0-9]+/g,' ').trim().replace(/\\s+/g,' ');
          const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
          const report = message => { try { bridge.onStatus(String(message || '')); } catch(_){} };
          const visible = el => !!(el && (el.offsetWidth || el.offsetHeight || el.getClientRects().length));
          const snapshot = () => {
            const categoryPattern = /\\bbatch\\s*[-:#]?\\s*[1-4]\\b|non[\\s_-]*prioritas|^prioritas$/i;
            const visibleNodes = Array.from(document.querySelectorAll('[role=tab],button,[role=button],a,li,.ant-tabs-tab,.ant-radio-button-wrapper,.ant-segmented-item'))
              .filter(el=>visible(el))
              .map(el=>String(el.innerText||el.textContent||el.getAttribute('aria-label')||'').replace(/\\s+/g,' ').trim())
              .filter(value=>value && value.length<=100);
            return {
              url: location.href,
              title: document.title,
              readyState: document.readyState,
              dropdownCandidates: Array.from(document.querySelectorAll('select,[role=combobox],input[readonly],input[aria-haspopup],button,[aria-haspopup],[class*=select],[class*=dropdown]')).filter(visible).slice(0,35).map(el=>({tag:el.tagName,role:el.getAttribute('role'),id:el.id,name:el.name,placeholder:el.getAttribute('placeholder'),aria:el.getAttribute('aria-label'),text:String(el.innerText||el.textContent||'').replace(/\\s+/g,' ').trim().slice(0,100),className:String(el.className||'').slice(0,100)})),
              requestedProvince:province,
              requestedRegency:regency,
              requestedSubdistrict:subdistrict,
              assignmentSearchName:assignmentSearchName,
              text: (document.body && document.body.innerText || '').replace(/\\s+/g,' ').slice(0,1800),
              selects: Array.from(document.querySelectorAll('select')).slice(0,25).map(s=>({
                id:s.id, name:s.name, aria:s.getAttribute('aria-label'),
                label:s.labels && Array.from(s.labels).map(x=>x.innerText).join(' '),
                options:Array.from(s.options).slice(0,40).map(o=>o.textContent.trim())
              })),
              inputs: Array.from(document.querySelectorAll('input,textarea')).slice(0,25).map(x=>({
                id:x.id,name:x.name,type:x.type,placeholder:x.placeholder,
                aria:x.getAttribute('aria-label')
              })),
              buttons: Array.from(document.querySelectorAll('button,[role=button],input[type=submit]')).slice(0,50).map(x=>(x.innerText||x.value||x.getAttribute('aria-label')||'').trim()).filter(Boolean),
              tabs: visibleNodes.filter(value=>/\\b(batch|prioritas)\\b/i.test(value)).slice(0,40),
              categoryControls: visibleNodes.filter(value=>categoryPattern.test(value)).slice(0,40)
            };
          };
          function optionFor(select,target){
            const wanted=norm(target);
            if(!wanted || wanted.length<3) return null;
            const options=Array.from(select.options).map(option=>({option,label:norm(option.textContent)}))
              .filter(item=>item.label.length>=3);
            const exact=options.find(item=>item.label===wanted);
            if(exact) return exact.option;
            if(wanted.length<5) return null;
            const partial=options.filter(item=>item.label.includes(wanted))
              .sort((a,b)=>a.label.length-b.label.length)[0];
            return partial?partial.option:null;
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
          function regionKindPattern(kind){
            return kind==='province'?/provinsi|province/i:
              kind==='regency'?/kabupaten|kab\\.?|kota|regency|city/i:/kecamatan|kec\\.?|subdistrict/i;
          }
          function regionControlMeta(element){
            const labelIds=String(element.getAttribute('aria-labelledby')||'').split(/\\s+/)
              .map(id=>document.getElementById(id)?.innerText||'').join(' ');
            const labels=element.labels?Array.from(element.labels).map(label=>label.innerText||'').join(' '):'';
            const ancestors=[];
            let parent=element.parentElement;
            for(let depth=0;parent&&depth<4;depth++,parent=parent.parentElement){
              const text=String(parent.innerText||'').replace(/\\s+/g,' ').trim();
              if(text&&text.length<=240&&!ancestors.includes(text))ancestors.push(text);
            }
            const fieldText=ancestors.join(' · ').slice(0,360);
            const own=[element.id,element.name,element.type,element.placeholder,textOf(element),
              element.getAttribute('aria-label'),element.getAttribute('data-placeholder'),
              element.getAttribute('title'),element.getAttribute('data-testid'),
              element.getAttribute('aria-controls'),element.getAttribute('aria-autocomplete'),
              element.className,labelIds,labels].filter(Boolean).join(' ');
            return {own:own,field:fieldText,all:own+' '+fieldText};
          }
          function isGlobalSearchControl(element,meta,regionPattern){
            const searchPattern=/search|cari|pencarian|filter|keyword|koperasi|kopdes|desa|kelurahan|passcode|password|kata.?sandi|pin/i;
            if(element.tagName==='INPUT'&&String(element.type||'').toLowerCase()==='password') return true;
            // A searchable region control may legitimately use type=search or
            // a select-search class. Exclude global search only when it is not
            // associated with the currently requested administrative level.
            return searchPattern.test(meta.own)&&!regionPattern.test(meta.all);
          }
          function regionControlCandidates(kind,excluded){
            const pattern=regionKindPattern(kind);
            // Many portal builds render dependent regions as ordinary text
            // inputs rather than readonly/select elements. Include them, but
            // only probe a free-text field when its label/nearby form text
            // identifies the requested administrative level.
            const selector='select,input:not([type=password]):not([type=submit]):not([type=button]),textarea,[role=combobox],[role=listbox],button,[role=button],[aria-haspopup],[class*="select"],[class*="dropdown"],[data-testid*="select"],[contenteditable="true"],[tabindex]:not([tabindex="-1"])';
            const seen=new Set();
            return Array.from(document.querySelectorAll(selector)).filter(visible).filter(element=>{
              if(seen.has(element)||excluded.includes(element)||element.disabled) return false;
              seen.add(element);
              const meta=regionControlMeta(element);
              if(isGlobalSearchControl(element,meta,pattern)) return false;
              const explicit=pattern.test(meta.all);
              const tag=element.tagName;
              const inputLike=tag==='INPUT'||tag==='TEXTAREA';
              const dropdown=tag==='SELECT'||element.getAttribute('role')==='combobox'
                ||element.getAttribute('role')==='listbox'||element.hasAttribute('aria-haspopup')
                ||element.hasAttribute('aria-controls')
                ||/(select|dropdown|combobox|choices|select2)/i.test(String(element.className||''));
              const searchableInput=inputLike&&(explicit||element.readOnly||element.hasAttribute('list')
                ||element.hasAttribute('aria-controls')||element.hasAttribute('aria-autocomplete')
                ||String(element.type||'').toLowerCase()==='search');
              if(inputLike&&!searchableInput)return false;
              return explicit||dropdown||searchableInput;
            }).map(element=>{
              const meta=regionControlMeta(element);
              const explicit=pattern.test(meta.all);
              const target=kind==='province'?'Kalimantan Tengah':kind==='regency'?'Kapuas':'Pulau Petak';
              const selectedOption=element.tagName==='SELECT'&&optionFor(element,target);
              const inputLike=element.tagName==='INPUT'||element.tagName==='TEXTAREA';
              const searchable=inputLike&&(element.readOnly||element.hasAttribute('aria-controls')
                ||element.hasAttribute('aria-autocomplete')||String(element.type||'').toLowerCase()==='search');
              return {element,score:(explicit?120:0)+(element.tagName==='SELECT'?50:0)
                +(element.getAttribute('role')==='combobox'?35:0)+(searchable?15:0)+(selectedOption?60:0)};
            }).sort((a,b)=>b.score-a.score).map(item=>item.element);
          }
          function optionNodeText(element){
            return [textOf(element),element.getAttribute('aria-label'),element.getAttribute('title'),
              element.getAttribute('data-value'),element.getAttribute('value')].filter(Boolean).join(' ').replace(/\\s+/g,' ').trim();
          }
          function tryCustomOption(target,exclude){
            const wanted=norm(target);
            if(!wanted)return false;
            const excluded=Array.isArray(exclude)?exclude.filter(Boolean):[exclude].filter(Boolean);
            const selector='[role=option],option,li,button,a,[data-value],[aria-selected],[role=menuitem],[class*="option"],[class*="dropdown-item"],[class*="select__option"],[class*="menu-item"]';
            const candidates=Array.from(document.querySelectorAll(selector)).filter(visible).filter(el=>!excluded.includes(el))
              .map(el=>({el,text:norm(optionNodeText(el))}))
              .filter(item=>item.text&&item.text.length<=140&&(item.text===wanted||(wanted.length>=5&&item.text.includes(wanted))));
            candidates.sort((a,b)=>{
              const aExact=a.text===wanted?0:1,bExact=b.text===wanted?0:1;
              if(aExact!==bExact)return aExact-bExact;
              const aPreferred=a.el.matches('[role=option],option,[data-value],[aria-selected],[role=menuitem],[class*="option"],[class*="dropdown-item"],[class*="select__option"],[class*="menu-item"]')?0:1;
              const bPreferred=b.el.matches('[role=option],option,[data-value],[aria-selected],[role=menuitem],[class*="option"],[class*="dropdown-item"],[class*="select__option"],[class*="menu-item"]')?0:1;
              return aPreferred-bPreferred||a.text.length-b.text.length;
            });
            if(!candidates.length)return false;
            const option=candidates[0].el;
            const clickTarget=option.closest('[role=option],option,li,button,a,[data-value],[aria-selected],[role=menuitem],[class*="option"],[class*="dropdown-item"],[class*="select__option"],[class*="menu-item"]')||option;
            clickTarget.click();
            return true;
          }
          async function waitForCustomOption(target,exclude,maxWait=1700){
            const deadline=Date.now()+maxWait;
            while(Date.now()<deadline){
              if(tryCustomOption(target,exclude))return true;
              await sleep(140);
            }
            return false;
          }
          function setRegionQuery(control,target){
            const proto=control.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;
            const descriptor=Object.getOwnPropertyDescriptor(proto,'value');
            if(!descriptor||!descriptor.set)return false;
            descriptor.set.call(control,target);
            control.dispatchEvent(new Event('input',{bubbles:true}));
            control.dispatchEvent(new Event('change',{bubbles:true}));
            control.dispatchEvent(new KeyboardEvent('keyup',{key:'ArrowDown',code:'ArrowDown',bubbles:true}));
            return true;
          }
          function regionDebug(kind,excluded){
            const pattern=regionKindPattern(kind);
            const controls=regionControlCandidates(kind,excluded).slice(0,18).map(element=>{
              const meta=regionControlMeta(element);
              return {tag:element.tagName,role:element.getAttribute('role')||'',type:element.getAttribute('type')||'',
                label:meta.all.slice(0,180),expanded:element.getAttribute('aria-expanded')||'',
                controls:element.getAttribute('aria-controls')||''};
            });
            const selects=Array.from(document.querySelectorAll('select')).slice(0,16).map(select=>({
              label:regionControlMeta(select).all.slice(0,120),
              optionCount:select.options?.length||0,
              hasTarget:!!optionFor(select,kind==='province'?'Kalimantan Tengah':kind==='regency'?'Kapuas':'Pulau Petak')
            }));
            return {kind,readyState:document.readyState,controlCount:controls.length,selectCount:selects.length,controls,selects};
          }
          function dismissRegionDropdown(){
            try{document.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',code:'Escape',keyCode:27,which:27,bubbles:true}));}catch(_){}
          }
          async function chooseRegion(target,kind,exclude){
            const excluded=Array.isArray(exclude)?exclude.filter(Boolean):[exclude].filter(Boolean);
            const wanted=norm(target);
            if(!wanted)return null;
            let lastCandidateCount=0;
            for(let attempt=0;attempt<16;attempt++){
              const allSelects=Array.from(document.querySelectorAll('select')).filter(el=>!excluded.includes(el)&&!el.disabled);
              // Prefer a native select whose own field label identifies this region.
              const pattern=regionKindPattern(kind);
              const nativeMatches=allSelects.filter(select=>optionFor(select,target));
              const labelledNative=nativeMatches.find(select=>pattern.test(regionControlMeta(select).all));
              const nativeSelect=labelledNative||nativeMatches.find(select=>visible(select));
              if(nativeSelect&&setSelect(nativeSelect,target)){
                nativeSelect.dispatchEvent(new Event('blur',{bubbles:true}));
                return {select:nativeSelect,method:'select'};
              }
              const controls=regionControlCandidates(kind,excluded);
              lastCandidateCount=controls.length;
              for(const control of controls){
                const meta=regionControlMeta(control);
                control.click();
                await sleep(220);
                if(await waitForCustomOption(target,[control],1200))return {select:control,method:'custom'};
                // Some portal versions expose a searchable text input only after
                // opening the field. Type the requested region into that field;
                // never type into password/global cooperative-search controls.
                const inputLike=control.tagName==='INPUT'||control.tagName==='TEXTAREA';
                if(inputLike&&pattern.test(meta.all)&&!isGlobalSearchControl(control,meta,pattern)){
                  if(setRegionQuery(control,target)&&await waitForCustomOption(target,[control],2200))
                    return {select:control,method:'searchable-input'};
                }
                dismissRegionDropdown();
                await sleep(180);
              }
              await sleep(420);
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
            report('Provinsi terpilih. Memuat pilihan Kabupaten/Kota…');
            await sleep(1000);
            const d = await chooseRegion(regency,'regency',[p.select]);
            if(!d){
              bridge.onComplete(JSON.stringify({success:false,message:'Kabupaten/kota penugasan tidak terdeteksi setelah provinsi dipilih.',diagnostics:snapshot()}));
              return;
            }
            report('Kabupaten '+regency+' terpilih. Memuat daftar Kecamatan '+subdistrict+'…');
            await sleep(1500);
            const k = await chooseRegion(subdistrict,'subdistrict',[p.select,d.select]);
            if(!k){
              bridge.onComplete(JSON.stringify({success:false,message:'Pilihan kecamatan penugasan belum tersedia pada portal SIMKOPDES setelah kabupaten dipilih.',stage:'subdistrict',regionDebug:regionDebug('subdistrict',[p.select,d.select]),diagnostics:snapshot()}));
              return;
            }
            report('Kecamatan '+subdistrict+' terpilih. Menyiapkan pencarian '+assignmentSearchName+'…');
            await sleep(350);
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
            report('Passcode diterima untuk diproses. Memindai Batch 1–4 dan Non Prioritas…');

            const categoryName = label => {
              const value=String(label||'').replace(/\\s+/g,' ').trim();
              if(/non[\\s_-]*prioritas/i.test(value)) return 'Non Prioritas';
              const batch=value.match(/batch\\s*[-:#]?\\s*([1-4])/i);
              if(batch) return 'Batch '+batch[1];
              if(/^prioritas$/i.test(value)) return 'Prioritas';
              return '';
            };
            const categoryPattern=/\\bbatch\\s*[-:#]?\\s*[1-4]\\b|non[\\s_-]*prioritas|^prioritas$/i;

            function categoryControls(){
              const found=new Map();
              const selector='[role=tab],button,[role=button],a,li,.ant-tabs-tab,.ant-radio-button-wrapper,.ant-segmented-item,[data-value],[role=option],[role=menuitem]';
              for(const element of Array.from(document.querySelectorAll(selector)).filter(visible)){
                const rawLabel=[textOf(element),element.getAttribute('aria-label'),element.getAttribute('title'),element.getAttribute('data-value')].filter(Boolean).join(' ');
                const label=categoryName(rawLabel);
                if(!label || textOf(element).length>120) continue;
                const clickTarget=element.closest('button,[role=tab],[role=button],a,.ant-tabs-tab,.ant-radio-button-wrapper,.ant-segmented-item')||element;
                const key='node:'+label;
                if(!found.has(key)) found.set(key,{kind:'node',label:label,text:textOf(element)});
              }
              const selects=visibleSelects();
              selects.forEach((select,index)=>{
                Array.from(select.options).forEach(option=>{
                  const label=categoryName(option.textContent);
                  if(!label) return;
                  const key='select:'+index+':'+label;
                  if(!found.has(key)) found.set(key,{kind:'select',selectIndex:index,label:label,optionText:option.textContent.trim()});
                });
              });
              return Array.from(found.values());
            }

            function activeCategoryLabel(){
              const active=document.querySelector('[role=tab][aria-selected=true],.ant-tabs-tab-active,.ant-radio-button-wrapper-checked,.ant-segmented-item-selected,[aria-current=page]');
              return active?categoryName([textOf(active),active.getAttribute('aria-label'),active.getAttribute('title')].filter(Boolean).join(' ')):'';
            }

            function extractCurrentRows(categoryLabel){
              const tables=Array.from(document.querySelectorAll('table,[role=table]')).filter(visible);
              const records=[];
              const seenRows=new Set();
              for(const table of tables){
                const rowEls=Array.from(table.querySelectorAll('tr,[role=row]'));
                if(rowEls.length<2) continue;
                const headerRow=rowEls.find(row=>row.querySelector('th,[role=columnheader]')) || rowEls[0];
                const headers=Array.from(headerRow.querySelectorAll('th,td,[role=columnheader]')).map(textOf);
                const headerNorm=headers.map(norm);
                const findIndex=pattern=>headerNorm.findIndex(header=>pattern.test(header));
                const nameIndex=findIndex(/^nama(?: koperasi| kopdes| kdkmp| kdmp)?$|nama.*(koperasi|kopdes|kdkmp|kdmp)|koperasi|kopdes|kdkmp|kdmp/);
                const statusIndex=findIndex(/status|hasil|verifikasi|verval|kelayakan/);
                const assetStatusIndex=findIndex(/status data aset|status aset|kondisi aset/);
                const batchIndex=findIndex(/batch|tahap/);
                const categoryIndex=findIndex(/prioritas|kategori|kelompok/);
                const ownerIndex=findIndex(/kepemilikan|pemilik|status tanah|hak tanah/);
                const surveyorIndex=findIndex(/surveyor|enumerator|petugas/);
                const latitudeIndex=findIndex(/latitude|lintang/);
                const longitudeIndex=findIndex(/longitude|bujur/);
                const coordinateIndex=findIndex(/koordinat|coordinate|lokasi/);
                const countIndex=findIndex(/jumlah.*(bidang|aset)|bidang.*aset|total.*bidang|total aset/);
                for(const row of rowEls){
                  if(row===headerRow) continue;
                  const cells=Array.from(row.querySelectorAll('td,[role=cell]')).map(textOf);
                  if(!cells.length || !cells.some(Boolean)) continue;
                  const raw={};
                  headers.forEach((header,index)=>{if(header)raw[header]=cells[index]||'';});
                  const at=index=>index>=0?(cells[index]||''):'';
                  const name=at(nameIndex);
                  if(!name) continue;
                  const tableBatch=at(batchIndex)||categoryLabel||activeCategoryLabel()||'';
                  const category=at(categoryIndex)||categoryLabel||activeCategoryLabel()||'';
                  const nonPriority=/non[\\s_-]*prioritas/i.test(category+' '+tableBatch);
                  const batchMatch=(category+' '+tableBatch).match(/batch\\s*[-:#]?\\s*([1-4])/i);
                  const batch=nonPriority?'Non Prioritas':(batchMatch?'Batch '+batchMatch[1]:(tableBatch||''));
                  const countRaw=at(countIndex);
                  const parsedCount=parseInt(String(countRaw).replace(/[^0-9]/g,''),10);
                  let lat=latitudeIndex>=0?Number(at(latitudeIndex)):null;
                  let lng=longitudeIndex>=0?Number(at(longitudeIndex)):null;
                  const coord=at(coordinateIndex);
                  if((!Number.isFinite(lat)||!Number.isFinite(lng)) && coord){
                    const pair=coord.match(/(-?\\d+(?:[.,]\\d+)?)\\s*[,; ]+\\s*(-?\\d+(?:[.,]\\d+)?)/);
                    if(pair){lat=Number(pair[1].replace(',','.'));lng=Number(pair[2].replace(',','.'));}
                  }
                  const sourceText=cells.join(' | ');
                  const rowKey=[norm(name),batch,norm(sourceText)].join('|');
                  if(seenRows.has(rowKey)) continue;
                  seenRows.add(rowKey);
                  records.push({
                    name:name,
                    cooperativeId:at(findIndex(/id koperasi|kode koperasi|nomor koperasi/)),
                    batch:batch||null,
                    priorityType:nonPriority?'Non Prioritas':(batchMatch?'Prioritas':''),
                    category:category||categoryLabel||'',
                    resultStatus:at(statusIndex)||at(assetStatusIndex),
                    assetCount:Number.isFinite(parsedCount)&&parsedCount>=0?parsedCount:null,
                    latitude:Number.isFinite(lat)?lat:null,
                    longitude:Number.isFinite(lng)?lng:null,
                    ownership:at(ownerIndex),
                    surveyor:at(surveyorIndex),
                    assetStatus:at(assetStatusIndex)||at(statusIndex),
                    sourceText:sourceText,
                    rawFields:raw
                  });
                  if(records.length>=5000) break;
                }
                if(records.length>=5000) break;
              }
              return {records:records,tableCount:tables.length};
            }

            function currentTableSignature(categoryLabel=''){
              return extractCurrentRows(categoryLabel).records.map(row=>norm(row.name)+'|'+norm(row.sourceText)).join('¦').slice(0,16000);
            }

            async function waitForTableReady(maxWait=9000){
              const deadline=Date.now()+maxWait;
              let previous='',stable=0,lastCount=0;
              while(Date.now()<deadline){
                const extracted=extractCurrentRows('');
                const signature=extracted.records.map(row=>norm(row.name)+'|'+norm(row.sourceText)).join('¦').slice(0,16000);
                if(signature && signature===previous) stable++;
                else stable=0;
                previous=signature;
                lastCount=extracted.records.length;
                if(signature && stable>=2) return {ready:true,rowCount:lastCount,tableCount:extracted.tableCount};
                await sleep(300);
              }
              const result=extractCurrentRows('');
              return {ready:result.records.length>0,rowCount:result.records.length||lastCount,tableCount:result.tableCount};
            }

            function findAssignmentSearchInput(){
              const candidates=Array.from(document.querySelectorAll('input:not([type=password]),textarea')).filter(visible);
              const scored=candidates.map(element=>{
                const meta=[element.type,element.id,element.name,element.placeholder,element.getAttribute('aria-label'),element.getAttribute('title'),element.parentElement?.innerText].join(' ').toLowerCase();
                if(/passcode|password|pin|provinsi|province|kabupaten|kecamatan|desa|kelurahan|wilayah/i.test(meta)) return {element,score:0};
                let score=0;
                if(String(element.type||'').toLowerCase()==='search') score+=8;
                if(/nama koperasi|nama kopdes|kdkmp|kopdes/i.test(meta)) score+=10;
                if(/search|cari|pencarian|filter/i.test(meta)) score+=6;
                return {element,score};
              }).filter(item=>item.score>0).sort((a,b)=>b.score-a.score);
              return scored[0]?.element||null;
            }

            async function applyAssignmentSearch(){
              if(!assignmentSearchName) return {applied:false,query:''};
              const input=findAssignmentSearchInput();
              if(!input) return {applied:false,query:''};
              const proto=input.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;
              const setter=Object.getOwnPropertyDescriptor(proto,'value').set;
              const queries=[assignmentSearchName];
              const firstToken=assignmentSearchName.split(/\\s+/).filter(Boolean)[0]||'';
              if(firstToken && norm(firstToken)!==norm(assignmentSearchName)) queries.push(firstToken);
              let lastQuery='';
              for(const query of queries){
                lastQuery=query;
                setter.call(input,query);
                input.dispatchEvent(new Event('input',{bubbles:true}));
                input.dispatchEvent(new Event('change',{bubbles:true}));
                input.dispatchEvent(new KeyboardEvent('keyup',{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}));
                input.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}));
                await sleep(1500);
                const rows=extractCurrentRows('');
                const queryNorm=norm(query);
                if(rows.records.some(row=>norm(row.name).includes(queryNorm)||norm(row.sourceText).includes(queryNorm)))
                  return {applied:true,query:query};
              }
              return {applied:true,query:lastQuery};
            }

            function assignedNameMatches(rowName){
              const wanted=norm(assignmentSearchName).split(' ').filter(Boolean);
              const found=norm(rowName).split(' ').filter(Boolean);
              if(wanted.length!==found.length||!wanted.length)return false;
              let changed=0;
              for(let i=0;i<wanted.length;i++){
                if(wanted[i]===found[i])continue;
                if(Math.min(wanted[i].length,found[i].length)<6)return false;
                let previous=Array.from({length:found[i].length+1},(_,j)=>j);
                for(let x=1;x<=wanted[i].length;x++){
                  const current=[x];
                  for(let y=1;y<=found[i].length;y++){
                    const cost=wanted[i][x-1]===found[i][y-1]?0:1;
                    current[y]=Math.min(current[y-1]+1,previous[y]+1,previous[y-1]+cost);
                  }
                  previous=current;
                }
                if(previous[found[i].length]>1)return false;
                changed++;
              }
              return changed<=1;
            }

            function getNextPageControl(){
              const candidates=Array.from(document.querySelectorAll('button,a,[role=button],li')).filter(visible);
              return candidates.find(element=>{
                const text=[textOf(element),element.getAttribute('aria-label'),element.getAttribute('title'),String(element.className||'')].join(' ');
                if(!/(next|selanjutnya|berikutnya|pagination-next|halaman berikut|›|»)/i.test(text)) return false;
                const disabled=element.disabled || element.getAttribute('aria-disabled')==='true'
                  || element.classList.contains('disabled') || element.parentElement?.classList.contains('disabled')
                  || element.closest('li')?.classList.contains('ant-pagination-disabled');
                return !disabled;
              })||null;
            }

            async function waitForTableChange(previousSignature,maxWait=4200){
              const deadline=Date.now()+maxWait;
              while(Date.now()<deadline){
                await sleep(200);
                const current=extractCurrentRows('').records.map(row=>row.sourceText).join('¦').slice(0,12000);
                if(current && current!==previousSignature) return true;
              }
              return false;
            }

            async function collectCurrentCategory(label,allRecords,coverage,tableCounts){
              const category=label||activeCategoryLabel()||'Kategori awal';
              const pageSignatures=new Set();
              let pages=0;
              const search=await applyAssignmentSearch();
              if(search.applied) report('Filter nama: '+search.query+' · '+category+'…');
              else report('Memeriksa seluruh tabel '+category+'…');
              await waitForTableReady(4500);
              const maxPagesPerCategory=12;
              while(pages<maxPagesPerCategory){
                await sleep(250);
                const extracted=extractCurrentRows(category);
                tableCounts.count=Math.max(tableCounts.count,extracted.tableCount);
                const signature=extracted.records.map(row=>row.sourceText).join('¦');
                const pageKey=category+'|'+signature;
                if(signature && !pageSignatures.has(pageKey)){
                  pageSignatures.add(pageKey);
                  allRecords.push(...extracted.records);
                }
                pages++;
                report('Memindai '+category+' · halaman '+pages+' · '+allRecords.length+' baris terkumpul…');
                const next=getNextPageControl();
                if(!next) break;
                if(pages>=maxPagesPerCategory){
                  tableCounts.paginationComplete=false;
                  break;
                }
                const before=signature;
                next.click();
                if(!await waitForTableChange(before,4200)){
                  tableCounts.paginationComplete=false;
                  break;
                }
              }
              tableCounts.pagesByCategory=tableCounts.pagesByCategory||{};
              tableCounts.pagesByCategory[category]=pages;
              if(category && !coverage.includes(category)) coverage.push(category);
            }

            async function activateCategory(entry){
              if(entry.kind==='select'){
                const selects=visibleSelects();
                const select=selects[entry.selectIndex];
                if(!select) return false;
                const option=Array.from(select.options).find(item=>categoryName(item.textContent)===entry.label);
                if(!option) return false;
                const setter=Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype,'value').set;
                setter.call(select,option.value);
                select.dispatchEvent(new Event('input',{bubbles:true}));
                select.dispatchEvent(new Event('change',{bubbles:true}));
                await sleep(500);
                return true;
              }
              const selector='[role=tab],button,[role=button],a,li,.ant-tabs-tab,.ant-radio-button-wrapper,.ant-segmented-item';
              const target=Array.from(document.querySelectorAll(selector)).filter(visible)
                .find(element=>categoryName(textOf(element))===entry.label && textOf(element).length<=90);
              if(!target) return false;
              const clickTarget=target.closest('button,[role=tab],[role=button],a,.ant-tabs-tab,.ant-radio-button-wrapper,.ant-segmented-item,[role=option],[role=menuitem]')||target;
              clickTarget.click();
              await waitForTableReady(7000);
              await sleep(350);
              return true;
            }

            const allRecords=[];
            const coverage=[];
            const tableCounts={count:0,paginationComplete:true,pagesByCategory:{}};
            const initialCategory=activeCategoryLabel();
            if(initialCategory) report('Memeriksa kategori awal: '+initialCategory+'…');
            else report('Menunggu tabel Verval siap…');
            await waitForTableReady(11000);
            await collectCurrentCategory(initialCategory,allRecords,coverage,tableCounts);
            let entries=categoryControls();
            entries.sort((a,b)=>(a.label==='Non Prioritas'?-1:0)-(b.label==='Non Prioritas'?-1:0));
            const processed=new Set();
            for(let index=0;index<entries.length;index++){
              const entry=entries[index];
              if(processed.has(entry.label)) continue;
              report('Memeriksa kategori '+(processed.size+1)+' dari '+entries.length+': '+entry.label+'…');
              if(!await activateCategory(entry)) continue;
              processed.add(entry.label);
              await collectCurrentCategory(entry.label,allRecords,coverage,tableCounts);
              if(entry.label==='Non Prioritas' && allRecords.some(row=>assignedNameMatches(row.name))){
                report('Nama penugasan ditemukan di Non Prioritas. Kategori lain dilewati.');
                break;
              }
              entries=categoryControls().sort((a,b)=>(a.label==='Non Prioritas'?-1:0)-(b.label==='Non Prioritas'?-1:0));
            }

            // Some portal builds expose category labels as filters in table
            // headers instead of separate tabs. Preserve the category values
            // discovered in each row and never stop after the first non-empty table.
            const observedCategories=Array.from(new Set(allRecords.map(row=>row.batch||row.priorityType||row.category).filter(Boolean)));
            const hasNonPriority=allRecords.some(row=>/non[\\s_-]*prioritas/i.test([row.batch,row.priorityType,row.category,row.sourceText].join(' ')))
              || coverage.some(label=>/non[\\s_-]*prioritas/i.test(label));
            const hasBatchSelection=[1,2,3,4].every(number=>
              coverage.some(label=>new RegExp('batch\\\\s*'+number,'i').test(label))
              || allRecords.some(row=>new RegExp('batch\\\\s*'+number,'i').test([row.batch,row.category,row.sourceText].join(' ')))
            );
            const tableHasCategoryColumn=allRecords.some(row=>/prioritas|batch/i.test(Object.keys(row.rawFields||{}).join(' ')));
            const coverageComplete=hasNonPriority&&(hasBatchSelection||coverage.some(label=>/^prioritas$/i.test(label))||tableHasCategoryColumn)&&tableCounts.paginationComplete!==false;
            const uniqueRows=new Map();
            for(const row of allRecords){
              const key=[norm(row.name),row.cooperativeId||'',row.batch||'',norm(row.sourceText)].join('|');
              if(!uniqueRows.has(key)) uniqueRows.set(key,row);
            }
            const records=Array.from(uniqueRows.values());
            if(records.length){
              bridge.onComplete(JSON.stringify({
                success:true,records:records,province:province,regency:regency,subdistrict:subdistrict,assignment:assignment,searchName:assignmentSearchName,
                source:'Halaman Verval SIMKOPDES · seluruh kategori yang terbaca',
                updatedAt:new Date().toISOString(),tableCount:tableCounts.count,
                rowCount:records.length,coverage:coverage,
                observedCategories:observedCategories,
                coverageComplete:coverageComplete,
                paginationComplete:tableCounts.paginationComplete,
                pagesByCategory:tableCounts.pagesByCategory,
                nonPriorityFound:hasNonPriority
              }));
              return;
            }

            const finalSnapshot=snapshot();
            bridge.onComplete(JSON.stringify({
              success:false,
              message:'Passcode diterima, tetapi data tabel tidak ditemukan pada kategori yang berhasil diperiksa.',
              diagnostics:finalSnapshot,
              coverage:coverage,
              categoryControls:entries.map(entry=>entry.label),
              paginationComplete:tableCounts.paginationComplete,
              pagesByCategory:tableCounts.pagesByCategory
            }));
            return;
          }
          run().catch(error=>{
            bridge.onComplete(JSON.stringify({success:false,message:'Otomasi alur Verval gagal: '+(error.message||error),diagnostics:snapshot()}));
          });
        })();
        """,
                JSONObject.quote(province),
                JSONObject.quote(regency),
                JSONObject.quote(subdistrict),
                JSONObject.quote(assignment),
                JSONObject.quote(searchName),
                JSONObject.quote(passcode));
    }

    private String getVervalPasscode() {
        // Legacy WebView automation is no longer the primary Verval path.
        // Never embed a shared passcode in a distributable APK.
        return "";
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
            result.put("requestedRegency", regency);
            result.put("requestedSubdistrict", subdistrict);
            result.put("assignment", assignment);
            result.put("searchName", searchName);
            result.put("passcodeSubmitted", passcodeSubmitted);
            Intent data = new Intent();
            data.putExtra("verval_result_json", result.toString());
            setResult(RESULT_OK, data);
        } catch (Exception error) {
            try {
                JSONObject failure = new JSONObject();
                failure.put("success", false);
                failure.put("message", "Hasil Verval tidak dapat dikirim ke AgriAttend: " + error.getMessage());
                failure.put("records", new JSONArray());
                failure.put("province", province);
                failure.put("regency", regency);
                failure.put("subdistrict", subdistrict);
                failure.put("assignment", assignment);
                failure.put("searchName", searchName);
                failure.put("passcodeSubmitted", passcodeSubmitted);
                Intent data = new Intent();
                data.putExtra("verval_result_json", failure.toString());
                setResult(RESULT_OK, data);
            } catch (Exception nested) {
                setResult(RESULT_CANCELED);
            }
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
            result.put("regency", regency);
            result.put("subdistrict", subdistrict);
            result.put("assignment", assignment);
            result.put("searchName", searchName);
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
