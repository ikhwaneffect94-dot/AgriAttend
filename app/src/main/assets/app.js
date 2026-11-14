'use strict';
const STORE = 'agriattend.v1';
const MASTER_API = 'https://agriconnect-master.starconnect.id';
const CLIENT_APP = 'AgriAttend';
const CLIENT_VERSION = '1.5.3';
const $ = (sel, root=document) => root.querySelector(sel);
const $$ = (sel, root=document) => [...root.querySelectorAll(sel)];
const state = loadState();
let currentType = 'CHECKIN';
let attendanceMode = 'inOffice';
let payslipUnlocked = false;
let payslipRows = [];
let payslipSelected = null;
let shiftOptions = [];
let currentGps = null;
let historyFilter = 'all';
let toastTimer;
let session = null;
let companyLocation = null;
let companyLocationData = null;
let companyLocationError = '';
let companyLocationResponseReceived = false;
let companyLocationLoading = false;
let companyLocationPromise = null;
let selfieRequired = false;
let faceRecognitionRequired = false;
let correctionPurposes = [];
let preflightNeedsOtp = false;
let apiCounter = 0;
let profileDetail = null;
let personalDetail = null;
let employmentDetail = null;
let calendarRows = [];
let calendarSelectedDate = null;
let calendarMonth = new Date(new Date().getFullYear(), new Date().getMonth(), 1);
let requestStatusFilter = 'all';
let requestCategoryFilter = 'all';
let officialCheckInOutRequests = [];
let requestStatusErrors = [];
let calendarLoading = false;
const pendingApi = new Map();

