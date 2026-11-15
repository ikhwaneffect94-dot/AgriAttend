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
        }, 180000);
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
          const snapshot = () => {
            const categoryPattern = /\\bbatch\\s*[-:#]?\\s*[1-4]\\b|non[\\s_-]*prioritas|^prioritas$/i;
            const visibleNodes = Array.from(document.querySelectorAll('[role=tab],button,[role=button],a,li,.ant-tabs-tab,.ant-radio-button-wrapper,.ant-segmented-item'))
              .filter(el=>visible(el))
              .map(el=>String(el.innerText||el.textContent||el.getAttribute('aria-label')||'').replace(/\\s+/g,' ').trim())
              .filter(value=>value && value.length<=100);
            return {
              url: location.href,
              title: document.title,
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
              const selector='[role=tab],button,[role=button],a,li,.ant-tabs-tab,.ant-radio-button-wrapper,.ant-segmented-item,[data-value]';
              for(const element of Array.from(document.querySelectorAll(selector)).filter(visible)){
                const label=categoryName(textOf(element));
                if(!label || textOf(element).length>90) continue;
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
              return active?categoryName(textOf(active)):'';
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
                const nameIndex=findIndex(/nama.*(koperasi|kopdes|kdkmp|kdmp)|nama koperasi|nama kopdes|koperasi|kopdes|kdkmp|kdmp/);
                const statusIndex=findIndex(/status|hasil|verifikasi|verval|kelayakan/);
                const batchIndex=findIndex(/batch|tahap/);
                const categoryIndex=findIndex(/prioritas|kategori|kelompok/);
                const ownerIndex=findIndex(/kepemilikan|pemilik|status tanah|hak tanah/);
                const surveyorIndex=findIndex(/surveyor|enumerator|petugas/);
                const latitudeIndex=findIndex(/latitude|lintang/);
                const longitudeIndex=findIndex(/longitude|bujur/);
                const coordinateIndex=findIndex(/koordinat|coordinate|lokasi/);
                const countIndex=findIndex(/jumlah bidang|jumlah aset|bidang aset|total bidang/);
                for(const row of rowEls){
                  if(row===headerRow) continue;
                  const cells=Array.from(row.querySelectorAll('td,[role=cell]')).map(textOf);
                  if(!cells.length || !cells.some(Boolean)) continue;
                  const raw={};
                  headers.forEach((header,index)=>{if(header)raw[header]=cells[index]||'';});
                  const at=index=>index>=0?(cells[index]||''):'';
                  const name=at(nameIndex)||cells.find((cell,index)=>cell && !/^\\d+$/.test(cell) && index<3)||'';
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
                    resultStatus:at(statusIndex)||'Status Verval tersedia pada tabel resmi',
                    assetCount:Number.isFinite(parsedCount)&&parsedCount>0?parsedCount:1,
                    latitude:Number.isFinite(lat)?lat:null,
                    longitude:Number.isFinite(lng)?lng:null,
                    ownership:at(ownerIndex),
                    surveyor:at(surveyorIndex),
                    assetStatus:at(statusIndex),
                    sourceText:sourceText,
                    rawFields:raw
                  });
                  if(records.length>=5000) break;
                }
                if(records.length>=5000) break;
              }
              return {records:records,tableCount:tables.length};
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

            async function waitForTableChange(previousSignature,maxWait=1400){
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
              while(pages<50){
                await sleep(350);
                const extracted=extractCurrentRows(category);
                tableCounts.count=Math.max(tableCounts.count,extracted.tableCount);
                const signature=extracted.records.map(row=>row.sourceText).join('¦');
                const pageKey=category+'|'+signature;
                if(signature && !pageSignatures.has(pageKey)){
                  pageSignatures.add(pageKey);
                  allRecords.push(...extracted.records);
                }
                pages++;
                const next=getNextPageControl();
                if(!next) break;
                const before=signature;
                next.click();
                if(!await waitForTableChange(before)) break;
              }
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
                await sleep(1000);
                return true;
              }
              const selector='[role=tab],button,[role=button],a,li,.ant-tabs-tab,.ant-radio-button-wrapper,.ant-segmented-item';
              const target=Array.from(document.querySelectorAll(selector)).filter(visible)
                .find(element=>categoryName(textOf(element))===entry.label && textOf(element).length<=90);
              if(!target) return false;
              const clickTarget=target.closest('button,[role=tab],[role=button],a,.ant-tabs-tab,.ant-radio-button-wrapper,.ant-segmented-item')||target;
              clickTarget.click();
              await sleep(1000);
              return true;
            }

            const allRecords=[];
            const coverage=[];
            const tableCounts={count:0};
            const initialCategory=activeCategoryLabel();
            await collectCurrentCategory(initialCategory,allRecords,coverage,tableCounts);
            let entries=categoryControls();
            const processed=new Set();
            for(let index=0;index<entries.length;index++){
              const entry=entries[index];
              if(processed.has(entry.label)) continue;
              processed.add(entry.label);
              report('Memeriksa '+entry.label+'…');
              if(!await activateCategory(entry)) continue;
              await collectCurrentCategory(entry.label,allRecords,coverage,tableCounts);
              entries=categoryControls();
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
            const coverageComplete=hasNonPriority&&(hasBatchSelection||coverage.some(label=>/^prioritas$/i.test(label))||tableHasCategoryColumn);
            const uniqueRows=new Map();
            for(const row of allRecords){
              const key=[norm(row.name),row.cooperativeId||'',row.batch||'',norm(row.sourceText)].join('|');
              if(!uniqueRows.has(key)) uniqueRows.set(key,row);
            }
            const records=Array.from(uniqueRows.values());
            if(records.length){
              bridge.onComplete(JSON.stringify({
                success:true,records:records,province:province,district:district,
                source:'Halaman Verval SIMKOPDES · seluruh kategori yang terbaca',
                updatedAt:new Date().toISOString(),tableCount:tableCounts.count,
                rowCount:records.length,coverage:coverage,
                observedCategories:observedCategories,
                coverageComplete:coverageComplete,
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
              categoryControls:entries.map(entry=>entry.label)
            }));
            return;
          }
          run().catch(error=>{
            bridge.onComplete(JSON.stringify({success:false,message:'Otomasi alur Verval gagal: '+(error.message||error),diagnostics:snapshot()}));
          });
        })();
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
