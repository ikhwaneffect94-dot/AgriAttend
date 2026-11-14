(function (cfg) {
  'use strict';
  if (window.__agriVervalAutomationRun === cfg.runId) return;
  window.__agriVervalAutomationRun = cfg.runId;

  const bridge = window.VervalPortalBridge;
  const seen = new Map();
  let clickedDistrict = false;
  let clickedProvince = false;
  let passwordAttempted = false;
  let lastProgress = '';
  let lastPageSignature = '';
  let lastPageChangeAt = Date.now();
  let nextPageClicks = 0;
  let lastClickedPageSignature = '';
  let pendingNextAt = 0;
  let expectedTotal = 0;
  let idleTicks = 0;
  let finished = false;
  const startedAt = Date.now();

  const say = (message) => {
    if (message !== lastProgress) {
      lastProgress = message;
      try { bridge && bridge.onStatus(String(message)); } catch (_) {}
    }
  };
  const norm = (value) => String(value || '')
    .normalize('NFD').replace(/[\u0300-\u036f]/g, '')
    .toLowerCase().replace(/\b(kabupaten|kab\.?|kota administrasi|kota|provinsi|prov\.?)\b/g, ' ')
    .replace(/[^a-z0-9]+/g, ' ').trim().replace(/\s+/g, ' ');
  const visible = (el) => {
    if (!el || !el.getBoundingClientRect) return false;
    const r = el.getBoundingClientRect(), s = getComputedStyle(el);
    return r.width > 0 && r.height > 0 && s.display !== 'none' && s.visibility !== 'hidden' && Number(s.opacity || 1) > 0.05;
  };
  const cleanText = (el) => String(el?.innerText || el?.textContent || '').replace(/\s+/g, ' ').trim();

  function clickRegion(label, provinceMode) {
    const target = norm(label);
    if (!target) return false;
    const selectors = 'tr,[role="row"],a,button,[role="button"],li,.accordion-item,.list-group-item,.card';
    const regionMatch = (txt) => {
      if (txt === target) return true;
      if (!txt.startsWith(target + ' ')) return false;
      const remainder = txt.slice(target.length + 1);
      const province = norm(cfg.provinsi || '');
      const knownProvinces = ['Aceh','Sumatera Utara','Sumatera Barat','Riau','Kepulauan Riau','Jambi','Sumatera Selatan','Kepulauan Bangka Belitung','Bengkulu','Lampung','DKI Jakarta','Jawa Barat','Banten','Jawa Tengah','DI Yogyakarta','Jawa Timur','Bali','Nusa Tenggara Barat','Nusa Tenggara Timur','Kalimantan Barat','Kalimantan Tengah','Kalimantan Selatan','Kalimantan Timur','Kalimantan Utara','Sulawesi Utara','Gorontalo','Sulawesi Tengah','Sulawesi Barat','Sulawesi Selatan','Sulawesi Tenggara','Maluku','Maluku Utara','Papua','Papua Barat','Papua Selatan','Papua Tengah','Papua Pegunungan','Papua Barat Daya'].map(norm);
      const provinceSuffix = (province && (remainder === province || remainder.startsWith(province + ' '))) || knownProvinces.some(name => remainder === name || remainder.startsWith(name + ' '));
      return /^\d/.test(remainder) || /^(total|data|jumlah|koperasi)\b/.test(remainder) || provinceSuffix;
    };
    const candidates = Array.from(document.querySelectorAll(selectors)).filter(el => {
      if (!visible(el)) return false;
      const txt = norm(cleanText(el));
      return txt && regionMatch(txt) && txt.length < 220;
    }).sort((a, b) => cleanText(a).length - cleanText(b).length);
    for (const candidate of candidates) {
      if (candidate.dataset.agriVervalClicked === cfg.runId) continue;
      const control = candidate.matches('a,button,[role="button"]') ? candidate : candidate.querySelector('a,button,[role="button"]');
      const clickable = control || candidate;
      if (!clickable || !visible(clickable)) continue;
      clickable.dataset.agriVervalClicked = cfg.runId;
      clickable.click();
      if (provinceMode) clickedProvince = true; else clickedDistrict = true;
      say(provinceMode ? 'Memilih provinsi penugasan…' : 'Memilih kabupaten/kota penugasan…');
      return true;
    }
    return false;
  }

  function fillPassword() {
    let fields = Array.from(document.querySelectorAll('input[type="password"]')).filter(visible);
    if (!fields.length) fields = Array.from(document.querySelectorAll('input:not([type="hidden"]),textarea')).filter(el => {
      if (!visible(el)) return false;
      const label = [el.name,el.id,el.placeholder,el.getAttribute('aria-label'),el.autocomplete].join(' ').toLowerCase();
      return /pass|password|passcode|kode.?akses/.test(label);
    });
    if (!fields.length) return false;
    const field = fields[fields.length - 1];
    const context = cleanText(field.closest('[role="dialog"],.modal,form,.swal2-popup') || field.parentElement?.parentElement || field.parentElement);
    const hint = [field.name,field.id,field.placeholder,field.getAttribute('aria-label'),context].join(' ').toLowerCase();
    if (!clickedDistrict && !detailContainer() && !/passcode|password|kata sandi|kode akses|akses data/i.test(hint)) return false;
    if (!cfg.password) {
      say('Portal meminta passcode. Simpan passcode di AgriAttend lalu tekan Refresh kembali.');
      try { bridge && bridge.onNeedPassword(); } catch (_) {}
      return true;
    }
    if (passwordAttempted) return true;
    passwordAttempted = true;
    const valueProto = field instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    const setter = Object.getOwnPropertyDescriptor(valueProto, 'value')?.set;
    if (setter) setter.call(field, cfg.password); else field.value = cfg.password;
    field.dispatchEvent(new Event('input', { bubbles: true }));
    field.dispatchEvent(new Event('change', { bubbles: true }));
    const form = field.form || field.closest('form');
    let submit = form?.querySelector('button[type="submit"],input[type="submit"]');
    if (!submit) {
      const area = field.closest('[role="dialog"],.modal,.swal2-popup') || field.parentElement?.parentElement || document;
      submit = Array.from(area.querySelectorAll('button,a,[role="button"]')).find(el => visible(el) && /akses|verifikasi|lanjut|masuk|submit|ok|konfirmasi/i.test(cleanText(el)));
    }
    say('Mengisi passcode verval secara otomatis…');
    setTimeout(() => {
      try {
        if (submit && visible(submit)) submit.click();
        else if (form && typeof form.requestSubmit === 'function') form.requestSubmit();
        else if (form) form.submit();
      } catch (e) { try { bridge && bridge.onError('Tidak dapat mengirim passcode pada form portal: ' + (e.message || e)); } catch (_) {} }
    }, 250);
    return true;
  }

  function parseCoordinates(value) {
    const match = String(value || '').match(/(-?\d{1,2}\.\d+)\s*[,; ]\s*(-?\d{1,3}\.\d+)/);
    if (!match) return { latitude: null, longitude: null };
    const latitude = Number(match[1]), longitude = Number(match[2]);
    if (!Number.isFinite(latitude) || latitude < -90 || latitude > 90 || !Number.isFinite(longitude) || longitude < -180 || longitude > 180) return { latitude: null, longitude: null };
    return { latitude, longitude };
  }

  function detailContainer() {
    const headings = Array.from(document.querySelectorAll('h1,h2,h3,h4,[role="heading"],.modal-title'))
      .filter(visible).filter(el => /daftar koperasi.*verifikasi.*validasi|daftar koperasi.*verval/i.test(cleanText(el)));
    if (headings.length) return headings[0].closest('[role="dialog"],.modal,.modal-content,section,article') || headings[0].parentElement?.parentElement || document;
    const text = document.body?.innerText || '';
    if (/daftar koperasi verifikasi\s*&\s*validasi/i.test(text) && /kabupaten\/kota/i.test(text)) return document;
    return null;
  }

  function extractRows(container) {
    const tables = Array.from(container.querySelectorAll('table'));
    for (const table of tables) {
      const headerRow = Array.from(table.querySelectorAll('tr')).find(tr => /nama koperasi/i.test(cleanText(tr)) && /hasil pemeriksaan/i.test(cleanText(tr)));
      if (!headerRow) continue;
      const headers = Array.from(headerRow.querySelectorAll('th,td')).map(cell => norm(cleanText(cell)));
      const nameIndex = Math.max(0, headers.findIndex(x => x.includes('nama koperasi')));
      const statusIndex = headers.findIndex(x => x.includes('hasil pemeriksaan'));
      const coordIndex = headers.findIndex(x => x.includes('koordinat'));
      const mapIndex = headers.findIndex(x => x.includes('peta'));
      const bodyRows = Array.from(table.querySelectorAll('tbody tr')).length
        ? Array.from(table.querySelectorAll('tbody tr'))
        : Array.from(table.querySelectorAll('tr')).filter(tr => tr !== headerRow && tr.querySelectorAll('td').length >= 2);
      const records = [];
      for (const row of bodyRows) {
        if (!visible(row)) continue;
        const cells = Array.from(row.querySelectorAll('td'));
        if (cells.length < 2) continue;
        const nameCell = cells[nameIndex] || cells[1] || cells[0];
        const nameRaw = cleanText(nameCell);
        const batchMatch = (cleanText(row).match(/batch\s*([1-4])/i) || nameRaw.match(/batch\s*([1-4])/i));
        const batch = batchMatch ? Number(batchMatch[1]) : null;
        const name = nameRaw.replace(/batch\s*[1-4]/ig, '').replace(/\s+/g, ' ').trim();
        if (!name || /nama koperasi/i.test(name)) continue;
        const status = cleanText(cells[statusIndex >= 0 ? statusIndex : 2] || '');
        const coordText = cleanText(cells[coordIndex >= 0 ? coordIndex : 3] || row);
        const coords = parseCoordinates(coordText);
        const mapCell = cells[mapIndex >= 0 ? mapIndex : 4] || row;
        const mapAnchor = Array.from(mapCell.querySelectorAll('a[href]')).find(a => /google\.[^/]+\/maps|maps\.google|goo\.gl\/maps/i.test(a.href))
          || Array.from(row.querySelectorAll('a[href]')).find(a => /google\.[^/]+\/maps|maps\.google|goo\.gl\/maps/i.test(a.href));
        const record = {
          name,
          batch,
          resultStatus: status || 'Status tidak terbaca',
          latitude: coords.latitude,
          longitude: coords.longitude,
          mapUrl: mapAnchor?.href || (coords.latitude !== null ? `https://www.google.com/maps?q=${coords.latitude},${coords.longitude}` : ''),
          sourceText: cleanText(row)
        };
        const key = norm(record.name) + '|' + String(record.batch || '') + '|' + norm(record.resultStatus);
        seen.set(key, record);
        records.push(record);
      }
      if (records.length) return records;
    }
    // Responsive/mobile layouts may render each cooperative as a card instead of a table.
    const cards = Array.from(container.querySelectorAll('[role="row"],.card,.list-group-item,article,[data-row]')).filter(visible);
    const fallback=[];
    for (const card of cards) {
      const text=cleanText(card);
      if(!/batch\s*[1-4]/i.test(text) || !/layak|tidak layak|catatan|non.?prioritas|hasil pemeriksaan/i.test(text)) continue;
      const batchMatch=text.match(/batch\s*([1-4])/i), batch=batchMatch?Number(batchMatch[1]):null;
      const anchors=Array.from(card.querySelectorAll('a[href]'));
      const mapAnchor=anchors.find(a=>/google\.[^/]+\/maps|maps\.google|goo\.gl\/maps/i.test(a.href));
      const coords=parseCoordinates(text);
      const statusMatch=text.match(/(Layak[^\n]*|Tidak layak[^\n]*|Non.?prioritas[^\n]*|Belum[^\n]*verval[^\n]*|Sudah[^\n]*verval[^\n]*)/i);
      const nameCandidates=Array.from(card.querySelectorAll('b,strong,h3,h4,[class*=name],[class*=title]')).map(cleanText).filter(Boolean);
      let name=(nameCandidates.find(x=>x.length>8)||text.split(/batch\s*[1-4]/i)[0]||'').replace(/\s+/g,' ').trim();
      name=name.replace(/(hasil pemeriksaan|koordinat lokasi|peta lokasi).*$/i,'').trim();
      if(!name || name.length>180) continue;
      fallback.push({name,batch,resultStatus:statusMatch?statusMatch[1].trim():'Status tidak terbaca',latitude:coords.latitude,longitude:coords.longitude,mapUrl:mapAnchor?.href||(coords.latitude!==null?`https://www.google.com/maps?q=${coords.latitude},${coords.longitude}`:''),sourceText:text});
    }
    if(fallback.length){fallback.forEach(record=>seen.set(norm(record.name)+'|'+String(record.batch||'')+'|'+norm(record.resultStatus),record));return fallback;}
    return [];
  }

  function maybeSelectAllFilters(container) {
    Array.from(container.querySelectorAll('select')).filter(visible).forEach(select => {
      const option = Array.from(select.options).find(o => /semua status|semua batch|semua/i.test(norm(o.textContent)));
      if (option && select.value !== option.value) {
        select.value = option.value;
        select.dispatchEvent(new Event('change', { bubbles: true }));
      }
    });
  }

  function findNextButton(container) {
    const controls = Array.from(container.querySelectorAll('button,a,[role="button"]')).filter(visible);
    return controls.find(el => {
      const text = norm(cleanText(el));
      const aria = norm(el.getAttribute('aria-label'));
      const title = norm(el.getAttribute('title'));
      return /^(next|berikutnya|selanjutnya|›|»|>)$/.test(text) || /next|berikutnya|selanjutnya/.test(aria + ' ' + title);
    }) || null;
  }
  function isDisabled(el) { return !!(el?.disabled || el?.getAttribute('aria-disabled') === 'true' || el?.classList.contains('disabled')); }

  function finalize() {
    if (finished || seen.size === 0) return false;
    if (expectedTotal > 0 && seen.size < expectedTotal) {
      finished = true;
      try { bridge && bridge.onError(`Data verval belum lengkap: portal menyatakan ${expectedTotal} koperasi, tetapi baru ${seen.size} yang berhasil dibaca. Tidak ada hasil parsial yang disimpan sebagai data lengkap.`); } catch (_) {}
      return false;
    }
    finished = true;
    const records = Array.from(seen.values());
    const title = (document.body?.innerText || '').match(/Kabupaten\/Kota:\s*([^\n•]+)/i);
    const province = (document.body?.innerText || '').match(/Provinsi\s+([^\n•]+)/i);
    const result = {
      kabupaten: cfg.kabupaten,
      provinsi: cfg.provinsi || (province ? province[1].trim() : ''),
      sourceKabupaten: title ? title[1].trim() : '',
      records,
      total: records.length,
      updatedAt: new Date().toISOString(),
      sourceUrl: location.href
    };
    say(`Berhasil membaca ${records.length} koperasi. Mengirim hasil ke AgriAttend…`);
    try { bridge && bridge.onData(JSON.stringify(result)); } catch (e) { say('Data terbaca tetapi gagal dikirim ke AgriAttend: ' + (e.message || e)); }
    return true;
  }

  function scanDetail() {
    const container = detailContainer();
    if (!container) return false;
    maybeSelectAllFilters(container);
    const found = extractRows(container);
    if (!found.length) return false;
    const signature = found.map(r => r.name + '|' + (r.batch || '') + '|' + r.resultStatus).join('||');
    if (signature !== lastPageSignature) {
      lastPageSignature = signature;
      lastPageChangeAt = Date.now();
      idleTicks = 0;
      pendingNextAt = 0;
    } else idleTicks++;
    const totalMatch = cleanText(container).match(/(\d+)\s+koperasi/i);
    if (totalMatch) expectedTotal = Math.max(expectedTotal, Number(totalMatch[1]) || 0);
    const next = findNextButton(container);
    if (pendingNextAt && Date.now() - pendingNextAt > 10000 && signature === lastClickedPageSignature) {
      finished = true;
      try { bridge && bridge.onError(`Portal belum memuat halaman berikutnya. Baru ${seen.size}${expectedTotal ? ' dari ' + expectedTotal : ''} koperasi yang terbaca. Periksa koneksi atau coba Refresh kembali.`); } catch (_) {}
      return true;
    }
    if (next && !isDisabled(next) && nextPageClicks < 50 && !pendingNextAt && signature !== lastClickedPageSignature && Date.now() - lastPageChangeAt > 1100) {
      lastClickedPageSignature = signature;
      pendingNextAt = Date.now();
      next.click();
      nextPageClicks++;
      say(`Membaca halaman data berikutnya (${nextPageClicks + 1})…`);
      return true;
    }
    // Scroll the modal/list to the bottom so lazy-loaded rows can enter the DOM.
    const scrollables = Array.from(container.querySelectorAll('*')).filter(el => visible(el) && el.scrollHeight > el.clientHeight + 20);
    if (scrollables.length) {
      const scroller = scrollables.sort((a, b) => (b.scrollHeight - b.clientHeight) - (a.scrollHeight - a.clientHeight))[0];
      if (scroller.scrollTop + scroller.clientHeight < scroller.scrollHeight - 10) {
        scroller.scrollTop = scroller.scrollHeight;
        lastPageChangeAt = Date.now();
        say('Memuat seluruh daftar koperasi…');
        return true;
      }
    }
    if (pendingNextAt) return true;
    if (expectedTotal > 0 && seen.size < expectedTotal) {
      if (Date.now() - lastPageChangeAt > 6000) {
        finished = true;
        try { bridge && bridge.onError(`Data verval belum lengkap: portal menyatakan ${expectedTotal} koperasi, tetapi baru ${seen.size} yang berhasil dibaca.`); } catch (_) {}
      }
      return true;
    }
    if (idleTicks >= 2 || Date.now() - lastPageChangeAt > 3500) finalize();
    return true;
  }

  function attempt() {
    if (finished) return;
    if (Date.now() - startedAt > 120000) {
      if (seen.size) finalize();
      else {
        try { bridge && bridge.onError('Waktu pemeriksaan habis. Portal tidak menampilkan daftar koperasi untuk kabupaten penugasan. Periksa akses atau pemetaan lokasi AgriConnect.'); } catch (_) {}
        finished = true;
      }
      return;
    }
    if (fillPassword()) return;
    if (scanDetail()) return;

    const district = String(cfg.kabupaten || '').trim();
    const province = String(cfg.provinsi || '').trim();
    if (!clickedDistrict && clickRegion(district, false)) return;
    if (!clickedProvince && province && clickRegion(province, true)) return;
    if (clickedProvince && !clickedDistrict && clickRegion(district, false)) return;

    if (!district) {
      try { bridge && bridge.onError('Kabupaten/kota penugasan tidak ditemukan pada data profil AgriConnect.'); } catch (_) {}
      finished = true;
      return;
    }
    if (Date.now() - startedAt > 15000 && !clickedDistrict && !detailContainer()) {
      say(`Mencari kabupaten/kota ${district} pada daftar Simkopdes…`);
    } else if (Date.now() - startedAt < 15000) {
      say('Membuka halaman pemantauan verval Simkopdes…');
    }
  }

  say(`Menyiapkan pemeriksaan verval untuk ${cfg.kabupaten || 'lokasi penugasan'}…`);
  try { window.__agriVervalAutomationStop && window.__agriVervalAutomationStop(); } catch (_) {}
  const observer = new MutationObserver(() => attempt());
  observer.observe(document.documentElement || document, { childList: true, subtree: true, attributes: true });
  const timer = setInterval(() => {
    attempt();
    if (finished) { clearInterval(timer); observer.disconnect(); }
  }, 1200);
  window.__agriVervalAutomationStop = function () { clearInterval(timer); observer.disconnect(); };
  setTimeout(attempt, 500);
})(window.__AGRI_VERVAL_CONFIG__ || {});