function loadState(){
  try {
    const saved = JSON.parse(localStorage.getItem(STORE) || '{}');
    return {
      profile: saved.profile || {name:'', employee:''},
      locations: Array.isArray(saved.locations) ? saved.locations : [],
      attendance: Array.isArray(saved.attendance) ? saved.attendance : [],
      corrections: Array.isArray(saved.corrections) ? saved.corrections : [],
      noticeDismissed: !!saved.noticeDismissed,
      vervalRecords: Array.isArray(saved.vervalRecords) ? saved.vervalRecords : [],
      vervalUpdatedAt: saved.vervalUpdatedAt || '',
      vervalAssignment: saved.vervalAssignment || '',
      vervalProvince: saved.vervalProvince || ''
    };
  } catch { return {profile:{name:'',employee:''},locations:[],attendance:[],corrections:[],noticeDismissed:false,vervalRecords:[],vervalUpdatedAt:'',vervalAssignment:'',vervalProvince:''}; }
}
function persist(){ localStorage.setItem(STORE, JSON.stringify(state)); renderAll(); }
function esc(value){return String(value ?? '').replace(/[&<>"']/g, ch=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[ch]));}
function fmtDate(value, opts={day:'2-digit',month:'short',year:'numeric'}){return new Intl.DateTimeFormat('id-ID',opts).format(new Date(value));}
function fmtTime(value){return new Intl.DateTimeFormat('id-ID',{hour:'2-digit',minute:'2-digit',second:'2-digit',hour12:false}).format(new Date(value));}
function showToast(message){const el=$('#toast');el.textContent=message;el.classList.add('show');clearTimeout(toastTimer);toastTimer=setTimeout(()=>el.classList.remove('show'),3500);}
function initials(name){return (name||'IM').trim().split(/\s+/).slice(0,2).map(x=>x[0]?.toUpperCase()||'').join('')||'IM';}
function clockTick(){const now=new Date();$('#liveClock').textContent=fmtTime(now);$('#liveDay').textContent=fmtDate(now,{weekday:'long',day:'numeric',month:'long',year:'numeric'});$('#todayDate').textContent=fmtDate(now,{weekday:'short',day:'numeric',month:'short'});}
function route(name){
  const allowed=['home','attendance','calendar','verval','payslip','profile','correction','history','locations']; if(!allowed.includes(name)) name='home';
  $$('.page').forEach(p=>p.classList.toggle('active',p.id===`page-${name}`));
  $$('[data-route]').forEach(b=>b.classList.toggle('active',b.dataset.route===name));
  const labels={home:'Ringkasan',attendance:'Absensi',calendar:'Kalender Absensi',verval:'Pantauan Verval Kopdes',payslip:'Slip Gaji',profile:'Profil Karyawan',correction:'Pengajuan',history:'Riwayat',locations:'Lokasi Kerja'};
  $('#pageCrumb').textContent=labels[name];
  if(name==='history') renderHistory(); if(name==='locations') renderLocations(); if(name==='verval') renderVervalPage(); if(name==='profile') renderProfilePage(); if(name==='payslip') renderPayslipPage(); if(name==='correction' && session){loadShiftOptions().catch(()=>{});loadOfficialCheckInOutStatuses().catch(e=>showToast('Status pengajuan gagal dimuat: '+readableApiError(e)));} if(name==='calendar' && session) loadCalendarWorkspace(); if(name==='attendance' && session && !companyLocation && !companyLocationResponseReceived && !companyLocationLoading) loadOfficialCompanyLocation().catch(()=>{}); window.scrollTo({top:0,behavior:'smooth'});
}
function localYmd(date){const y=date.getFullYear(),m=String(date.getMonth()+1).padStart(2,'0'),d=String(date.getDate()).padStart(2,'0');return `${y}-${m}-${d}`;}
function unwrapList(value){
  if(Array.isArray(value))return value;
  for(const candidate of [value?.data,value?.content,value?.result,value?.items,value?.records,value?.data?.content,value?.data?.items])if(Array.isArray(candidate))return candidate;
  return [];
}
function unwrapApiObject(value){
  let current=value;
  for(let i=0;i<5;i++){
    if(!current||typeof current!=='object'||Array.isArray(current))break;
    if(current.data&&typeof current.data==='object'&&!Array.isArray(current.data)){current=current.data;continue;}
    if(current.result&&typeof current.result==='object'&&!Array.isArray(current.result)){current=current.result;continue;}
    if(current.payload&&typeof current.payload==='object'&&!Array.isArray(current.payload)){current=current.payload;continue;}
    break;
  }
  return current;
}
function readableApiError(error){
  const status=Number(error?.status)||0;
  const detail=String(error?.message||'Koneksi tidak diketahui').replace(/\s+/g,' ').trim().slice(0,240);
  if(status===401)return 'HTTP 401: sesi tidak diterima server setelah percobaan refresh. Keluar lalu login kembali.';
  if(status===403)return 'HTTP 403: akun tidak memiliki izin membaca lokasi kantor. Hubungi administrator AgriConnect.';
  if(status===404)return 'HTTP 404: endpoint lokasi kantor tidak ditemukan pada tenant ini. Minta administrator memeriksa konfigurasi tenant.';
  if(status>=500)return `Server AgriConnect sedang bermasalah (HTTP ${status}). Coba beberapa saat lagi.`;
  if(status>0)return `Permintaan lokasi kantor gagal (HTTP ${status}): ${detail}`;
  return `Lokasi kantor belum berhasil diambil: ${detail}`;
}
function formatApiDate(value){
  if(value==null||value==='')return '—';
  let d=value;
  if(typeof value==='number'||/^\d{10,13}$/.test(String(value)))d=new Date(Number(value)<1e12?Number(value)*1000:Number(value));
  else d=new Date(value);
  return Number.isNaN(d.getTime())?String(value):fmtDate(d,{day:'2-digit',month:'short',year:'numeric'});
}
function distanceMeters(lat1,lon1,lat2,lon2){const rad=x=>x*Math.PI/180;const dLat=rad(lat2-lat1),dLon=rad(lon2-lon1);const a=Math.sin(dLat/2)**2+Math.cos(rad(lat1))*Math.cos(rad(lat2))*Math.sin(dLon/2)**2;return 6371000*2*Math.atan2(Math.sqrt(a),Math.sqrt(1-a));}
/* Native HTTP bridge: requests use HTTPSURLConnection to avoid WebView CORS restrictions. */
window.__nativeApiResult = function(id,status,body){
  const resolve = pendingApi.get(id); if(!resolve) return; pendingApi.delete(id);
  let data = body; try { data = body ? JSON.parse(body) : {}; } catch (_) {}
  resolve({status:Number(status)||0,body,data,ok:Number(status)>=200&&Number(status)<300});
};
function apiRequest(method,url,headers={},body=null,bodyMode='json'){
  return new Promise((resolve,reject)=>{
    if(!window.AndroidBridge || typeof AndroidBridge.apiRequest!=='function'){
      reject(new Error('Koneksi API AgriConnect hanya tersedia pada aplikasi Android AgriAttend.'));return;
    }
    const id='req_'+Date.now()+'_'+(++apiCounter);
    pendingApi.set(id,resolve);
    try { AndroidBridge.apiRequest(method,url,JSON.stringify(headers),body==null?'':JSON.stringify(body),bodyMode,id); }
    catch(e){pendingApi.delete(id);reject(e);}
    setTimeout(()=>{if(pendingApi.has(id)){pendingApi.delete(id);resolve({status:0,ok:false,data:{message:'Waktu tunggu koneksi habis.'},body:''});}},50000);
  });
}
function parseError(result){
  const d=result?.data||{};
  return d.message||d.error_description||d.error||d.detail||`Permintaan API gagal (HTTP ${result?.status||'tidak diketahui'}).`;
}
async function apiJson(method,url,headers={},body=null,bodyMode='json'){
  const r=await apiRequest(method,url,headers,body,bodyMode);
  if(!r.ok){const error=new Error(parseError(r));error.status=r.status;throw error;}
  return r.data;
}
function jwtPayload(token){
  try{
    const part=String(token).split('.')[1]; if(!part) throw new Error('Token tidak lengkap');
    const normalized=part.replace(/-/g,'+').replace(/_/g,'/');
    const raw=atob(normalized.padEnd(Math.ceil(normalized.length/4)*4,'='));
    const bytes=Uint8Array.from(raw,c=>c.charCodeAt(0));
    return JSON.parse(new TextDecoder().decode(bytes));
  }catch(e){throw new Error('Token login AgriConnect tidak dapat dibaca.');}
}
function getClaimArray(claim){return Array.isArray(claim)?claim:(claim==null?[]:[claim]);}
function validateTenantApiUrl(value){
  let u;try{u=new URL(String(value));}catch(_){throw new Error('Alamat API tenant dari token tidak valid.');}
  if(u.protocol!=='https:'||u.username||u.password||u.port&&u.port!=='443'||!u.hostname||u.hostname==='localhost')throw new Error('Alamat API tenant bukan HTTPS yang aman.');
  return u.origin + u.pathname.replace(/\/+$/, '');
}
function sessionHeaders(){
  if(!session) throw new Error('Silakan login ke AgriConnect terlebih dahulu.');
  return {'X-TenantID':String(session.tenantId),'Authorization':'Bearer '+session.accessToken,'AuthorizationToken':session.authoritiesToken||''};
}
function saveSession(){
  if(!window.AndroidBridge||!AndroidBridge.saveSecureSession) throw new Error('Penyimpanan sesi Android tidak tersedia.');
  const ok=AndroidBridge.saveSecureSession(JSON.stringify(session));
  if(!ok) throw new Error('Sesi tidak dapat disimpan dengan enkripsi Android.');
}
function showAuth(message='',success=false){
  $('#authScreen').hidden=false;$('#authMessage').textContent=message;$('#authMessage').classList.toggle('success',success);
}
function hideAuth(){ $('#authScreen').hidden=true;$('#authMessage').textContent=''; }
function loginMessage(text,success=false){$('#authMessage').textContent=text;$('#authMessage').classList.toggle('success',success);}
async function preflightLogin(){
  preflightNeedsOtp=false;
  const username=$('#loginUsername').value.trim();if(!username){loginMessage('Masukkan username terlebih dahulu.');return;}
  $('#loginContinue').disabled=true;loginMessage('Memeriksa username di AgriConnect…');
  try{
    const rawResponse=await apiJson('POST',MASTER_API+'/api/auth',{}, {username});
    const response=rawResponse?.data??rawResponse;
    // Match the official APK: proceed to password only when the master API
    // explicitly reports isValid=true. Missing/unknown isValid is not treated
    // as a successful username check.
    const valid=response?.isValid;
    const isValid=valid===true||valid===1||['true','1','y','yes'].includes(String(valid).toLowerCase());
    if(!isValid){loginMessage('AgriConnect tidak mengonfirmasi username valid. Periksa username atau hubungi admin tenant.');return;}
    const otpFlag=response?.isNeedOtp;preflightNeedsOtp=otpFlag===true||otpFlag===1||['true','y','1'].includes(String(otpFlag).toLowerCase());
    if(preflightNeedsOtp){
      $('#credentialStep').hidden=true;$('#authActions').hidden=true;$('#loginContinue').hidden=false;
      loginMessage('Server AgriConnect menandai akun ini wajib OTP. Sesuai pengaturan AgriAttend tanpa OTP, login tidak dilanjutkan dan tidak akan mencoba melewati verifikasi. Gunakan aplikasi resmi atau minta admin menyediakan metode integrasi yang didukung.');
      return;
    }
    $('#credentialStep').hidden=false;$('#authActions').hidden=false;$('#loginContinue').hidden=true;
    $('#loginPassword').required=true;$('#loginPassword').focus();
    loginMessage('Username valid. Login menggunakan password tanpa OTP sesuai respons server.',true);
  }catch(e){loginMessage('Gagal memeriksa username: '+e.message);}
  finally{$('#loginContinue').disabled=false;}
}
async function submitLogin(e){
  e.preventDefault();const username=$('#loginUsername').value.trim(),password=$('#loginPassword').value;
  if(!username||!password){loginMessage('Username dan password wajib diisi.');return;}
  if(preflightNeedsOtp){loginMessage('Server mewajibkan OTP untuk akun ini. AgriAttend tidak melewati verifikasi tersebut.');return;}
  $('#loginSubmit').disabled=true;loginMessage('Mengautentikasi akun AgriConnect…');
  try{
    const body={username,password,grantType:'password'};
    const rawResult=await apiJson('POST',MASTER_API+'/api/auth/login',{},body);
    const result=rawResult?.data??rawResult;
    if(!result.access_token||!result.refresh_token)throw new Error('Respons login tidak memuat access token dan refresh token.');
    const claims=jwtPayload(result.access_token);
    const tenants=getClaimArray(claims.tenant_id),instances=getClaimArray(claims.instance_api);
    if(!tenants.length||!instances.length)throw new Error('Token login tidak memiliki tenant_id atau instance_api. Hubungi admin AgriConnect.');
    const urlApi=validateTenantApiUrl(instances[0]);
    if(window.AndroidBridge.setTenantApiHost&&!AndroidBridge.setTenantApiHost(new URL(urlApi).hostname))throw new Error('Host API tenant ditolak oleh pemeriksaan keamanan.');
    session={tenantId:String(tenants[0]),urlApi,accessToken:result.access_token,refreshToken:result.refresh_token,userName:claims.user_name||username,expirationDate:claims.expiration_date||'',authoritiesToken:'',profile:null};
    await loadOfficialProfile();
    await loadOfficialAttendanceSettings();
    saveSession();
    $('#loginPassword').value='';
    hideAuth();if($('#connectionStatus'))$('#connectionStatus').textContent='Terhubung';
    showToast('Login AgriConnect berhasil.');renderAll();
  }catch(err){session=null;loginMessage('Login gagal: '+err.message);}
  finally{$('#loginSubmit').disabled=false;}
}
async function loadOfficialProfile(){
  let profile=await apiJson('GET',session.urlApi+'/api/user/profile',sessionHeaders());
  if(profile&&profile.data&&!profile.authoritiesToken)profile=profile.data;
  session.profile=profile||{};session.authoritiesToken=profile?.authoritiesToken||'';
  const name=profile.fullName||profile.name||profile.employeeName||profile.displayName||session.userName;
  state.profile={name:String(name||''),employee:String(profile.employmentId||profile.employeeId||profile.employeeNumber||'')};
  // Keep the API's multiple-profile behaviour visible; do not silently pick another employee.
  let profiles=[];try{const profileList=await apiJson('GET',session.urlApi+'/api/user/profile/list',sessionHeaders());profiles=Array.isArray(profileList)?profileList:(Array.isArray(profileList?.data)?profileList.data:[]);}catch(_){profiles=[];}
  if(Array.isArray(profiles)&&profiles.length>1) await chooseEmployeeProfile(profiles);
  session.profile=session.profile||{};
  await loadOfficialProfileDetails();
}
async function loadOfficialProfileDetails(){
  if(!session)return;
  const headers=sessionHeaders();
  const [detailResult,personalResult,employmentResult]=await Promise.allSettled([
    apiJson('GET',session.urlApi+'/api/user/profile/profile-detail',headers),
    apiJson('GET',session.urlApi+'/api/user/profile/personal',headers),
    apiJson('GET',session.urlApi+'/api/user/profile/employment',headers)
  ]);
  profileDetail=detailResult.status==='fulfilled'?(detailResult.value?.data??detailResult.value):null;
  personalDetail=personalResult.status==='fulfilled'?(personalResult.value?.data??personalResult.value):null;
  employmentDetail=employmentResult.status==='fulfilled'?(employmentResult.value?.data??employmentResult.value):null;
  session.profileDetail=profileDetail;session.personalDetail=personalDetail;session.employmentDetail=employmentDetail;
  renderProfilePage();
}
function chooseEmployeeProfile(profiles){
  return new Promise((resolve,reject)=>{
    const select=$('#profileChoice');select.innerHTML=profiles.map((p,i)=>`<option value="${i}">${esc(p.employeeName||p.name||p.username||p.employeeId||('Profil '+(i+1)))}</option>`).join('');
    const dialog=$('#profileSelectDialog');dialog.showModal();
    const handler=async e=>{
      e.preventDefault();const selected=profiles[Number(select.value)];if(!selected){reject(new Error('Profil karyawan tidak ditemukan.'));return;}
      try{
        const rawResponse=await apiJson('POST',session.urlApi+'/api/user/profile/change-profile',sessionHeaders(),{username:selected.username||session.userName,currentProfileEmployeeId:selected.employeeId});
        const response=rawResponse?.data??rawResponse;
        if(response&&response.access_token){const claims=jwtPayload(response.access_token);session.accessToken=response.access_token;session.refreshToken=response.refresh_token||session.refreshToken;session.tenantId=getClaimArray(claims.tenant_id)[0]||session.tenantId;session.urlApi=validateTenantApiUrl(getClaimArray(claims.instance_api)[0]||session.urlApi);if(AndroidBridge.setTenantApiHost)AndroidBridge.setTenantApiHost(new URL(session.urlApi).hostname);}
        dialog.close();$('#profileSelectForm').removeEventListener('submit',handler);let updatedProfile=await apiJson('GET',session.urlApi+'/api/user/profile',sessionHeaders());if(updatedProfile?.data&&!updatedProfile.authoritiesToken)updatedProfile=updatedProfile.data;session.profile=updatedProfile;session.authoritiesToken=updatedProfile?.authoritiesToken||'';state.profile={name:String(updatedProfile.fullName||updatedProfile.name||updatedProfile.employeeName||session.userName),employee:String(updatedProfile.employmentId||updatedProfile.employeeId||'')};resolve();
      }catch(err){loginMessage('Gagal memilih profil: '+err.message);reject(err);}
    };
    $('#profileSelectForm').addEventListener('submit',handler);
  });
}
async function loadOfficialCompanyLocation(){
  if(!session)throw new Error('Login AgriConnect diperlukan untuk memuat lokasi kantor.');
  if(companyLocationPromise)return companyLocationPromise;
  companyLocationLoading=true;
  companyLocationResponseReceived=false;
  const name=$('#companyLocationName'), tolerance=$('#companyTolerance'), message=$('#companyLocationMessage'), button=$('#refreshCompanyLocation');
  if(button){button.disabled=true;button.textContent='Memuat…';}
  if(message)message.textContent='Mengambil titik dan radius kantor dari server AgriConnect…';
  if(name)name.textContent='Memuat lokasi…';
  companyLocationPromise=(async()=>{
    try{
      const url=session.urlApi+'/api/user/home/dashboard/location';
      const response=await apiJsonWithRefresh('GET',url);
      // The official AgriConnect client also calls this endpoint silently.
      // Some tenants return a successful response without office coordinates;
      // record that distinction so attendance can follow the official request path.
      companyLocationResponseReceived=true;
      const loc=unwrapApiObject(response);
      companyLocationData=loc;
      const rawLat=loc?.latitude??loc?.lat;
      const rawLng=loc?.longitude??loc?.lng??loc?.long;
      const rawTolerance=loc?.toleranceInMeter??loc?.tolerance_in_meter??loc?.tolerance;
      const lat=Number(rawLat),lng=Number(rawLng),toleranceValue=Number(rawTolerance);
      if(rawLat==null||rawLng==null||rawTolerance==null||rawLat===''||rawLng===''||rawTolerance===''||
         !Number.isFinite(lat)||lat < -90||lat>90||!Number.isFinite(lng)||lng < -180||lng>180||
         !Number.isFinite(toleranceValue)||toleranceValue<=0){
        throw new Error('Tenant tidak mengirim latitude, longitude, atau toleranceInMeter. Mengikuti alur APK AgriConnect, GPS aktual tetap dapat dikirim dan keputusan akhir mengikuti respons server. Tidak ada koordinat kantor yang dibuat oleh aplikasi.');
      }
      companyLocation={latitude:lat,longitude:lng,toleranceInMeter:toleranceValue};
      companyLocationError='';
      if(name)name.textContent=`Lokasi kantor dari AgriConnect (${lat.toFixed(5)}, ${lng.toFixed(5)})`;
      if(tolerance)tolerance.textContent=`${toleranceValue} meter`;
      if(message)message.textContent='Titik dan radius kantor berhasil dimuat otomatis dari server. Tidak perlu diisi manual.';
      if(currentGps){
        const d=distanceMeters(currentGps.latitude,currentGps.longitude,lat,lng);
        $('#gpsDistance').textContent=`Jarak ke kantor: ${Math.round(d)} m / batas ${Math.round(toleranceValue)} m`;
      }
      return companyLocation;
    }catch(error){
      companyLocation=null;
      companyLocationError=readableApiError(error);
      if(name)name.textContent=companyLocationResponseReceived?'Tidak disediakan tenant':'Tidak dapat dimuat';
      if(tolerance)tolerance.textContent=companyLocationResponseReceived?'Tidak tersedia':'—';
      if(message)message.textContent=companyLocationError;
      throw error;
    }
  })();
  try{return await companyLocationPromise;}
  finally{
    companyLocationPromise=null;
    companyLocationLoading=false;
    if(button){button.disabled=false;button.textContent='Muat ulang lokasi kantor ↻';}
  }
}

async function loadOfficialAttendanceSettings(){
  if(!session)return;
  try{await loadOfficialCompanyLocation();}
  catch(e){console.warn('AgriConnect office location request failed:',e?.status||0,e?.message||e);}
  try{
    const purposeResponse=await apiJsonWithRefresh('GET',session.urlApi+'/api/lookup/global-key?name=TM_CHECKINOUT_PURPOSES');
    correctionPurposes=extractPurposeList(purposeResponse);
    renderCorrectionPurposes();
  }catch(e){correctionPurposes=[];renderCorrectionPurposes();$('#correctionPurposeHelp').textContent='Daftar keperluan dari server tidak dapat dimuat. '+readableApiError(e);}
  try{
    const propertyResponse=await apiJsonWithRefresh('GET',session.urlApi+'/api/lookup/global-key?name=TM_PROPERTIES');
    const list=unwrapList(propertyResponse);
    const selfie=list.find(x=>x.name==='TM_CHECK_INOUT_REQUIRED_SELFIE');
    selfieRequired=String(selfie?.value||'N').toUpperCase()==='Y';
    $('#companyPhotoPolicy').textContent=selfieRequired?'Selfie wajib':'Selfie opsional';
    $('#photoRequiredLabel').textContent=selfieRequired?'* Wajib':'';
    $('#photoHelp').textContent=selfieRequired?'Tenant mewajibkan foto selfie sebelum absensi dikirim.':'Pilih foto bila diminta atau dibutuhkan oleh alur perusahaan.';
  }catch(e){selfieRequired=false;$('#companyPhotoPolicy').textContent='Belum dapat dipastikan';$('#photoHelp').textContent='Kebijakan selfie tidak dapat dibaca: '+readableApiError(e);}
  try{
    const mobile=decodeMaybeJwt(session.profile?.mobileAuthorityToken);
    const authorities=mobile?.authorities||[];
    faceRecognitionRequired=Array.isArray(authorities)&&authorities.some(a=>/face|recognition|wajah/i.test(String(a.name||a)));
  }catch(_){faceRecognitionRequired=false;}
  if(faceRecognitionRequired&&$('#faceRecognitionNotice'))$('#faceRecognitionNotice').textContent='Profil ini memiliki otorisasi face recognition. Verifikasi wajah khusus belum tersedia di pendamping; gunakan AgriConnect resmi sampai modul itu diintegrasikan.';
}

function extractPurposeList(response){
  const candidates=[response,response?.data,response?.listPurpose,response?.data?.listPurpose,response?.result];
  for(const candidate of candidates){if(Array.isArray(candidate))return candidate.filter(x=>x&&x.value!=null&&x.name!=null).map(x=>({value:String(x.value),name:String(x.name)}));}
  return [];
}
function renderCorrectionPurposes(){
  const select=$('#correctionPurpose');if(!select)return;
  const old=select.value;
  select.innerHTML='<option value="">Pilih keperluan dari AgriConnect</option>'+correctionPurposes.map(p=>`<option value="${esc(p.value)}">${esc(p.name)}</option>`).join('');
  if(correctionPurposes.some(p=>p.value===old))select.value=old;
  $('#correctionPurposeHelp').textContent=correctionPurposes.length?'Pilihan mengikuti TM_CHECKINOUT_PURPOSES dari server AgriConnect.':'Belum ada pilihan keperluan dari server.';
  const attendanceSelect=$('#attendancePurpose');if(attendanceSelect){const keep=attendanceSelect.value;attendanceSelect.innerHTML='<option value="">Pilih keperluan dari AgriConnect</option>'+correctionPurposes.map(p=>`<option value="${esc(p.value)}">${esc(p.name)}</option>`).join('');if(correctionPurposes.some(p=>p.value===keep))attendanceSelect.value=keep;const help=$('#attendancePurposeHelp');if(help)help.textContent=correctionPurposes.length?'Pilihan dimuat dari daftar tenant AgriConnect.':'Daftar keperluan tidak tersedia.';}
}
function decodeMaybeJwt(token){try{return jwtPayload(token);}catch(_){return null;}}
async function refreshSession(reloadTenantSettings=true){
  if(!session?.refreshToken)return false;
  const rawResponse=await apiJson('POST',MASTER_API+'/api/auth/login',{}, {username:session.userName,grantType:'refresh_token',refreshToken:session.refreshToken});
  const response=rawResponse?.data??rawResponse;
  if(!response.access_token)throw new Error('Refresh token tidak menghasilkan access token.');
  const claims=jwtPayload(response.access_token);session.accessToken=response.access_token;session.refreshToken=response.refresh_token||session.refreshToken;
  session.tenantId=getClaimArray(claims.tenant_id)[0]||session.tenantId;session.urlApi=validateTenantApiUrl(getClaimArray(claims.instance_api)[0]||session.urlApi);
  if(AndroidBridge.setTenantApiHost&&!AndroidBridge.setTenantApiHost(new URL(session.urlApi).hostname))throw new Error('Host API tenant tidak lolos validasi.');
  session.expirationDate=claims.expiration_date||'';await loadOfficialProfile();if(reloadTenantSettings)await loadOfficialAttendanceSettings();saveSession();return true;
}
async function apiJsonWithRefresh(method,url,body=null,bodyMode='json'){
  try{return await apiJson(method,url,sessionHeaders(),body,bodyMode);}
  catch(e){
    if(e.status===401){await refreshSession(false);return await apiJson(method,url,sessionHeaders(),body,bodyMode);}
    throw e;
  }
}
async function restoreSession(){
  if(!window.AndroidBridge||!AndroidBridge.getSecureSession){showAuth('Jalankan AgriAttend versi Android untuk login dan mengirim absensi.');return;}
  try{
    const saved=AndroidBridge.getSecureSession();if(!saved){showAuth();return;}
    session=JSON.parse(saved);if(!session?.accessToken||!session?.urlApi){session=null;showAuth();return;}
    session.urlApi=validateTenantApiUrl(session.urlApi);if(AndroidBridge.setTenantApiHost&&!AndroidBridge.setTenantApiHost(new URL(session.urlApi).hostname))throw new Error('Host API tenant tidak valid.');
    const exp=session.expirationDate?new Date(session.expirationDate).getTime():0;
    if(exp&&exp<Date.now()+60000)await refreshSession();else{await loadOfficialProfile();await loadOfficialAttendanceSettings();saveSession();}
    hideAuth();if($('#connectionStatus'))$('#connectionStatus').textContent='Terhubung';renderAll();
  }catch(e){session=null;AndroidBridge.clearSecureSession();showAuth('Sesi tidak dapat dipulihkan. Login kembali: '+e.message);}
}
function logout(){
  if(!confirm('Keluar dari sesi AgriConnect pada aplikasi ini?'))return;
  session=null;payslipUnlocked=false;payslipRows=[];payslipSelected=null;shiftOptions=[];companyLocation=null;companyLocationData=null;companyLocationError='';companyLocationResponseReceived=false;companyLocationLoading=false;companyLocationPromise=null;selfieRequired=false;faceRecognitionRequired=false;currentGps=null;profileDetail=null;personalDetail=null;employmentDetail=null;calendarRows=[];officialCheckInOutRequests=[];requestStatusErrors=[];requestStatusFilter='all';requestCategoryFilter='all';
  if(window.AndroidBridge&&AndroidBridge.clearSecureSession)AndroidBridge.clearSecureSession();
  if($('#connectionStatus'))$('#connectionStatus').textContent='Belum terhubung';if($('#companyLocationName'))$('#companyLocationName').textContent='Memuat lokasi…';if($('#companyTolerance'))$('#companyTolerance').textContent='—';
  $('#credentialStep').hidden=true;$('#authActions').hidden=true;$('#loginContinue').hidden=false;$('#loginUsername').value='';$('#loginPassword').value='';preflightNeedsOtp=false;
  state.profile={name:'',employee:''};persist();renderProfilePage();renderCalendarGrid();renderCorrectionStatusList();
  showAuth('Kamu sudah keluar dari AgriConnect.',true);
}
function distanceToOffice(gps){
  if(!companyLocation)throw new Error('Titik dan radius kantor dari API belum tersedia.');
  const distance=distanceMeters(gps.latitude,gps.longitude,companyLocation.latitude,companyLocation.longitude);
  $('#gpsDistance').textContent=`Jarak ke kantor: ${Math.round(distance)} m / batas ${Math.round(companyLocation.toleranceInMeter)} m`;
  return distance;
}

function updateGpsUI(){
  const lat=$('#gpsLat'),lng=$('#gpsLng');
  if(!currentGps){if(lat)lat.textContent='Belum diambil';if(lng)lng.textContent='Belum diambil';if($('#gpsAccuracy'))$('#gpsAccuracy').textContent='Akurasi: —';if($('#gpsDistance'))$('#gpsDistance').textContent='Jarak kantor: —';if($('#gpsMapLink'))$('#gpsMapLink').textContent='Lokasi aktual akan tampil setelah GPS diambil.';if($('#captureTime'))$('#captureTime').textContent='Belum dicatat';return;}
  if(lat)lat.textContent=currentGps.latitude.toFixed(6);if(lng)lng.textContent=currentGps.longitude.toFixed(6);if($('#gpsAccuracy'))$('#gpsAccuracy').textContent=`Akurasi GPS: ±${Math.round(currentGps.accuracy)} m`;
  if($('#gpsDistance')){if(companyLocation){const d=distanceMeters(currentGps.latitude,currentGps.longitude,companyLocation.latitude,companyLocation.longitude);$('#gpsDistance').textContent=`Jarak ke kantor: ${Math.round(d)} m / batas ${Math.round(companyLocation.toleranceInMeter)} m`;}else $('#gpsDistance').textContent='Radius kantor belum tersedia.';}
  if($('#gpsMapLink'))$('#gpsMapLink').innerHTML=`<a href="https://www.google.com/maps?q=${currentGps.latitude},${currentGps.longitude}" target="_blank" rel="noopener">Lihat lokasi aktual di peta ↗</a>`;
  if($('#captureTime'))$('#captureTime').textContent=`${fmtDate(currentGps.capturedAt,{day:'2-digit',month:'long',year:'numeric'})} · ${fmtTime(currentGps.capturedAt)}`;
}
function getGps(){
  return new Promise((resolve,reject)=>{
    if(!navigator.geolocation){reject(new Error('Browser tidak mendukung GPS.'));return;}
    $('#gpsLat').textContent='Mengambil lokasi…';$('#gpsLng').textContent='Mengambil lokasi…';
    navigator.geolocation.getCurrentPosition(pos=>{
      currentGps={latitude:pos.coords.latitude,longitude:pos.coords.longitude,accuracy:pos.coords.accuracy,capturedAt:new Date().toISOString()};updateGpsUI();resolve(currentGps);
    },err=>{currentGps=null;updateGpsUI();const messages={1:'Izin lokasi ditolak. Aktifkan izin lokasi Android.',2:'Posisi belum tersedia. Pastikan GPS aktif.',3:'Waktu tunggu GPS habis. Coba lagi.'};reject(new Error(messages[err.code]||'Gagal mengambil GPS.'));},{enableHighAccuracy:true,timeout:25000,maximumAge:0});
  });
}
function base64Utf8(value){const bytes=new TextEncoder().encode(value);let binary='';for(let i=0;i<bytes.length;i+=0x8000)binary+=String.fromCharCode(...bytes.subarray(i,i+0x8000));return btoa(binary);}
function verifyMockLocation(gps){
  if(!window.AndroidBridge||typeof AndroidBridge.getMockLocationStatus!=='function')return;
  const status=AndroidBridge.getMockLocationStatus(gps.latitude,gps.longitude,new Date(gps.capturedAt).getTime());
  if(status==='mock')throw new Error('Perangkat melaporkan lokasi simulasi/mock. Matikan aplikasi lokasi palsu dan ambil GPS asli sebelum absensi.');
  if(status==='unknown')console.warn('Status mock location tidak dapat dipastikan dari provider Android; server tetap melakukan validasi geofence.');
}
async function compressPhoto(file){
  let bitmap;
  try{bitmap=await createImageBitmap(file);}catch(_){return file;}
  const maxSide=1600,scale=Math.min(1,maxSide/Math.max(bitmap.width,bitmap.height));
  const canvas=document.createElement('canvas');canvas.width=Math.max(1,Math.round(bitmap.width*scale));canvas.height=Math.max(1,Math.round(bitmap.height*scale));
  const ctx=canvas.getContext('2d');ctx.drawImage(bitmap,0,0,canvas.width,canvas.height);bitmap.close?.();
  const blob=await new Promise(resolve=>canvas.toBlob(resolve,'image/jpeg',0.82));
  if(!blob)throw new Error('Foto gagal dikompresi.');return blob;
}
function fileToBase64(file){
  return new Promise((resolve,reject)=>{const reader=new FileReader();reader.onload=()=>{const value=String(reader.result||'');resolve(value.split(',')[1]||'');};reader.onerror=()=>reject(new Error('Foto tidak dapat dibaca.'));reader.readAsDataURL(file);});
}
function profileValue(value){
  if(value===null||value===undefined||value==='')return '—';
  if(typeof value==='boolean')return value?'Ya':'Tidak';
  return String(value);
}
function renderProfilePage(){
  const p=session?.profile||{},d=profileDetail||session?.profileDetail||{},b=personalDetail||session?.personalDetail||{},e=employmentDetail||session?.employmentDetail||{};
  const name=p.fullName||d.employeeName||p.name||session?.userName||'Belum login';
  const items=[
    ['Nama lengkap',p.fullName||d.employeeName||b.fullName],['Nomor pegawai',d.employeeNo||p.employeeNo],
    ['Employee ID',p.employeeId],['Employment ID',p.employmentId],['Nama panggilan',b.nickname],
    ['Jabatan',e.positionName||d.positionName],['Job title',e.jobTitle],['Perusahaan',e.companyName],
    ['Organisasi',e.organizationName],['Lokasi kerja',e.locationName],['Level jabatan',e.positionLevelName],
    ['Grade',e.gradeName],['Jenis hubungan kerja',e.type],['Atasan',e.managerName],
    ['Tanggal mulai kerja',e.hireDate],['Tanggal mulai di perusahaan',e.enterpriseHireDate],['Masa kerja',e.lengthOfService],
    ['Mulai periode jabatan',e.periodStart],['Akhir periode jabatan',e.periodEnd],
    ['Jenis kelamin',b.gender],['Agama',b.religion],['Golongan darah',b.bloodType],
    ['Tempat lahir',b.birthPlace],['Tanggal lahir',b.birthDate],['Status perkawinan',b.status],
    ['Tanggal perkawinan',b.maritalDate],['Pendidikan terakhir',b.lastEducation],
    ['Email kantor',b.officeEmail],['Email pribadi',b.personalMail],['Telepon utama',b.mainPhone],
    ['Telepon lain',b.otherPhone],['NPWP',b.npwp],['Nomor BPJS Ketenagakerjaan',b.bpjsTkNo],
    ['Nomor BPJS Kesehatan',b.bpjsKesehatanNo],['Nomor asuransi',b.insuranceNo],
    ['NIK',b.nationalId],['Nomor keluarga',b.familyId],['Bank',b.bank],
    ['Cabang bank',b.branchName],['Nama pemilik rekening',b.accountName],['Nomor rekening',b.accountNo],
    ['Jadwal shift',p.shiftInDateTimeFormatted],['Sedang off-shift',p.isOffShift],
    ['Hari libur hari ini',p.isHoliday],['Status ketidakhadiran hari ini',p.isAbsence],
    ['Terdaftar face recognition',p.registeredFace]
  ].filter(x=>x[1]!==undefined&&x[1]!==null&&x[1]!=='');
  const title=$('#profileDisplayName');if(title)title.textContent=name;
  const employee=$('#profileDisplayEmployee');if(employee)employee.textContent=d.employeeNo?`No. Pegawai ${d.employeeNo}`:(p.employmentId?`Employment ID ${p.employmentId}`:'Profil AgriConnect');
  const avatar=$('#profileAvatarLarge');if(avatar)avatar.textContent=initials(name);
  const grid=$('#profileDataGrid');if(grid)grid.innerHTML=items.length?items.map(([label,value])=>`<div class="profile-data-item"><small>${esc(label)}</small><b>${esc(profileValue(value))}</b></div>`).join(''):'<div class="empty-state">Data profil belum dimuat. Login dan tekan Muat ulang profil.</div>';
  const stateEl=$('#profileDataStatus');if(stateEl)stateEl.textContent=session?'Data berasal dari API AgriConnect':'Belum terhubung';
}
async function refreshProfileData(){
  if(!session){showAuth('Login AgriConnect diperlukan untuk menarik data profil.');return;}
  const btn=$('#refreshProfile');if(btn){btn.disabled=true;btn.textContent='Memuat…';}
  try{
    const result=await apiJsonWithRefresh('GET',session.urlApi+'/api/user/profile');
    const profile=result?.data??result;
    if(!profile||typeof profile!=='object')throw new Error('Respons profil tidak dikenali.');
    session.profile=profile;session.authoritiesToken=profile.authoritiesToken||session.authoritiesToken||'';
    state.profile={name:String(profile.fullName||profile.name||profile.employeeName||session.userName||''),employee:String(profile.employmentId||profile.employeeId||profile.employeeNumber||'')};
    await loadOfficialProfileDetails();saveSession();persist();renderProfilePage();showToast('Profil berhasil diperbarui dari AgriConnect.');
  }catch(e){showToast('Gagal memuat profil: '+e.message);}
  finally{if(btn){btn.disabled=false;btn.textContent='Muat ulang profil ↻';}}
}
function normalizeRegionText(value){return String(value||'').normalize('NFD').replace(/[\u0300-\u036f]/g,'').toLowerCase().replace(/\b(kabupaten|kab\.?|kota administrasi|kota)\b/g,' ').replace(/[^a-z0-9]+/g,' ').trim().replace(/\s+/g,' ');}
function getPlacementRegion(){
  const sources=[session?.employmentDetail,employmentDetail,session?.profileDetail,profileDetail,session?.profile,companyLocationData].filter(Boolean);
  const directKeys=['kabupatenname','kabupaten','regencyname','regency','districtname','district','cityname','city','assignmentkabupaten','assignmentcity','placementcity','placementdistrict','workcityname','workdistrictname'];
  const provinceKeys=['provincename','provinsiname','province','provinsi','statename','assignmentprovince','workprovincename'];
  const seen=new Set(),values=[];
  function visit(obj,depth=0){if(!obj||typeof obj!=='object'||depth>5||seen.has(obj))return;seen.add(obj);for(const [key,value] of Object.entries(obj)){if(typeof value==='string'&&value.trim()){const k=key.toLowerCase();if(directKeys.includes(k))values.unshift({key:k,value:value.trim()});else if(/location|placement|assignment|district|kabupaten|regency|city|office|branch|province|provinsi|address|alamat/.test(k))values.push({key:k,value:value.trim()});}else if(value&&typeof value==='object')visit(value,depth+1);}}
  sources.forEach(x=>visit(x));
  let kabupaten='',province='';
  for(const item of values){const m=item.value.match(/(?:kabupaten|kab\.?|kota(?:\s+administrasi)?)\s*[:.-]?\s*([A-Za-zÀ-ÿ0-9 '\-]+?)(?=\s*[,/|·;]|\s+(?:provinsi|province)\b|$)/i);if(m&&m[1].trim().length>1){kabupaten=(/kota/i.test(item.value.slice(0,m.index+5))?'Kota ':'Kab. ')+m[1].trim();break;}}
  if(!kabupaten){const direct=values.find(x=>['kabupatenname','kabupaten','regencyname','regency','districtname','district','cityname','city','assignmentkabupaten','assignmentcity','placementcity','placementdistrict','workcityname','workdistrictname'].includes(x.key));if(direct)kabupaten=direct.value;}
  for(const source of sources){for(const [key,value] of Object.entries(source)){if(provinceKeys.includes(key.toLowerCase())&&typeof value==='string'&&value.trim()){province=value.trim();break;}}if(province)break;}
  if(!province){const nestedProvince=values.find(x=>provinceKeys.includes(x.key));if(nestedProvince)province=nestedProvince.value;}
  if(!province){for(const item of values){const m=item.value.match(/(?:provinsi|province)\s*[:.-]?\s*([A-Za-zÀ-ÿ0-9 '\-]+)/i);if(m){province=m[1].trim();break;}}}
  if(!kabupaten){const loc=values.find(x=>['worklocationname','assignmentlocationname','assignedlocationname','locationname','worklocation','placementlocation'].includes(x.key));if(loc){const parts=loc.value.split(/[,/|·]/).map(x=>x.trim()).filter(Boolean);const best=parts.find(x=>/^(kabupaten|kab\.?|kota)\b/i.test(x))||parts.find(x=>/\b(kabupaten|kab\.?|kota)\b/i.test(x));if(best)kabupaten=best;else if(parts.length===1&&!/kantor|workshop|gerai|office|koperasi|kopdes|merah putih|desa|kelurahan|kecamatan|jalan|jl\.?/i.test(parts[0])&&parts[0].length<45)kabupaten=parts[0];else if(parts.length>1){const knownProvinces=['Aceh','Sumatera Utara','Sumatera Barat','Riau','Kepulauan Riau','Jambi','Sumatera Selatan','Kepulauan Bangka Belitung','Bengkulu','Lampung','DKI Jakarta','Jawa Barat','Banten','Jawa Tengah','DI Yogyakarta','Jawa Timur','Bali','Nusa Tenggara Barat','Nusa Tenggara Timur','Kalimantan Barat','Kalimantan Tengah','Kalimantan Selatan','Kalimantan Timur','Kalimantan Utara','Sulawesi Utara','Gorontalo','Sulawesi Tengah','Sulawesi Barat','Sulawesi Selatan','Sulawesi Tenggara','Maluku','Maluku Utara','Papua','Papua Barat','Papua Selatan','Papua Tengah','Papua Pegunungan','Papua Barat Daya'];const last=parts[parts.length-1];const matched=knownProvinces.find(x=>normalizeRegionText(x)===normalizeRegionText(last));if(!province&&matched)province=matched;const candidate=parts[parts.length-2];if((matched||province&&normalizeRegionText(last)===normalizeRegionText(province))&&!/kantor|workshop|gerai|office|koperasi|kopdes|merah putih|desa|kelurahan|kecamatan|jalan|jl\.?/i.test(candidate)&&candidate.length<45)kabupaten=candidate;}}}
  return {kabupaten:kabupaten.trim(),province:province.trim()};
}
function renderVervalPage(){
  const placement=getPlacementRegion();const assignment=$('#vervalAssignmentLabel'),province=$('#vervalProvinceLabel');
  if(assignment)assignment.textContent=placement.kabupaten||'Kabupaten/kota belum ditemukan di data profil';if(province)province.textContent=placement.province||'Tidak tersedia';
  const stored=!!(window.AndroidBridge&&AndroidBridge.hasVervalPassword&&AndroidBridge.hasVervalPassword());const credentialStatus=$('#vervalCredentialStatus'),help=$('#vervalCredentialHelp');
  if(credentialStatus)credentialStatus.textContent=stored?'Akses tersimpan aman':'Belum disiapkan';if(help)help.textContent=stored?'Passcode tersimpan terenkripsi dan akan dimasukkan otomatis saat diminta portal.':'Masukkan passcode satu kali; setelah tersimpan, kamu tidak perlu mengisinya saat refresh berikutnya.';const credentialEntry=$('#vervalCredentialEntry'),saveCredential=$('#saveVervalPassword');if(credentialEntry)credentialEntry.hidden=stored;if(saveCredential)saveCredential.hidden=stored;
  const total=$('#vervalTotal'),batch=$('#vervalBatchCount'),nonPriority=$('#vervalNonPriority'),updated=$('#vervalUpdatedAt');
  if(total)total.textContent=state.vervalRecords.length?String(state.vervalRecords.length):'—';if(batch)batch.textContent=state.vervalRecords.length?String(state.vervalRecords.filter(x=>x.batch>=1&&x.batch<=4).length):'—';if(nonPriority)nonPriority.textContent=state.vervalRecords.length?String(state.vervalRecords.filter(x=>/non\s*prioritas|non-priority/i.test((x.resultStatus||'')+' '+(x.sourceText||''))).length):'—';
  if(updated)updated.textContent=state.vervalUpdatedAt?fmtDate(state.vervalUpdatedAt,{day:'2-digit',month:'short',year:'numeric'})+' '+fmtTime(state.vervalUpdatedAt):'Belum ada';
  const subtitle=$('#vervalResultSubtitle');if(subtitle)subtitle.textContent=state.vervalRecords.length?`${state.vervalRecords.length} koperasi · ${state.vervalAssignment||placement.kabupaten||'Lokasi'} · data Simkopdes terakhir berhasil dibaca.`:'Belum ada hasil. Tekan Refresh Data Verval untuk mengambil daftar dari portal.';
  const list=$('#vervalRecords');if(!list)return;
  if(!state.vervalRecords.length){list.innerHTML='<div class="empty-state">Belum ada data verval. Simpan akses satu kali, lalu tekan Refresh Data Verval.</div>';return;}
  const q=($('#vervalSearch')?.value||'').trim().toLowerCase(),batchFilter=$('#vervalBatchFilter')?.value||'all',statusFilter=$('#vervalStatusFilter')?.value||'all';
  let records=state.vervalRecords.filter(r=>{const st=String(r.resultStatus||'').toLowerCase(),name=String(r.name||'').toLowerCase();if(q&&!name.includes(q))return false;if(batchFilter==='none'&&r.batch!=null)return false;if(batchFilter!=='all'&&batchFilter!=='none'&&String(r.batch)!==batchFilter)return false;if(statusFilter==='non'&&!/non\s*prioritas|non-priority/.test(st+' '+r.sourceText))return false;if(statusFilter==='layak'&&!/layak digunakan/.test(st))return false;if(statusFilter==='catatan'&&!/catatan perbaikan/.test(st))return false;return true;});
  if(!records.length){list.innerHTML='<div class="empty-state">Tidak ada koperasi yang cocok dengan filter.</div>';return;}
  list.innerHTML=records.map(record=>{const hasCoords=record.latitude!==null&&record.latitude!==undefined&&record.longitude!==null&&record.longitude!==undefined&&Number.isFinite(Number(record.latitude))&&Number.isFinite(Number(record.longitude));const coords=hasCoords?`${Number(record.latitude).toFixed(6)}, ${Number(record.longitude).toFixed(6)}`:'Koordinat tidak tersedia';const rawMap=String(record.mapUrl||'');const safeMap=/^https:\/\/(?:www\.google\.com\/maps|maps\.google\.com|maps\.app\.goo\.gl|goo\.gl\/maps)/i.test(rawMap)?rawMap:'';const map=safeMap||(hasCoords?`https://www.google.com/maps?q=${record.latitude},${record.longitude}`:'');const status=record.resultStatus||'Status tidak terbaca';return `<article class="verval-record"><div class="verval-record-main"><b>${esc(record.name||'Nama koperasi tidak terbaca')}</b>${record.batch?`<span class="verval-batch">Batch ${esc(record.batch)}</span>`:''}</div><div class="verval-record-status"><span class="verval-result-pill">${esc(status)}</span></div><div class="verval-record-meta"><small>KOORDINAT LOKASI</small><div class="verval-coordinates">${esc(coords)}</div></div><a href="${map?esc(map):'#'}" target="_blank" rel="noopener" class="${map?'':'disabled'}">Buka Google Maps ↗</a></article>`;}).join('');
}
function setVervalStatus(message,isError=false){const el=$('#vervalLiveStatus');if(el){el.textContent=message;el.classList.toggle('verval-error',!!isError);}}
window.__vervalAutomationStatus=function(message){setVervalStatus(String(message||'Portal sedang diproses…'));};
window.__vervalAutomationError=function(message){setVervalStatus(String(message||'Pemeriksaan verval gagal.'),true);const btn=$('#refreshVervalData');if(btn){btn.disabled=false;btn.textContent='↻ Refresh Data Verval';}};
window.__vervalAutomationNeedPassword=function(){setVervalStatus('Portal meminta passcode. Simpan passcode pemantauan di atas, kemudian tekan Refresh Data Verval kembali.',true);const input=$('#vervalPassword');if(input)input.focus();const btn=$('#refreshVervalData');if(btn){btn.disabled=false;btn.textContent='↻ Refresh Data Verval';}};
window.__vervalAutomationResult=function(payload){
  try{const result=typeof payload==='string'?JSON.parse(payload):payload;if(!result||!Array.isArray(result.records)||!result.records.length)throw new Error('Portal tidak mengembalikan daftar koperasi.');
    state.vervalRecords=result.records.map(x=>({name:String(x.name||''),batch:x.batch==null?null:Number(x.batch),resultStatus:String(x.resultStatus||'Status tidak terbaca'),latitude:x.latitude==null?null:Number(x.latitude),longitude:x.longitude==null?null:Number(x.longitude),mapUrl:String(x.mapUrl||''),sourceText:String(x.sourceText||'')}));
    state.vervalUpdatedAt=result.updatedAt||new Date().toISOString();state.vervalAssignment=result.sourceKabupaten||result.kabupaten||getPlacementRegion().kabupaten;state.vervalProvince=result.provinsi||getPlacementRegion().province;
    persist();renderVervalPage();setVervalStatus(`Berhasil memperbarui ${state.vervalRecords.length} data koperasi dari Simkopdes pada ${fmtDate(state.vervalUpdatedAt,{day:'2-digit',month:'long',year:'numeric'})} pukul ${fmtTime(state.vervalUpdatedAt)}.`);const btn=$('#refreshVervalData');if(btn){btn.disabled=false;btn.textContent='↻ Refresh Data Verval';}showToast(`Data verval diperbarui: ${state.vervalRecords.length} koperasi.`);
  }catch(e){window.__vervalAutomationError('Data verval gagal diproses: '+(e.message||e));}
};
async function refreshVervalData(){
  const btn=$('#refreshVervalData');if(btn){btn.disabled=true;btn.textContent='Memeriksa…';}
  try{
    if(!session)throw new Error('Login AgriConnect terlebih dahulu agar lokasi penugasan bisa dibaca.');
    if(!window.AndroidBridge||typeof AndroidBridge.openVervalPortal!=='function')throw new Error('Otomatisasi portal tersedia pada aplikasi Android AgriAttend.');
    const placement=getPlacementRegion();
    if(!placement.kabupaten)throw new Error('Kabupaten/kota penugasan belum ditemukan pada data employment AgriConnect. Perbarui profil atau minta administrator memastikan data penempatan.');
    if(!AndroidBridge.hasVervalPassword||!AndroidBridge.hasVervalPassword())throw new Error('Simpan passcode pemantauan satu kali pada bagian Akses Simkopdes terlebih dahulu.');
    state.vervalAssignment=placement.kabupaten;state.vervalProvince=placement.province;
    persist();setVervalStatus(`Membuka Simkopdes dan mencari ${placement.kabupaten}${placement.province?', '+placement.province:''}…`);
    AndroidBridge.openVervalPortal(placement.kabupaten,placement.province);
  }catch(e){window.__vervalAutomationError(e.message||'Gagal memulai pemeriksaan verval.');showToast(e.message||'Gagal memulai pemeriksaan verval.');}
  finally{if(btn&&(!window.AndroidBridge||!AndroidBridge.openVervalPortal||!session||!getPlacementRegion().kabupaten||!AndroidBridge.hasVervalPassword?.())){btn.disabled=false;btn.textContent='↻ Refresh Data Verval';}}
}

function formatMoney(value){if(value===null||value===undefined||value==='')return '—';const n=Number(value);return Number.isFinite(n)?new Intl.NumberFormat('id-ID',{style:'currency',currency:'IDR',maximumFractionDigits:0}).format(n):String(value);}
function renderPayslipPage(){
  const panel=$('#payslipPinPanel');if(panel)panel.hidden=payslipUnlocked;
  const list=$('#payslipList');if(!list)return;
  if(!payslipUnlocked){list.innerHTML='<div class="empty-state">Verifikasi PIN untuk memuat slip gaji.</div>';return;}
  if(!payslipRows.length){list.innerHTML='<div class="empty-state">Belum ada slip gaji yang tersedia pada akun ini.</div>';return;}
  list.innerHTML=payslipRows.map((row,i)=>{const period=String(row.period??'');const runId=String(row.runTypeId??'');return `<button type="button" class="payslip-list-item ${payslipSelected&&payslipSelected.period===period&&String(payslipSelected.runTypeId)===runId?'active':''}" data-payslip-index="${i}"><span><b>${esc(period||'Periode')}</b><small>${esc(row.runTypeName||'Payroll')}</small></span><strong>${formatMoney(row.value)}</strong></button>`;}).join('');
}
async function verifyPayslipPin(){
  if(!session){showAuth('Login AgriConnect diperlukan untuk membuka slip gaji.');return;}
  const pin=$('#payslipPin')?.value.trim()||'';if(!/^\d{6}$/.test(pin)){const msg=$('#payslipPinMessage');if(msg)msg.textContent='PIN harus terdiri dari 6 digit.';return;}
  const btn=$('#unlockPayslip');btn.disabled=true;btn.textContent='Memeriksa…';
  try{const result=await apiJsonWithRefresh('POST',session.urlApi+'/api/user/my-profile/pin/validation',{pin});const payload=unwrapApiObject(result);const rejected=result===false||result==null||payload?.error===true||payload?.valid===false||payload?.isValid===false;if(rejected)throw new Error(payload?.message||result?.message||'PIN tidak diterima server.');payslipUnlocked=true;$('#payslipPin').value='';$('#payslipPinMessage').textContent='PIN diterima. Memuat daftar slip…';await loadPayslipList();}
  catch(e){payslipUnlocked=false;$('#payslipPinMessage').textContent='Verifikasi gagal: '+(e.message||e);showToast('PIN slip gaji tidak dapat diverifikasi.');}
  finally{btn.disabled=false;btn.textContent='Verifikasi';renderPayslipPage();}
}
async function loadPayslipList(){
  if(!session)throw new Error('Login AgriConnect diperlukan.');
  const result=await apiJsonWithRefresh('GET',session.urlApi+'/api/user/self-service/payslip');payslipRows=unwrapList(result);renderPayslipPage();
}
async function loadPayslipDetail(row){
  if(!session||!payslipUnlocked)throw new Error('Verifikasi PIN terlebih dahulu.');
  payslipSelected=row;const period=String(row.period??''),runId=String(row.runTypeId??'');const target=$('#payslipDetail');if(target)target.innerHTML='<div class="empty-state">Memuat rincian slip…</div>';
  try{const url=session.urlApi+'/api/user/self-service/payslip/detail?period='+encodeURIComponent(period)+'&runtypeid='+encodeURIComponent(runId);const data=unwrapApiObject(await apiJsonWithRefresh('GET',url));const earnings=Array.isArray(data.payslipEarnings)?data.payslipEarnings:[],deductions=Array.isArray(data.payslipDeductions)?data.payslipDeductions:[],additionals=Array.isArray(data.payslipAdditionals)?data.payslipAdditionals:[];const token=String(data.token||'');const downloadUrl=token?session.urlApi+'/public/core-ws/payslip-report/payslip-result?runtypeid='+encodeURIComponent(runId)+'&period='+encodeURIComponent(period)+'&token='+encodeURIComponent(token):'';$('#payslipDetailSubtitle').textContent=`${data.employeeName||''} · ${period}`;target.innerHTML=`<div class="payslip-totals"><div><small>GAJI BERSIH DITERIMA</small><b>${formatMoney(data.jumlahDiterima)}</b></div><div><small>PENGHASILAN KOTOR</small><b>${formatMoney(data.gajiKotor)}</b></div><div><small>TOTAL POTONGAN</small><b>${formatMoney(data.totalPotongan)}</b></div></div>${renderPayslipLines('Pendapatan',earnings,'nameEarning','amountEarning')}${renderPayslipLines('Potongan',deductions,'nameDeduction','amountDeduction')}${renderPayslipLines('Tambahan',additionals,'nameAdditional','amountAdditional')}${downloadUrl?`<a class="primary-btn full payslip-download" href="${esc(downloadUrl)}" target="_blank" rel="noopener">Unduh slip PDF ↗</a>`:''}`;renderPayslipPage();}
  catch(e){target.innerHTML=`<div class="empty-state">Gagal memuat rincian slip: ${esc(e.message||e)}</div>`;throw e;}
}
function renderPayslipLines(title,rows,nameKey,valueKey){if(!rows.length)return '';return `<details class="payslip-lines" open><summary>${esc(title)} (${rows.length})</summary>${rows.map(x=>`<div><span>${esc(x[nameKey]||'—')}</span><b>${formatMoney(x[valueKey])}</b></div>`).join('')}</details>`;}
async function refreshPayslip(){try{if(!payslipUnlocked){$('#payslipPinPanel').hidden=false;showToast('Verifikasi PIN terlebih dahulu.');return;}const b=$('#refreshPayslip');b.disabled=true;await loadPayslipList();showToast('Daftar slip gaji diperbarui.');}catch(e){showToast('Gagal memuat slip gaji: '+e.message);}finally{const b=$('#refreshPayslip');if(b)b.disabled=false;}}
async function loadShiftOptions(){
  if(!session)return;const select=$('#shiftChangeId');if(!select)return;select.innerHTML='<option value="">Memuat pilihan shift…</option>';
  try{const result=await apiJsonWithRefresh('GET',session.urlApi+'/api/lookup/shift/shift-change');shiftOptions=unwrapList(result).map(x=>({id:String(x.id??x.value??''),name:String(x.name??x.label??'')})).filter(x=>x.id&&x.name);select.innerHTML='<option value="">Pilih shift</option>'+shiftOptions.map(x=>`<option value="${esc(x.id)}">${esc(x.name)}</option>`).join('');$('#shiftChangeHelp').textContent=shiftOptions.length?'Pilihan shift berasal dari lookup AgriConnect.':'Server tidak mengembalikan pilihan shift.';}
  catch(e){shiftOptions=[];select.innerHTML='<option value="">Pilihan shift gagal dimuat</option>';$('#shiftChangeHelp').textContent='Gagal memuat lookup: '+readableApiError(e);}
}
async function submitShiftChange(e){
  e.preventDefault();if(!session){showAuth('Login AgriConnect diperlukan.');return;}
  const from=$('#shiftStartDate').value,to=$('#shiftEndDate').value,shiftId=$('#shiftChangeId').value,remark=$('#shiftChangeRemark').value.trim();
  if(!from||!to||from>to||!shiftId||!remark){showToast('Lengkapi rentang tanggal, shift, dan alasan.');return;}
  if(!shiftOptions.some(x=>x.id===shiftId)){showToast('Pilih shift dari daftar resmi AgriConnect.');return;}
  const btn=$('#submitShiftChange');btn.disabled=true;btn.textContent='Mengirim…';
  try{const normalizedShiftId=/^\d+$/.test(shiftId)?Number(shiftId):shiftId;const response=await apiJsonWithRefresh('POST',session.urlApi+'/api/user/self-service/shift-change',{remark,details:[{scheduleDateFrom:from,scheduleDateTo:to,shiftId:normalizedShiftId}]});const ticket=response?.id||response?.data?.id||response?.requestId||'';$('#shiftChangeForm').reset();showToast(ticket?'Permohonan perubahan jam kerja terkirim. ID '+ticket:'Permohonan perubahan jam kerja diterima server.');await loadOfficialCheckInOutStatuses().catch(()=>{});}
  catch(err){showToast('Pengajuan perubahan jam kerja gagal: '+(err.message||err));}
  finally{btn.disabled=false;btn.textContent='Kirim permohonan perubahan jam kerja →';}
}

function statusForTimesheet(row){
  if(row?.isAlpha===true||row?.isAlpha===1||String(row?.isAlpha).toLowerCase()==='true')return 'Absent';
  if(row?.isDayOff===true||row?.isDayOff===1||String(row?.isDayOff).toLowerCase()==='true')return 'Day Off';
  if(row?.holiday!==null&&row?.holiday!==undefined&&row?.holiday!=='')return 'Holiday';
  const category=String(row?.absenceCategory||'').toLowerCase();
  if(category==='permission'||category==='leave')return 'Leave';
  if(category==='sick')return 'Sick';
  const hasIn=row?.actualInTime!==null&&row?.actualInTime!==undefined&&row?.actualInTime!=='';
  const hasOut=row?.actualOutTime!==null&&row?.actualOutTime!==undefined&&row?.actualOutTime!=='';
  if(hasIn&&hasOut){
    const late=row?.isLateIn===true||row?.isLateIn===1||String(row?.isLateIn).toLowerCase()==='true';
    return late?'Late':'On Time';
  }
  return 'No Check In/Out';
}
function statusClass(value){return String(value||'').toLowerCase().replace(/[^a-z]+/g,'-').replace(/^-|-$/g,'')||'unknown';}
function formatApiTime(value){
  if(value==null||value==='')return '—';
  const d=new Date(value);if(!Number.isNaN(d.getTime()))return fmtTime(d).slice(0,5);
  const m=String(value).match(/(?:T|^)(\d{2}:\d{2})/);return m?m[1]:String(value);
}
function getRecordDate(row){const value=row?.startDate||row?.date||row?.attendanceDate||row?.workDate||'';return String(value).slice(0,10);}
async function loadCalendarRows(){
  if(!session)throw new Error('Login AgriConnect diperlukan.');
  const year=calendarMonth.getFullYear(),month=calendarMonth.getMonth();
  const start=localYmd(new Date(year,month,1)),end=localYmd(new Date(year,month+1,0));
  const data=await apiJsonWithRefresh('GET',session.urlApi+'/api/user/time-sheet?start='+encodeURIComponent(start)+'&end='+encodeURIComponent(end));
  calendarRows=unwrapList(data).map(row=>({...row,_date:getRecordDate(row),_displayStatus:statusForTimesheet(row)}));
  renderCalendarGrid();renderCalendarDayDetail(calendarSelectedDate||localYmd(new Date()));
}
async function loadOfficialCheckInOutStatuses(){
  if(!session)throw new Error('Login AgriConnect diperlukan.');
  const statuses=['In Progress','Approved','Rejected','Canceled'];
  const categories=[
    {key:'check-in-out',label:'Koreksi Absen',url:'/api/user/self-service/check-in-out/status/my-request'},
    {key:'Leave',label:'Cuti',url:'/api/user/self-service/absence/status/my-request'},
    {key:'Permission',label:'Izin',url:'/api/user/self-service/absence/status/my-request'},
    {key:'Sick',label:'Sakit',url:'/api/user/self-service/absence/status/my-request'},
    {key:'shift-change',label:'Perubahan jam kerja',url:'/api/user/self-service/shift-change/status/my-request'}
  ];
  const all=[],errors=[];
  // Read-only: pull the employee's own request lists; never call approval/action endpoints.
  for(const category of categories){
    const results=await Promise.allSettled(statuses.map(async status=>{
      const typeQuery=['check-in-out','shift-change'].includes(category.key)?'':'&type='+encodeURIComponent(category.key);
      const orderBy=status==='In Progress'?'ASC':'DESC';
      const url=session.urlApi+category.url+'?status='+encodeURIComponent(status)+typeQuery+'&orderBy='+orderBy;
      return {status,result:await apiJsonWithRefresh('GET',url)};
    }));
    results.forEach((entry,index)=>{
      const status=statuses[index];
      if(entry.status==='rejected'){errors.push(`${category.label} / ${status}: ${entry.reason?.message||entry.reason}`);return;}
      unwrapList(entry.value.result).forEach(item=>all.push({...item,_status:String(item.status||status),_category:category.key,_categoryLabel:category.label}));
    });
  }
  officialCheckInOutRequests=all.sort((a,b)=>{const stamp=x=>{const v=x.requestDateMilliSecond||x.requestDate||x.createdAt||0;const n=Number(v);return Number.isFinite(n)&&n>0?n:(Date.parse(v)||0);};return stamp(b)-stamp(a);});
  requestStatusErrors=errors;renderCorrectionStatusList();
}
function renderCalendarGrid(){
  const grid=$('#calendarGrid'),label=$('#calendarMonthLabel');if(!grid)return;
  const year=calendarMonth.getFullYear(),month=calendarMonth.getMonth();
  if(label)label.textContent=new Intl.DateTimeFormat('id-ID',{month:'long',year:'numeric'}).format(calendarMonth);
  const firstDay=new Date(year,month,1).getDay(),offset=(firstDay+6)%7,days=new Date(year,month+1,0).getDate();
  const map=new Map(calendarRows.map(r=>[r._date,r]));const cells=[];
  for(let i=0;i<offset;i++)cells.push('<div class="calendar-cell empty"></div>');
  for(let day=1;day<=days;day++){
    const date=localYmd(new Date(year,month,day)),row=map.get(date),status=row?row._displayStatus:'No Record';
    const selected=calendarSelectedDate===date?' selected':'';
    cells.push(`<button type="button" class="calendar-cell${selected}" data-calendar-date="${date}" aria-label="${date} ${esc(status)}"><b>${day}</b>${row?`<span class="calendar-dot ${statusClass(status)}"></span><small>${esc(status)}</small>`:'<small class="calendar-empty-label">—</small>'}</button>`);
  }
  grid.innerHTML=['Sen','Sel','Rab','Kam','Jum','Sab','Min'].map(d=>`<div class="calendar-weekday">${d}</div>`).join('')+cells.join('');
}
function renderCalendarDayDetail(date){
  const panel=$('#calendarDayDetail');if(!panel)return;
  calendarSelectedDate=date;const row=calendarRows.find(r=>r._date===date);
  if(!row){panel.innerHTML=`<div class="day-detail-head"><div><small>TANGGAL DIPILIH</small><h3>${esc(formatApiDate(date))}</h3></div><span class="status-pill no-record">Tidak ada data</span></div><p>Tidak ada rekaman timesheet dari AgriConnect untuk tanggal ini.</p>`;return;}
  const status=row._displayStatus;
  const fields=[['Jadwal masuk',formatApiTime(row.startTime)],['Jadwal pulang',formatApiTime(row.endTime)],['Absen masuk aktual',formatApiTime(row.actualInTime)],['Absen pulang aktual',formatApiTime(row.actualOutTime)],['Kategori ketidakhadiran',row.absenceCategory||'—'],['Terlambat',row.isLateIn?'Ya':'Tidak']];
  panel.innerHTML=`<div class="day-detail-head"><div><small>TANGGAL DIPILIH</small><h3>${esc(formatApiDate(date))}</h3></div><span class="status-pill ${statusClass(status)}">${esc(status)}</span></div><div class="day-detail-grid">${fields.map(([k,v])=>`<div><small>${esc(k)}</small><b>${esc(v)}</b></div>`).join('')}</div>`;
}
function renderCorrectionStatusList(){
  const list=$('#officialRequestList');if(!list)return;
  let items=requestStatusFilter==='all'?officialCheckInOutRequests:officialCheckInOutRequests.filter(x=>x._status===requestStatusFilter);
  if(requestCategoryFilter!=='all')items=items.filter(x=>x._category===requestCategoryFilter);
  if(requestStatusErrors.length){$('#requestStatusNotice').textContent='Sebagian data status tidak dapat dimuat: '+requestStatusErrors.join(' · ');}else $('#requestStatusNotice').textContent='Status berasal dari server AgriConnect. Data hanya menampilkan permintaan milik akun yang login.';
  const summary=$('#requestStatusSummary');if(summary){const counts={'In Progress':0,'Approved':0,'Rejected':0,'Canceled':0};officialCheckInOutRequests.forEach(item=>{if(Object.prototype.hasOwnProperty.call(counts,item._status))counts[item._status]++;});summary.innerHTML=[['Menunggu',counts['In Progress']],['Disetujui',counts.Approved],['Ditolak',counts.Rejected],['Dibatalkan',counts.Canceled]].map(([label,count])=>`<div><small>${label}</small><b>${count}</b></div>`).join('');}
  if(!items.length){list.innerHTML='<div class="empty-state">Tidak ada pengajuan pada filter ini.</div>';return;}
  list.innerHTML=items.map(item=>{
    const rawKind=String(item.type||item.checkInOutType||item.requestType||'');
    let kind='Pengajuan '+(item._categoryLabel||'absensi');
    if(item._category==='check-in-out')kind=rawKind==='CHECKIN'?'Koreksi Absen Masuk':rawKind==='CHECKOUT'?'Koreksi Absen Pulang':'Permintaan absensi';else if(item._category==='shift-change')kind='Perubahan jam kerja';
    const start=item.startDateMilliSecond||item.startDate,finish=item.endDateMilliSecond||item.endDate;
    let dateRange=start?`${formatApiDate(start)}${finish?' – '+formatApiDate(finish):''}`:formatApiDate(item.requestDateMilliSecond||item.requestDate||item.createdAt||item.absenceDate);
    if(item._category==='shift-change'&&Array.isArray(item.details)&&item.details.length){dateRange=item.details.map(d=>`${formatApiDate(d.scheduleDateFromMilliSecond||d.scheduleDateFrom||d.scheduleDateFromInMillisecond)} – ${formatApiDate(d.scheduleDateToMilliSecond||d.scheduleDateTo||d.scheduleDateToInMillisecond)}${d.shiftName?' · '+d.shiftName:''}`).join('; ');}
    const note=item.remark||item.purposeName||item.purpose||item.reason||item.actionNote||item.absenceReason||'';
    const ticket=item.requestId||item.dataApprovalId||item.id||'';
    return `<article class="official-request-item"><div class="request-main"><b>${esc(kind)}</b><small>${esc(dateRange)}${ticket?' · ID '+esc(ticket):''}</small>${note?`<p>${esc(note)}</p>`:''}</div><div class="request-status-group"><span class="status-pill ${statusClass(item._status)}">${esc(item._status)}</span></div></article>`;
  }).join('');
}
async function loadCalendarWorkspace(){
  if(!session){showAuth('Login AgriConnect diperlukan untuk menarik kalender absensi dan status koreksi.');return;}
  const label=$('#calendarLoadStatus');if(label)label.textContent='Memuat kalender dan status pengajuan…';
  const btn=$('#refreshCalendar');if(btn)btn.disabled=true;
  calendarLoading=true;
  try{
    const results=await Promise.allSettled([loadCalendarRows()]);
    const failures=results.filter(r=>r.status==='rejected').map(r=>r.reason?.message||'Gagal memuat data');
    if(label)label.textContent=failures.length?'Sebagian data gagal dimuat: '+failures.join(' · '):'Data kalender dan status diperbarui dari AgriConnect.';
  }finally{calendarLoading=false;if(btn)btn.disabled=false;}
}
function setAttendanceType(){const isIn=currentType==='CHECKIN';$$('.segment').forEach(b=>b.classList.toggle('active',b.dataset.attType===currentType));if($('#attendanceTypeTag'))$('#attendanceTypeTag').textContent=isIn?'CHECK IN':'CHECK OUT';if($('#attendanceTitle'))$('#attendanceTitle').textContent=isIn?'Absen Masuk':'Absen Pulang';if($('#attendanceDesc'))$('#attendanceDesc').textContent=isIn?'Kirim permintaan CHECKIN ke AgriConnect.':'Kirim permintaan CHECKOUT ke AgriConnect.';const button=$('#recordAttendance');if(button)button.innerHTML=(isIn?'Kirim Absen Masuk':'Kirim Absen Pulang')+' <span>→</span>';}
function setAttendanceMode(mode){attendanceMode=mode==='outOffice'?'outOffice':'inOffice';$$('[data-attendance-mode]').forEach(b=>b.classList.toggle('active',b.dataset.attendanceMode===attendanceMode));const outside=attendanceMode==='outOffice';const purpose=$('#attendancePurposeBlock'),noteBlock=$('#attendanceNoteBlock'),noteHelp=$('#attendanceNoteHelp'),officeRemark=$('#officeRemarkInfo'),badge=$('.mode-badge');if(purpose)purpose.hidden=!outside;if(noteBlock)noteBlock.hidden=!outside;if(noteHelp)noteHelp.textContent='Keterangan ini dikirim sebagai remark permintaan di luar kantor.';if(officeRemark)officeRemark.hidden=outside;if(badge)badge.innerHTML='<span></span> '+(outside?'DI LUAR KANTOR':'DI KANTOR');}

async function recordAttendance(){
  if(!session){showAuth('Login AgriConnect diperlukan sebelum mengirim absensi.');return;}
  if(faceRecognitionRequired){showToast('Akun memerlukan face recognition khusus. Gunakan AgriConnect resmi; aplikasi tidak melewati verifikasi wajah.');return;}
  const button=$('#recordAttendance');button.disabled=true;button.textContent='Memproses…';
  try{
    const isOutside=attendanceMode==='outOffice';
    if(!isOutside && !companyLocation && !companyLocationResponseReceived){
      try{await loadOfficialCompanyLocation();}
      catch(locationError){if(!companyLocationResponseReceived)throw locationError;}
    }
    const gps=await getGps();verifyMockLocation(gps);
    if(!isOutside&&companyLocation){const distance=distanceToOffice(gps);if(distance>companyLocation.toleranceInMeter)throw new Error(`Posisi GPS berada ${Math.round(distance-companyLocation.toleranceInMeter)} meter di luar radius kantor. Permintaan tidak dikirim.`);}
    else if(!isOutside&&companyLocationResponseReceived){if($('#gpsDistance'))$('#gpsDistance').textContent='Radius kantor tidak dikirim tenant; server akan memutuskan hasilnya.';}
    else if(!isOutside&&!companyLocationResponseReceived)throw new Error(companyLocationError||'Data lokasi kantor gagal dihubungi. Coba muat ulang sebelum mengirim absensi.');
    const purpose=isOutside?($('#attendancePurpose')?.value||''):'';
    const remarkInput=$('#attendanceNote')?.value.trim()||'';
    if(isOutside&&!purpose)throw new Error('Pilih keperluan dari daftar AgriConnect untuk mode Di luar kantor.');
    if(isOutside&&!remarkInput)throw new Error('Isi keterangan untuk permintaan Di luar kantor.');
    if(isOutside&&!correctionPurposes.some(p=>p.value===purpose))throw new Error('Keperluan tidak termasuk pilihan resmi AgriConnect.');
    const photo=$('#attendancePhoto').files[0]||null;
    if(selfieRequired&&!photo)throw new Error('Tenant mewajibkan foto selfie. Ambil atau pilih foto terlebih dahulu.');
    if(photo&&photo.size>15*1024*1024)throw new Error('Ukuran file foto maksimal 15 MB sebelum kompresi.');
    if(photo&&!/^image\//i.test(photo.type))throw new Error('Lampiran absensi harus berupa gambar.');
    let device={};try{device=JSON.parse(AndroidBridge.getDeviceInfo()||'{}');}catch(_){device={};}
    const payload={actualLatEnc:btoa(String(gps.latitude)),actualLngEnc:btoa(String(gps.longitude)),outOfOffice:isOutside,purpose,remark:isOutside?remarkInput:'In The Office',type:currentType,scApps:CLIENT_APP,scVersion:CLIENT_VERSION,deviceId:device.deviceId||'',deviceName:device.deviceName||'',deviceModel:device.deviceModel||'',devicePlatform:'android',deviceOs:'android',deviceOsVersion:device.deviceOsVersion||'',deviceAndroidSdkVersion:device.deviceAndroidSdkVersion||'',deviceManufacturer:device.deviceManufacturer||'',deviceVirtual:!!device.deviceVirtual,deviceWebviewVersion:device.deviceWebviewVersion||''};
    const requestBody={checkinout:base64Utf8(JSON.stringify(payload))};
    if(photo){const compressed=await compressPhoto(photo);requestBody.fileName=(photo.name||'attendance').replace(/\.[^.]+$/,'')+'.jpg';requestBody.photoMimeType='image/jpeg';requestBody.photoBase64=await fileToBase64(compressed);}
    const response=await apiJsonWithRefresh('POST',session.urlApi+'/api/user/self-service/check-in-out/v1/request',requestBody,'attendance');
    const ticket=response?.id||response?.requestId||response?.ticketNo||response?.ticketNumber||response?.data?.id||response?.data?.requestId||'';
    const rec={id:crypto.randomUUID(),kind:currentType,recordedAt:gps.capturedAt,latitude:gps.latitude,longitude:gps.longitude,accuracy:gps.accuracy,locationName:isOutside?'Di luar kantor':'Di kantor',serverTicket:String(ticket||''),serverStatus:'Terkirim',remark:isOutside?remarkInput:'In The Office',photoName:photo?.name||''};
    state.attendance.unshift(rec);persist();$('#ticketTitle').textContent='Permintaan absensi terkirim';$('#ticketMessage').textContent=currentType==='CHECKIN'?'Respons CHECKIN diterima server AgriConnect.':'Respons CHECKOUT diterima server AgriConnect.';$('#ticketNumber').textContent=ticket?String(ticket):'Server tidak mengembalikan nomor tiket.';$('#copyTicket').disabled=!ticket;$('#ticketDialog').showModal();
    $('#attendancePhoto').value='';$('#attendanceNote').value='';if($('#attendancePurpose'))$('#attendancePurpose').value='';$('#photoPreview').hidden=true;showToast('Absensi berhasil dikirim ke AgriConnect.');
  }catch(e){showToast(e.message||'Pengiriman absensi gagal.');}
  finally{button.disabled=false;setAttendanceType();}
}

function renderAll(){
  $('#welcomeName').textContent=state.profile.name?`, ${state.profile.name.split(' ')[0]}`:'!';$('#profileShortcut').textContent=initials(state.profile.name);$('#statToday').textContent=state.attendance.filter(a=>new Date(a.recordedAt).toDateString()===new Date().toDateString()).length;
  $('#statLocations').textContent=state.locations.length;$('#statCorrections').textContent=state.corrections.length;
  if(state.noticeDismissed) $('.notice-banner:not(.compact-notice)').style.display='none';
  renderRecent();renderHistory();renderLocations();renderVervalPage();updateGpsUI();
}
function combinedHistory(){return [...state.attendance.map(x=>({...x,category:'attendance'})),...state.corrections.map(x=>({...x,category:'correction'}))].sort((a,b)=>new Date(b.recordedAt||b.createdAt)-new Date(a.recordedAt||a.createdAt));}
function renderRecent(){const list=$('#recentList');const items=combinedHistory().slice(0,4);if(!items.length){list.innerHTML='<div class="empty-state"><span class="empty-icon">▤</span>Belum ada aktivitas. Mulai dengan membuat catatan absensi atau pengajuan koreksi.</div>';return;}list.innerHTML=items.map(x=>`<div class="recent-item"><span class="list-icon">${x.category==='attendance'?'◷':'✎'}</span><div class="list-copy"><b>${x.category==='attendance'?(x.kind==='CHECKIN'?'Absen Masuk':'Absen Pulang'):'Pengajuan Koreksi '+(x.kind==='CHECKIN'?'Masuk':'Pulang')}</b><small>${esc(x.locationName||'Lokasi belum tersedia')} · ${fmtDate(x.recordedAt||x.createdAt,{day:'2-digit',month:'short'})} ${fmtTime(x.recordedAt||x.createdAt)}</small></div><span class="list-status">${x.category==='attendance'?'Terkirim':(x.serverStatus||'Draf lokal')}</span></div>`).join('');}
function renderHistory(){const list=$('#historyList');let items=combinedHistory();if(historyFilter!=='all')items=items.filter(x=>x.category===historyFilter);if(!items.length){list.innerHTML='<div class="empty-state"><span class="empty-icon">▤</span>Tidak ada data pada kategori ini.</div>';return;}list.innerHTML=items.map(x=>{const date=x.recordedAt||x.createdAt;const correction=x.category==='correction';return `<div class="history-item"><span class="list-icon">${correction?'✎':'◷'}</span><div class="history-detail"><b>${correction?'Koreksi '+(x.kind==='CHECKIN'?'Masuk':'Pulang'):'Absen '+(x.kind==='CHECKIN'?'Masuk':'Pulang')}</b><small>${esc(x.locationName||'Lokasi tidak dipilih')} · ${fmtDate(date,{day:'2-digit',month:'long',year:'numeric'})}, ${fmtTime(date)}</small>${correction?`<div class="history-extra">Waktu dimohonkan: ${esc(x.requestedDate)} ${esc(x.requestedTime)} · ${esc(x.reason.slice(0,95))} · Tiket: ${esc(x.serverTicket||'Tidak dikembalikan')}</div>`:`<div class="history-extra">GPS ${Number(x.latitude).toFixed(5)}, ${Number(x.longitude).toFixed(5)} · Akurasi ±${Math.round(x.accuracy)} m · Tiket: ${esc(x.serverTicket||'Tidak dikembalikan')}</div>`}</div><span class="history-status">${correction?(x.serverStatus||'Draf lokal'):'Terkirim'}</span></div>`;}).join('');}
function renderLocations(){const grid=$('#locationsGrid');if(!state.locations.length){grid.innerHTML='<div class="panel empty-state" style="grid-column:1/-1"><span class="empty-icon">⌖</span>Belum ada lokasi kerja. Tambahkan titik lokasi yang ditetapkan perusahaan.</div>';return;}grid.innerHTML=state.locations.map(l=>`<article class="location-card"><div class="location-card-top"><span class="loc-symbol">⌖</span><div class="loc-actions"><button class="icon-btn" data-edit-location="${esc(l.id)}" aria-label="Edit lokasi">✎</button><button class="icon-btn" data-delete-location="${esc(l.id)}" aria-label="Hapus lokasi">×</button></div></div><h3>${esc(l.name)}</h3><p>${esc(l.address||'Tidak ada keterangan alamat')}</p><div class="location-coords">${Number(l.lat).toFixed(6)}, ${Number(l.lng).toFixed(6)}</div><div class="location-card-foot"><span>Lokasi referensi</span><a target="_blank" rel="noopener" href="https://www.openstreetmap.org/?mlat=${l.lat}&mlon=${l.lng}#map=16/${l.lat}/${l.lng}">Buka peta ↗</a></div></article>`).join('');}
function openLocationEditor(loc=null){$('#locationEditor').hidden=false;$('#locationEditorTitle').textContent=loc?'Edit lokasi kerja':'Tambah lokasi kerja';$('#locationId').value=loc?.id||'';$('#locationName').value=loc?.name||'';$('#locationAddress').value=loc?.address||'';$('#locationLat').value=loc?.lat??'';$('#locationLng').value=loc?.lng??'';$('#locationEditor').scrollIntoView({behavior:'smooth',block:'center'});}
async function saveCorrection(e){
  e.preventDefault();
  if(!session){showAuth('Login AgriConnect diperlukan untuk mengirim koreksi resmi.');return;}
  const date=$('#correctionDate').value,time=$('#correctionTime').value,purpose=$('#correctionPurpose').value,reason=$('#correctionReason').value.trim();
  if(!date||!time||!purpose||!reason){showToast('Lengkapi tanggal, jam, keperluan, dan alasan.');return;}
  if(!correctionPurposes.some(p=>p.value===purpose)){showToast('Pilih keperluan yang disediakan server AgriConnect.');return;}
  const files=[...($('#correctionAttachment').files||[])];
  for(const file of files){if(file.size>=5*1024*1024){showToast(`Ukuran setiap lampiran harus di bawah 5 MB: ${file.name}`);return;}}
  const button=$('#submitCorrection');button.disabled=true;button.textContent='Mengirim pengajuan…';
  try{
    const device=JSON.parse(AndroidBridge.getDeviceInfo()||'{}');
    const payload={
      deviceId:device.deviceId||'',deviceName:device.deviceName||'',deviceModel:device.deviceModel||'',
      devicePlatform:'android',deviceOs:'android',deviceOsVersion:device.deviceOsVersion||'',
      deviceAndroidSdkVersion:device.deviceAndroidSdkVersion||'',deviceManufacturer:device.deviceManufacturer||'',
      deviceVirtual:!!device.deviceVirtual,deviceWebviewVersion:device.deviceWebviewVersion||'',
      outOfOffice:false,purpose,remark:reason,scApps:CLIENT_APP,scVersion:CLIENT_VERSION,
      type:$('#correctionType').value,
      absenceDate:`${date}T${time}:00`
    };
    const requestBody={checkinout:base64Utf8(JSON.stringify(payload)),files:[]};
    for(const file of files){requestBody.files.push({fileName:file.name,fileMimeType:file.type||'application/octet-stream',fileBase64:await fileToBase64(file)});}
    const response=await apiJsonWithRefresh('POST',session.urlApi+'/api/user/self-service/check-in-out/backdate/v1/request',requestBody,'attendance');
    const ticket=response?.id||response?.requestId||response?.ticketNo||response?.ticketNumber||response?.data?.id||response?.data?.requestId||'';
    const item={id:crypto.randomUUID(),kind:payload.type,createdAt:new Date().toISOString(),requestedDate:date,requestedTime:time,purpose,reason,attachmentNames:files.map(f=>f.name),serverTicket:String(ticket||''),serverStatus:'Terkirim',locationName:'Di kantor'};
    state.corrections.unshift(item);persist();e.target.reset();
    $('#ticketTitle').textContent='Pengajuan koreksi terkirim';$('#ticketMessage').textContent='Respons pengajuan backdate diterima oleh server AgriConnect.';
    $('#ticketNumber').textContent=ticket?String(ticket):'Server menerima permintaan, tetapi tidak mengembalikan nomor tiket pada respons.';
    $('#copyTicket').disabled=!ticket;$('#ticketDialog').showModal();showToast('Pengajuan koreksi berhasil dikirim ke AgriConnect.');route('history');
  }catch(err){showToast('Pengajuan koreksi gagal: '+err.message);}
  finally{button.disabled=false;button.textContent='Kirim pengajuan koreksi →';}
}
function exportData(){const data={app:'AgriAttend',version:CLIENT_VERSION,exportedAt:new Date().toISOString(),note:'Data riwayat lokal pendamping. Token dan password tidak disertakan.',...state};const filename=`agriattend-export-${new Date().toISOString().slice(0,10)}.json`;const payload=JSON.stringify(data,null,2);if(window.AndroidBridge&&typeof window.AndroidBridge.saveTextFile==='function'){window.AndroidBridge.saveTextFile(filename,payload);showToast('Ekspor dikirim ke folder Download/AgriAttend.');return;}const blob=new Blob([payload],{type:'application/json'});const url=URL.createObjectURL(blob);const a=document.createElement('a');a.href=url;a.download=filename;a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);showToast('Ekspor data berhasil dibuat.');}
function bindEvents(){
  document.addEventListener('click',e=>{
    const nav=e.target.closest('[data-route]');if(nav){e.preventDefault();route(nav.dataset.route);if(nav.dataset.kind){currentType=nav.dataset.kind;setAttendanceType();}return;}
    const edit=e.target.closest('[data-edit-location]');if(edit){openLocationEditor(state.locations.find(l=>l.id===edit.dataset.editLocation));return;}
    const del=e.target.closest('[data-delete-location]');if(del){if(confirm('Hapus lokasi ini dari daftar lokal?')){state.locations=state.locations.filter(l=>l.id!==del.dataset.deleteLocation);persist();showToast('Lokasi dihapus.');}return;}
    const pay=e.target.closest('[data-payslip-index]');if(pay){const row=payslipRows[Number(pay.dataset.payslipIndex)];if(row)loadPayslipDetail(row).catch(err=>showToast('Gagal memuat detail slip: '+err.message));return;}
  });
  $$('.segment').forEach(btn=>btn.addEventListener('click',()=>{currentType=btn.dataset.attType;setAttendanceType();}));
  $('#refreshGPS').addEventListener('click',()=>getGps().then(()=>showToast('GPS aktual berhasil diperbarui.')).catch(e=>showToast(e.message)));
  $('#recordAttendance').addEventListener('click',recordAttendance);
  $('#unlockPayslip').addEventListener('click',verifyPayslipPin);$('#refreshPayslip').addEventListener('click',refreshPayslip);
  $('#payslipPin').addEventListener('keydown',e=>{if(e.key==='Enter'){e.preventDefault();verifyPayslipPin();}});
  $('#shiftChangeForm').addEventListener('submit',submitShiftChange);
  $('#refreshRequestStatuses').addEventListener('click',()=>loadOfficialCheckInOutStatuses().then(()=>showToast('Status pengajuan diperbarui.')).catch(e=>showToast('Gagal memuat status: '+readableApiError(e))));
  $('#vervalSearch').addEventListener('input',renderVervalPage);$('#vervalBatchFilter').addEventListener('change',renderVervalPage);$('#vervalStatusFilter').addEventListener('change',renderVervalPage);
  $('#refreshCompanyLocation').addEventListener('click',()=>loadOfficialCompanyLocation().then(()=>showToast('Lokasi kantor berhasil dimuat dari AgriConnect.')).catch(e=>showToast(readableApiError(e))));
  $$('[data-attendance-mode]').forEach(b=>b.addEventListener('click',()=>setAttendanceMode(b.dataset.attendanceMode)));
  $('#attendancePhoto').addEventListener('change',()=>{const f=$('#attendancePhoto').files[0];const p=$('#photoPreview');p.innerHTML='';if(!f){p.hidden=true;return;}if(!f.type.startsWith('image/')){showToast('Pilih berkas gambar.');$('#attendancePhoto').value='';return;}const img=document.createElement('img');img.src=URL.createObjectURL(f);p.appendChild(img);p.hidden=false;});
  $('#correctionForm').addEventListener('submit',saveCorrection);$('#exportAll').addEventListener('click',exportData);
  $$('[data-history-filter]').forEach(b=>b.addEventListener('click',()=>{historyFilter=b.dataset.historyFilter;$$('[data-history-filter]').forEach(x=>x.classList.toggle('active',x===b));renderHistory();}));
  $('#clearHistory').addEventListener('click',()=>{if(confirm('Hapus seluruh catatan dan draf koreksi lokal? Tindakan ini tidak dapat dibatalkan.')){state.attendance=[];state.corrections=[];persist();showToast('Riwayat lokal dihapus.');}});
  $('#addLocationBtn').addEventListener('click',()=>openLocationEditor());$('#closeLocationEditor').addEventListener('click',()=>$('#locationEditor').hidden=true);$('#cancelLocation').addEventListener('click',()=>$('#locationEditor').hidden=true);
  $('#locationForm').addEventListener('submit',e=>{e.preventDefault();const id=$('#locationId').value;const lat=Number($('#locationLat').value),lng=Number($('#locationLng').value);if(!Number.isFinite(lat)||lat < -90||lat>90||!Number.isFinite(lng)||lng < -180||lng>180){showToast('Koordinat tidak valid.');return;}const item={id:id||crypto.randomUUID(),name:$('#locationName').value.trim(),address:$('#locationAddress').value.trim(),lat,lng};if(id)state.locations=state.locations.map(x=>x.id===id?item:x);else state.locations.push(item);persist();$('#locationEditor').hidden=true;$('#locationForm').reset();showToast('Lokasi kerja tersimpan.');});
  $('#dismissNotice').addEventListener('click',()=>{state.noticeDismissed=true;persist();});
  $('#profileShortcut').addEventListener('click',()=>route('profile'));
  $('#refreshCalendar').addEventListener('click',loadCalendarWorkspace);
  $('#calendarMonth').addEventListener('change',e=>{if(e.target.value){const [y,m]=e.target.value.split('-').map(Number);calendarMonth=new Date(y,m-1,1);calendarSelectedDate=null;loadCalendarRows().catch(err=>showToast('Gagal memuat kalender: '+err.message));}});
  $('#calendarPrev').addEventListener('click',()=>{calendarMonth=new Date(calendarMonth.getFullYear(),calendarMonth.getMonth()-1,1);$('#calendarMonth').value=`${calendarMonth.getFullYear()}-${String(calendarMonth.getMonth()+1).padStart(2,'0')}`;calendarSelectedDate=null;loadCalendarRows().catch(err=>showToast(err.message));});
  $('#calendarNext').addEventListener('click',()=>{calendarMonth=new Date(calendarMonth.getFullYear(),calendarMonth.getMonth()+1,1);$('#calendarMonth').value=`${calendarMonth.getFullYear()}-${String(calendarMonth.getMonth()+1).padStart(2,'0')}`;calendarSelectedDate=null;loadCalendarRows().catch(err=>showToast(err.message));});
  $('#calendarGrid').addEventListener('click',e=>{const cell=e.target.closest('[data-calendar-date]');if(cell){renderCalendarGrid();renderCalendarDayDetail(cell.dataset.calendarDate);renderCalendarGrid();}});
  $$('[data-request-status]').forEach(b=>b.addEventListener('click',()=>{requestStatusFilter=b.dataset.requestStatus;$$('[data-request-status]').forEach(x=>x.classList.toggle('active',x===b));renderCorrectionStatusList();}));
  $$('[data-request-category]').forEach(b=>b.addEventListener('click',()=>{requestCategoryFilter=b.dataset.requestCategory;$$('[data-request-category]').forEach(x=>x.classList.toggle('active',x===b));renderCorrectionStatusList();}));
  $('#refreshProfile').addEventListener('click',refreshProfileData);
  $('#saveVervalPassword').addEventListener('click',()=>{const input=$('#vervalPassword'),value=input.value;if(!value){showToast('Masukkan passcode pemantauan terlebih dahulu.');return;}if(!window.AndroidBridge||!AndroidBridge.saveVervalPassword){showToast('Penyimpanan aman hanya tersedia pada aplikasi Android.');return;}if(AndroidBridge.saveVervalPassword(value)){input.value='';renderVervalPage();setVervalStatus('Passcode berhasil disimpan terenkripsi pada perangkat. Tekan Refresh Data Verval untuk pengecekan otomatis.');showToast('Akses Simkopdes tersimpan aman.');}else showToast('Passcode tidak dapat disimpan secara aman.');});
  $('#clearVervalPassword').addEventListener('click',()=>{if(!window.AndroidBridge||!AndroidBridge.clearVervalPassword){showToast('Pengelolaan akses tersedia pada aplikasi Android.');return;}if(confirm('Hapus passcode Simkopdes yang tersimpan terenkripsi di perangkat ini?')){AndroidBridge.clearVervalPassword();$('#vervalPassword').value='';renderVervalPage();setVervalStatus('Passcode tersimpan telah dihapus dari perangkat.');showToast('Akses Simkopdes dihapus.');}});
  $('#refreshVervalData').addEventListener('click',refreshVervalData);
  $('#loginContinue').addEventListener('click',preflightLogin);$('#authForm').addEventListener('submit',submitLogin);
  $('#backToUsername').addEventListener('click',()=>{$('#credentialStep').hidden=true;$('#authActions').hidden=true;$('#loginContinue').hidden=false;$('#loginPassword').value='';preflightNeedsOtp=false;loginMessage('');});
  $('#logoutBtn').addEventListener('click',logout);
  $('#closeTicket').addEventListener('click',()=>$('#ticketDialog').close());
  $('#copyTicket').addEventListener('click',async()=>{const value=$('#ticketNumber').textContent;try{await navigator.clipboard.writeText(value);showToast('Nomor tiket disalin.');}catch(_){showToast('Tidak dapat menyalin nomor tiket.');}});
  $('#correctionDate').max=new Date().toISOString().slice(0,10);
  $('#shiftStartDate').min=new Date().toISOString().slice(0,10);$('#shiftEndDate').min=new Date().toISOString().slice(0,10);
  $('#shiftStartDate').addEventListener('change',()=>{if($('#shiftEndDate').value&&$('#shiftEndDate').value<$('#shiftStartDate').value)$('#shiftEndDate').value=$('#shiftStartDate').value;$('#shiftEndDate').min=$('#shiftStartDate').value||new Date().toISOString().slice(0,10);});
}
async function init(){
  clockTick();setInterval(clockTick,1000);calendarMonth=new Date(new Date().getFullYear(),new Date().getMonth(),1);const monthInput=$('#calendarMonth');if(monthInput)monthInput.value=`${calendarMonth.getFullYear()}-${String(calendarMonth.getMonth()+1).padStart(2,'0')}`;bindEvents();renderAll();setAttendanceType();setAttendanceMode('inOffice');
  if('serviceWorker'in navigator && location.protocol.startsWith('http'))navigator.serviceWorker.register('./sw.js').catch(()=>{});
  await restoreSession();
}
init();
