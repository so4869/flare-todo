// ── 첨부파일 공통 모듈 (index.html, detail.html) ─────────────────────────
// 파일은 S3에 저장되고 CloudFront(todo-att.flare.im)로 제공된다.
// CDN 접근은 서명 쿠키로 인증하므로, 페이지 로드 시 keepCdnSession()으로 쿠키를 발급받아야
// 본문 이미지와 첨부 링크가 열린다.
const Attachments = (() => {
  const CDN_SESSION_REFRESH_MS = 60 * 60 * 1000;   // 쿠키 유효기간(12h)보다 충분히 짧게 갱신

  function esc(str) {
    return String(str ?? '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }

  function formatSize(n) {
    if (n < 1024) return `${n} B`;
    if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
    if (n < 1024 * 1024 * 1024) return `${(n / 1024 / 1024).toFixed(1)} MB`;
    return `${(n / 1024 / 1024 / 1024).toFixed(1)} GB`;
  }

  function iconFor(name, contentType = '') {
    const ext = (name.split('.').pop() || '').toLowerCase();
    if (contentType.startsWith('image/')) return 'bi-file-earmark-image';
    if (ext === 'pdf') return 'bi-file-earmark-pdf';
    if (['zip', '7z', 'rar', 'tar', 'gz'].includes(ext)) return 'bi-file-earmark-zip';
    if (['xls', 'xlsx', 'csv'].includes(ext)) return 'bi-file-earmark-spreadsheet';
    if (['doc', 'docx', 'hwp', 'hwpx', 'txt', 'md'].includes(ext)) return 'bi-file-earmark-text';
    if (['ppt', 'pptx'].includes(ext)) return 'bi-file-earmark-slides';
    return 'bi-file-earmark';
  }

  // 미리보기 방식: 'image' = 라이트박스, 'tab' = 새 탭(브라우저 내장 뷰어·확장 프로그램), null = 없음
  const IMAGE_EXTS = ['png', 'jpg', 'jpeg', 'gif', 'webp', 'bmp', 'svg', 'avif'];
  const TAB_EXTS = ['pdf', 'md', 'markdown', 'txt'];
  function previewKind(a) {
    const ext = ((a.name || '').split('.').pop() || '').toLowerCase();
    const type = a.contentType || '';
    if (a.kind === 'IMAGE' || type.startsWith('image/') || IMAGE_EXTS.includes(ext)) return 'image';
    if (type === 'application/pdf' || TAB_EXTS.includes(ext)) return 'tab';
    return null;
  }

  // ── 이미지 라이트박스 ────────────────────────────────────
  // 이미지만 띄우고, 이미지 밖(배경)을 누르거나 Esc 로 닫는다. 하단 바: 왼쪽 파일명, 가운데 이전/카운터/다음.
  // 이미지를 누르면 확대(원본 크기, 작으면 2배)·다시 누르면 화면 맞춤. 좌우 버튼·방향키로 순환.
  let lightbox = null;
  let lbItems = [];   // [{ url, name }]
  let lbIndex = 0;

  function ensureLightbox() {
    if (lightbox) return lightbox;
    document.body.insertAdjacentHTML('beforeend', `
      <div class="attach-lightbox" hidden>
        <div class="attach-lb-stage"><img class="attach-lb-img" alt=""></div>
        <div class="attach-lb-bar">
          <div class="attach-lb-name"></div>
          <div class="attach-lb-ctl">
            <button type="button" class="attach-lb-nav attach-lb-prev" aria-label="이전" tabindex="-1"><i class="bi bi-chevron-left"></i></button>
            <span class="attach-lb-count"></span>
            <button type="button" class="attach-lb-nav attach-lb-next" aria-label="다음" tabindex="-1"><i class="bi bi-chevron-right"></i></button>
          </div>
          <div class="attach-lb-dim"></div>
        </div>
      </div>`);
    lightbox = document.body.lastElementChild;
    // 상세 모달(Bootstrap) 안에서 열려도 포커스가 모달 밖으로 나가지 않도록 버튼 클릭 시 포커스 이동을 막는다.
    // (preventDefault 는 글자 선택도 막으므로 파일명·해상도 영역은 제외)
    lightbox.addEventListener('mousedown', e => {
      if (!e.target.closest('.attach-lb-name, .attach-lb-dim')) e.preventDefault();
    });
    lightbox.querySelector('.attach-lb-img').addEventListener('load', e => {
      lightbox.querySelector('.attach-lb-dim').textContent = `${e.target.naturalWidth} × ${e.target.naturalHeight}`;
    });
    lightbox.addEventListener('click', e => {
      if (e.target.closest('.attach-lb-prev')) showLightboxAt(lbIndex - 1);
      else if (e.target.closest('.attach-lb-next')) showLightboxAt(lbIndex + 1);
      else if (e.target.closest('.attach-lb-img')) toggleLightboxZoom();
      else if (!e.target.closest('.attach-lb-bar')) closeLightbox();
    });
    // capture 단계에서 먼저 받아 상세 모달의 Esc 닫기 등으로 전달되지 않게 한다
    document.addEventListener('keydown', e => {
      if (lightbox.hidden) return;
      if (e.key === 'Escape') closeLightbox();
      else if (e.key === 'ArrowLeft') showLightboxAt(lbIndex - 1);
      else if (e.key === 'ArrowRight') showLightboxAt(lbIndex + 1);
      else return;
      e.preventDefault();
      e.stopPropagation();
    }, true);
    return lightbox;
  }

  function lbImg() { return lightbox.querySelector('.attach-lb-img'); }

  function showLightboxAt(i) {
    const n = lbItems.length;
    lbIndex = ((i % n) + n) % n;
    const item = lbItems[lbIndex];
    resetLightboxZoom();
    lightbox.querySelector('.attach-lb-dim').textContent = '';
    lbImg().src = item.url;
    lightbox.querySelector('.attach-lb-name').textContent = item.name || '';
    lightbox.querySelector('.attach-lb-count').textContent = `${n}개 중 ${lbIndex + 1}개`;
  }

  function resetLightboxZoom() {
    const img = lbImg();
    img.classList.remove('attach-lb-zoomed');
    img.style.width = '';
    lightbox.querySelector('.attach-lb-stage').scrollTo(0, 0);
  }

  function toggleLightboxZoom() {
    const img = lbImg();
    if (img.classList.contains('attach-lb-zoomed')) { resetLightboxZoom(); return; }
    if (!img.naturalWidth) return;
    const fitted = img.getBoundingClientRect().width;
    // 화면보다 큰 이미지는 원본 크기로, 작은 이미지는 2배로
    const target = img.naturalWidth > fitted + 1 ? img.naturalWidth : fitted * 2;
    img.classList.add('attach-lb-zoomed');
    img.style.width = `${Math.round(target)}px`;
  }

  // items: URL 문자열 또는 { url, name } 배열
  function openImageViewer(items, index = 0) {
    const list = (items || [])
      .map(it => typeof it === 'string' ? { url: it, name: '' } : it)
      .filter(it => it && it.url);
    if (!list.length) return;
    ensureLightbox();
    lbItems = list;
    lightbox.classList.toggle('attach-lb-single', list.length < 2);
    showLightboxAt(index);
    lightbox.hidden = false;
  }

  function closeLightbox() {
    if (!lightbox) return;
    lightbox.hidden = true;
    resetLightboxZoom();
    lbImg().removeAttribute('src');
  }

  // 본문 이미지 이름: 할 일의 첨부 목록(getAttachments)에서 같은 URL 을 찾고, 없으면 alt/title, 그래도 없으면 URL 마지막 조각
  function bodyImageName(img, getAttachments) {
    const found = (typeof getAttachments === 'function' ? getAttachments() || [] : []).find(a => a.url === img.src);
    if (found) return found.name;
    if (img.alt || img.title) return img.alt || img.title;
    try { return decodeURIComponent(new URL(img.src).pathname.split('/').pop() || ''); } catch { return ''; }
  }

  // 본문(root: 요소 또는 ShadowRoot) 안의 이미지를 누르면 본문 이미지 전체를 라이트박스로 넘긴다
  function bindBodyImages(root, getAttachments) {
    if (!root || root.__attachBodyBound) return;
    root.__attachBodyBound = true;
    root.addEventListener('click', e => {
      const img = e.target.closest ? e.target.closest('img') : null;
      if (!img || !img.src) return;
      if (img.closest('a')) return;   // 링크로 감싼 이미지는 링크 동작 유지
      e.preventDefault();
      const imgs = [...root.querySelectorAll('img')].filter(el => el.src);
      openImageViewer(imgs.map(el => ({ url: el.src, name: bodyImageName(el, getAttachments) })), Math.max(0, imgs.indexOf(img)));
    });
  }

  // PDF·마크다운: 서버에서 inline 서명 URL 을 받아 새 탭에서 연다 (팝업 차단을 피하려고 탭은 먼저 연다)
  async function openInNewTab(attachmentId) {
    const win = window.open('about:blank', '_blank');
    try {
      const res = await authFetch(`/api/attachments/${attachmentId}/preview-url`);
      const data = res ? await res.json() : null;
      if (!data || !data.success) throw new Error(data?.message || '미리보기 URL을 받지 못했습니다.');
      if (win) { win.opener = null; win.location.replace(data.data.url); }
      else window.open(data.data.url, '_blank', 'noopener');
    } catch (e) {
      if (win) win.close();
      alert(e.message || '미리보기를 열 수 없습니다.');
    }
  }

  // ── 서버 설정 (최대 첨부 크기는 DB에서 관리) ─────────────────
  let configPromise = null;
  function getConfig() {
    if (!configPromise) {
      configPromise = authFetch('/api/attachments/config')
        .then(r => r.json())
        .then(d => d.data)
        .catch(e => { configPromise = null; throw e; });
    }
    return configPromise;
  }

  // ── CDN 서명 쿠키 ────────────────────────────────────────
  async function refreshCdnSession() {
    try {
      await authFetch('/api/attachments/cdn-session', { method: 'POST' });
    } catch (e) {
      console.warn('CDN 세션 갱신 실패', e);
    }
  }

  let cdnTimer = null;
  function keepCdnSession() {
    refreshCdnSession();
    clearInterval(cdnTimer);
    cdnTimer = setInterval(refreshCdnSession, CDN_SESSION_REFRESH_MS);
  }

  // ── 서버 경유 업로드 (본문 이미지, 직접 업로드를 못 쓸 때). 진행률 표시를 위해 XHR 사용 ──
  function upload(url, file, filename, onProgress, ctl) {
    return new Promise((resolve, reject) => {
      const form = new FormData();
      form.append('file', file, filename || file.name);
      const xhr = new XMLHttpRequest();
      if (ctl) ctl.onCancel(() => xhr.abort());
      xhr.open('POST', url);
      const token = localStorage.getItem('jwt_token');
      if (token) xhr.setRequestHeader('Authorization', `Bearer ${token}`);
      xhr.upload.onprogress = e => { if (e.lengthComputable && onProgress) onProgress(e.loaded / e.total * 100); };
      xhr.onload = () => {
        if (xhr.status === 401) {
          localStorage.removeItem('jwt_token');
          location.href = '/login.html';
          return;
        }
        let data = null;
        try { data = JSON.parse(xhr.responseText); } catch (_) { /* 비 JSON 응답 */ }
        if (data && data.success) resolve(data.data);
        else reject(new Error((data && data.message) || `업로드 실패 (${xhr.status})`));
      };
      xhr.onerror = () => reject(new Error('네트워크 오류로 업로드하지 못했습니다.'));
      xhr.onabort = () => reject(new Error('업로드를 취소했습니다.'));
      xhr.send(form);
    });
  }

  // ── S3 직접 업로드 (멀티파트) ─────────────────────────────
  // 파일 내용은 서버·로드밸런서를 거치지 않고 파트별 서명 URL로 S3에 바로 올라간다.
  // 서버는 시작(/uploads) → 파트 서명(/parts) → 완료(/complete, S3에서 파트를 직접 확인) 만 처리한다.
  // 버킷 CORS 에서 앱 도메인의 PUT 을 허용해야 한다 (docs/s3-direct-upload.md).
  const PART_CONCURRENCY = 4;
  const PART_ATTEMPTS = 4;
  const SIGN_BATCH = 20;

  // 취소 핸들. 업로드 중인 항목을 목록에서 지우면 cancel() 로 진행 중인 요청을 끊는다.
  function uploadControl() {
    const handlers = new Set();
    return {
      cancelled: false,
      onCancel(fn) { if (this.cancelled) fn(); else handlers.add(fn); return () => handlers.delete(fn); },
      cancel() { if (this.cancelled) return; this.cancelled = true; handlers.forEach(fn => { try { fn(); } catch (_) {} }); }
    };
  }

  async function apiJson(url, method, body) {
    const res = await authFetch(url, { method, body: body === undefined ? undefined : JSON.stringify(body) });
    if (!res) throw new Error('로그인이 만료되었습니다.');
    let data = null;
    try { data = await res.json(); } catch (_) { /* 비 JSON 응답 */ }
    if (!res.ok || !data || !data.success) {
      const err = new Error((data && data.message) || `요청 실패 (${res.status})`);
      err.status = res.status;
      throw err;
    }
    return data.data;
  }

  // 파트 하나를 서명 URL로 PUT. 실패 시 status(네트워크·CORS 오류면 0)를 담아 reject
  function putPart(url, blob, onProgress, ctl) {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      const off = ctl.onCancel(() => xhr.abort());
      const fail = (status, msg) => { off(); const e = new Error(msg); e.status = status; reject(e); };
      xhr.open('PUT', url);
      xhr.upload.onprogress = e => { if (e.lengthComputable) onProgress(e.loaded); };
      xhr.onload = () => {
        if (xhr.status >= 200 && xhr.status < 300) { off(); onProgress(blob.size); resolve(); }
        else fail(xhr.status, `파일 조각 업로드 실패 (${xhr.status})`);
      };
      xhr.onerror = () => fail(0, '네트워크 오류로 업로드하지 못했습니다.');
      xhr.onabort = () => fail(0, '업로드를 취소했습니다.');
      xhr.send(blob);
    });
  }

  const sleep = ms => new Promise(r => setTimeout(r, ms));

  async function uploadDirect(file, onProgress, ctl) {
    const { uploadId, partSize, partCount } = await apiJson('/api/attachments/uploads', 'POST',
      { name: file.name, size: file.size, contentType: file.type || null });
    const base = `/api/attachments/uploads/${uploadId}`;
    let anyPartDone = false;
    try {
      const loaded = new Array(partCount + 1).fill(0);
      const report = () => onProgress && onProgress(Math.min(99, loaded.reduce((a, b) => a + b, 0) / file.size * 100));

      // 파트 번호 → 서명 URL Promise. 여러 파트를 한 번에 서명받아 왕복을 줄인다.
      const urls = new Map();
      const sign = nums => {
        const p = apiJson(`${base}/parts`, 'POST', { partNumbers: nums })
          .then(d => new Map(d.parts.map(x => [x.partNumber, x.url])));
        nums.forEach(n => urls.set(n, p.then(m => m.get(n))));
      };
      const urlFor = (n, fresh) => {
        if (fresh || !urls.has(n)) {
          const nums = [n];
          if (!fresh) for (let k = n + 1; k <= partCount && nums.length < SIGN_BATCH; k++) if (!urls.has(k)) nums.push(k);
          sign(nums);
        }
        return urls.get(n);
      };

      async function uploadPart(n) {
        const blob = file.slice((n - 1) * partSize, Math.min(n * partSize, file.size));
        for (let attempt = 1; ; attempt++) {
          try {
            const url = await urlFor(n, attempt > 1);   // 재시도 때는 새로 서명 (만료·자격증명 교체 대비)
            await putPart(url, blob, l => { loaded[n] = l; report(); }, ctl);
            anyPartDone = true;
            return;
          } catch (e) {
            loaded[n] = 0;
            report();
            // 첫 파트부터 네트워크 오류면 CORS 미설정 등으로 보고 서버 경유 업로드로 넘긴다.
            if (ctl.cancelled || attempt >= PART_ATTEMPTS || (!anyPartDone && e.status === 0 && attempt >= 2)) throw e;
            await sleep(1000 * 2 ** (attempt - 1));
          }
        }
      }

      let next = 1;
      const worker = async () => { while (next <= partCount && !ctl.cancelled) await uploadPart(next++); };
      await Promise.all(Array.from({ length: Math.min(PART_CONCURRENCY, partCount) }, worker));
      if (ctl.cancelled) throw new Error('업로드를 취소했습니다.');
      const a = await apiJson(`${base}/complete`, 'POST');
      if (onProgress) onProgress(100);
      return a;
    } catch (e) {
      ctl.cancel();
      authFetch(base, { method: 'DELETE' }).catch(() => {});
      if (!anyPartDone && e.status === 0) e.fallback = true;
      throw e;
    }
  }

  // 할 일 첨부 파일 업로드: S3 직접 업로드, 쓸 수 없으면(설정 off, CORS 미설정 등) 서버 경유
  async function uploadFile(file, onProgress, ctl, config) {
    if (config && config.directUpload === false) return upload('/api/attachments', file, file.name, onProgress, ctl);
    try {
      return await uploadDirect(file, onProgress, uploadControlChild(ctl));
    } catch (e) {
      if (!e.fallback || ctl.cancelled) throw e;
      console.warn('S3 직접 업로드를 쓸 수 없어 서버 경유로 올립니다.', e);
      if (onProgress) onProgress(0);
      return upload('/api/attachments', file, file.name, onProgress, ctl);
    }
  }

  // 직접 업로드가 실패하면 자기 요청만 끊도록, 바깥 취소를 이어받는 하위 핸들을 쓴다.
  function uploadControlChild(parent) {
    const child = uploadControl();
    parent.onCancel(() => child.cancel());
    return child;
  }

  // ── TinyMCE 이미지 설정 ──────────────────────────────────
  // - 이미지 대화상자에서 올린 파일: 즉시 업로드 후 CDN URL을 src로 사용
  // - 붙여넣기/끌어놓기한 이미지: base64로 본문에 두고, 저장 시 서버가 S3로 올려 URL로 바꾼다
  //   (파일 업로드는 막고 본문 붙여넣기는 허용하는 사내망 대응)
  const editorImageOptions = {
    paste_data_images: true,
    automatic_uploads: false,
    image_description: false,
    images_upload_handler(blobInfo, success, failure, progress) {
      upload('/api/attachments/images', blobInfo.blob(), blobInfo.filename(), progress)
        .then(a => success(a.url))
        .catch(e => failure(e.message, { remove: true }));
    }
  };

  // 브라우저 창에 파일을 잘못 떨어뜨렸을 때 페이지가 파일로 이동하지 않도록 막는다.
  const hasFiles = e => e.dataTransfer && Array.from(e.dataTransfer.types || []).includes('Files');
  window.addEventListener('dragover', e => { if (hasFiles(e)) e.preventDefault(); });
  window.addEventListener('drop', e => { if (hasFiles(e)) e.preventDefault(); });

  // ── 외부 공유 링크 ───────────────────────────────────────
  // 유효기간은 일 단위 실수 (1.5 → 1일 12시간)
  function describeDays(days) {
    const totalMin = Math.round(days * 1440);
    const d = Math.floor(totalMin / 1440), h = Math.floor(totalMin % 1440 / 60), m = totalMin % 60;
    return [d && `${d}일`, h && `${h}시간`, m && `${m}분`].filter(Boolean).join(' ') || '0분';
  }

  function formatDateTime(date) {
    const opts = { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' };
    if (typeof userTimezone !== 'undefined') opts.timeZone = userTimezone;
    return date.toLocaleString('ko-KR', opts);
  }

  // 방식: link = 앱 링크(/s/{token}, 취소 가능) / direct = S3 서명 URL 그대로(최대 7일, 취소 불가)
  function sharePanelHtml() {
    return `
      <div class="attach-share" data-mode="link">
        <div class="attach-share-row">
          <select class="form-select form-select-sm attach-share-mode" aria-label="공유 방식" title="앱 링크는 나중에 취소할 수 있고, S3 직접 링크는 앱을 거치지 않는 대신 최대 7일·취소 불가">
            <option value="link">앱 링크</option>
            <option value="direct">S3 직접 링크</option>
          </select>
          <span class="small text-muted">유효기간</span>
          <div class="input-group input-group-sm attach-share-days">
            <input type="number" class="form-control" min="0" step="any" value="1" aria-label="유효기간(일)">
            <span class="input-group-text">일</span>
          </div>
          <span class="small text-muted attach-share-preview"></span>
          <button type="button" class="btn btn-sm btn-primary attach-share-create">링크 만들기</button>
        </div>
        <div class="attach-share-result d-none">
          <div class="input-group input-group-sm">
            <input type="text" class="form-control attach-share-url" readonly>
            <button type="button" class="btn btn-outline-secondary attach-share-copy"><i class="bi bi-clipboard me-1"></i>복사</button>
            <button type="button" class="btn btn-outline-danger attach-share-revoke" title="이 링크를 취소합니다">취소</button>
          </div>
          <div class="small text-muted mt-1 attach-share-expires"></div>
        </div>
        <div class="small text-danger attach-share-error"></div>
      </div>`;
  }

  function isDirectMode(panel) {
    return panel.querySelector('.attach-share-mode').value === 'direct';
  }

  // 방식을 바꾸면 유효기간 기본값·상한을 맞춘다. S3 직접 링크 기본값은 서버 설정(DB)에서 온다.
  async function applyShareMode(panel) {
    const direct = isDirectMode(panel);
    const input = panel.querySelector('.attach-share-days input');
    panel.dataset.mode = direct ? 'direct' : 'link';
    let config = null;
    try { config = await getConfig(); } catch (_) { /* 상한 검사는 서버가 다시 한다 */ }
    if (direct) {
      const max = config?.sharePresignedMaxDays ?? 7;
      input.max = max;
      input.value = Math.min(config?.sharePresignedDays ?? 7, max);
    } else {
      input.max = config?.shareMaxDays ?? 365;
      input.value = 1;
    }
    updateSharePreview(panel);
  }

  function updateSharePreview(panel) {
    const input = panel.querySelector('.attach-share-days input');
    const days = parseFloat(input.value);
    const max = parseFloat(input.max);
    const preview = panel.querySelector('.attach-share-preview');
    if (!(days > 0)) preview.textContent = '0보다 큰 값을 입력하세요';
    else if (max > 0 && days > max) preview.textContent = `${isDirectMode(panel) ? 'S3 직접 링크는 ' : ''}최대 ${max}일까지`;
    else preview.textContent = `${describeDays(days)} 후 만료 (${formatDateTime(new Date(Date.now() + days * 86400000))})`;
  }

  async function createShareLink(panel, attachmentId) {
    const errorEl = panel.querySelector('.attach-share-error');
    const btn = panel.querySelector('.attach-share-create');
    errorEl.classList.replace('text-muted', 'text-danger');
    errorEl.textContent = '';
    const days = parseFloat(panel.querySelector('.attach-share-days input').value);
    const direct = isDirectMode(panel);
    if (!(days > 0)) { errorEl.textContent = '유효기간을 0보다 크게 입력하세요.'; return; }
    btn.disabled = true;
    try {
      const res = await authFetch(`/api/attachments/${attachmentId}/share`, {
        method: 'POST',
        body: JSON.stringify({ days, direct })
      });
      const data = await res.json();
      if (!data.success) { errorEl.textContent = data.message; return; }
      if (data.data.direct) delete panel.dataset.linkId; else panel.dataset.linkId = data.data.id;
      panel.querySelector('.attach-share-revoke').classList.toggle('d-none', !!data.data.direct);
      panel.querySelector('.attach-share-url').value = data.data.url;
      panel.querySelector('.attach-share-expires').textContent = data.data.direct
        ? `${formatDateTime(new Date(data.data.expiresAt))}까지 누구나 이 S3 링크로 받을 수 있습니다. 이 링크는 취소할 수 없고 공유 링크 목록에도 나오지 않습니다.`
        : `${formatDateTime(new Date(data.data.expiresAt))}까지 누구나 이 링크로 받을 수 있습니다.`;
      panel.querySelector('.attach-share-result').classList.remove('d-none');
    } catch (e) {
      errorEl.textContent = '링크를 만들지 못했습니다.';
    } finally {
      btn.disabled = false;
    }
  }

  async function copyText(input, btn) {
    try {
      await navigator.clipboard.writeText(input.value);
    } catch (_) {
      // 비보안 컨텍스트(http) 등 Clipboard API를 못 쓰는 환경
      input.select();
      document.execCommand('copy');
    }
    btn.innerHTML = '<i class="bi bi-check-lg me-1"></i>복사됨';
    setTimeout(() => { btn.innerHTML = '<i class="bi bi-clipboard me-1"></i>복사'; }, 1500);
  }

  async function revokeShareLink(id) {
    const res = await authFetch(`/api/share-links/${id}`, { method: 'DELETE' });
    const data = await res.json();
    if (!data.success) throw new Error(data.message);
  }

  async function revokeFromPanel(panel) {
    if (!confirm('이 공유 링크를 취소할까요? 취소하면 링크로 더 이상 받을 수 없습니다.')) return;
    const errorEl = panel.querySelector('.attach-share-error');
    try {
      await revokeShareLink(panel.dataset.linkId);
      delete panel.dataset.linkId;
      panel.querySelector('.attach-share-result').classList.add('d-none');
      errorEl.classList.replace('text-danger', 'text-muted');
      errorEl.textContent = '링크를 취소했습니다.';
    } catch (e) {
      errorEl.textContent = e.message || '링크를 취소하지 못했습니다.';
    }
  }

  // 목록(ul)에 공유 버튼 동작을 연결한다. 항목을 다시 그려도 유지되도록 이벤트 위임 사용.
  function bindShare(ul) {
    if (ul.dataset.shareBound) return;
    ul.dataset.shareBound = '1';
    ul.addEventListener('click', e => {
      const li = e.target.closest('.attach-item');
      if (!li) return;
      if (e.target.closest('.attach-preview-btn')) {
        e.preventDefault();
        if (li.dataset.preview === 'image') {
          const imgs = [...ul.querySelectorAll('.attach-item[data-preview="image"]')];
          openImageViewer(imgs.map(el => ({ url: el.dataset.url, name: el.querySelector('.attach-name')?.textContent || '' })), imgs.indexOf(li));
        } else {
          openInNewTab(li.dataset.id);
        }
      } else if (e.target.closest('.attach-share-btn')) {
        const open = li.querySelector('.attach-share');
        if (open) { open.remove(); return; }
        li.insertAdjacentHTML('beforeend', sharePanelHtml());
        const panel = li.querySelector('.attach-share');
        updateSharePreview(panel);
        applyShareMode(panel);
        panel.querySelector('.attach-share-days input').focus();
      } else if (e.target.closest('.attach-share-create')) {
        createShareLink(li.querySelector('.attach-share'), li.dataset.id);
      } else if (e.target.closest('.attach-share-copy')) {
        const panel = li.querySelector('.attach-share');
        copyText(panel.querySelector('.attach-share-url'), e.target.closest('.attach-share-copy'));
      } else if (e.target.closest('.attach-share-revoke')) {
        revokeFromPanel(li.querySelector('.attach-share'));
      }
    });
    ul.addEventListener('input', e => {
      const panel = e.target.closest('.attach-share');
      if (panel && e.target.matches('.attach-share-days input')) updateSharePreview(panel);
    });
    ul.addEventListener('change', e => {
      const panel = e.target.closest('.attach-share');
      if (panel && e.target.matches('.attach-share-mode')) applyShareMode(panel);
    });
    ul.addEventListener('keydown', e => {
      const panel = e.target.closest('.attach-share');
      if (panel && e.key === 'Enter' && e.target.matches('.attach-share-days input')) {
        e.preventDefault();
        createShareLink(panel, e.target.closest('.attach-item').dataset.id);
      }
    });
  }

  // ── 공유 링크 관리 (상단 메뉴) ────────────────────────────
  // onOpenTodo(todoId): 목록에서 할 일 제목을 눌렀을 때 호출 (페이지마다 여는 방식이 다름)
  let managerModal = null;
  let managerOnOpenTodo = null;

  function ensureManagerModal() {
    if (managerModal) return managerModal;
    document.body.insertAdjacentHTML('beforeend', `
      <div class="modal fade" id="shareManagerModal" tabindex="-1">
        <div class="modal-dialog modal-lg modal-dialog-scrollable">
          <div class="modal-content">
            <div class="modal-header">
              <h5 class="modal-title"><i class="bi bi-share me-2"></i>공유 중인 링크</h5>
              <button type="button" class="btn-close" data-bs-dismiss="modal"></button>
            </div>
            <div class="modal-body"><div class="share-mgr-list"></div></div>
            <div class="modal-footer">
              <small class="text-muted me-auto">공유를 취소하면 그 링크로는 더 이상 받을 수 없습니다.</small>
              <button type="button" class="btn btn-secondary" data-bs-dismiss="modal">닫기</button>
            </div>
          </div>
        </div>
      </div>`);
    const el = document.getElementById('shareManagerModal');
    const list = el.querySelector('.share-mgr-list');
    list.addEventListener('click', async e => {
      const item = e.target.closest('.share-mgr-item');
      if (!item) return;
      if (e.target.closest('.share-mgr-copy')) {
        copyText(item.querySelector('input'), e.target.closest('.share-mgr-copy'));
      } else if (e.target.closest('.share-mgr-revoke')) {
        if (!confirm('이 공유 링크를 취소할까요? 취소하면 링크로 더 이상 받을 수 없습니다.')) return;
        try {
          await revokeShareLink(item.dataset.id);
          item.remove();
          if (!list.querySelector('.share-mgr-item')) list.innerHTML = managerEmptyHtml();
        } catch (err) {
          alert(err.message || '링크를 취소하지 못했습니다.');
        }
      } else if (e.target.closest('.share-mgr-todo')) {
        e.preventDefault();
        const todoId = Number(e.target.closest('.share-mgr-todo').dataset.todoId);
        bootstrap.Modal.getInstance(el).hide();
        if (managerOnOpenTodo) managerOnOpenTodo(todoId);
      }
    });
    managerModal = el;
    return el;
  }

  const managerEmptyHtml = () =>
    '<div class="text-center text-muted py-5"><i class="bi bi-link-45deg fs-2 d-block mb-2"></i>공유 중인 링크가 없습니다.</div>';

  function managerItemHtml(l) {
    const remainingDays = (new Date(l.expiresAt) - Date.now()) / 86400000;
    const source = l.todoId
      ? `<a href="#" class="share-mgr-todo" data-todo-id="${l.todoId}">${esc(l.todoTitle)}</a>`
      : (l.attachmentKind === 'IMAGE' ? '본문 이미지' : '할 일에서 빠진 첨부');
    return `
      <div class="share-mgr-item" data-id="${l.id}">
        <div class="share-mgr-head">
          <i class="bi ${iconFor(l.attachmentName, l.attachmentKind === 'IMAGE' ? 'image/' : '')} attach-icon"></i>
          <div class="share-mgr-info">
            <div class="share-mgr-name" title="${esc(l.attachmentName)}">${esc(l.attachmentName)}</div>
            <div class="small text-muted">${source} · ${formatDateTime(new Date(l.expiresAt))} 만료 (${describeDays(Math.max(remainingDays, 0))} 남음)</div>
          </div>
          <button type="button" class="btn btn-sm btn-outline-danger share-mgr-revoke">공유 취소</button>
        </div>
        <div class="input-group input-group-sm mt-2">
          <input type="text" class="form-control" value="${esc(l.url)}" readonly>
          <button type="button" class="btn btn-outline-secondary share-mgr-copy"><i class="bi bi-clipboard me-1"></i>복사</button>
        </div>
      </div>`;
  }

  async function openShareManager({ onOpenTodo } = {}) {
    managerOnOpenTodo = onOpenTodo || null;
    const el = ensureManagerModal();
    const list = el.querySelector('.share-mgr-list');
    list.innerHTML = '<div class="text-center text-muted py-5">불러오는 중...</div>';
    bootstrap.Modal.getOrCreateInstance(el).show();
    try {
      const res = await authFetch('/api/share-links');
      const data = await res.json();
      list.innerHTML = data.data.length ? data.data.map(managerItemHtml).join('') : managerEmptyHtml();
    } catch (e) {
      list.innerHTML = '<div class="text-center text-danger py-5">목록을 불러오지 못했습니다.</div>';
    }
  }

  // ── 전체받기 (zip) ──────────────────────────────────────
  // 서버가 최신 zip 을 CDN에 준비해 두고 주소를 돌려준다. CDN 은 서명 쿠키로 인증되므로 링크만 열면 받아진다.
  async function downloadAll(todoId, btn) {
    const label = btn?.innerHTML;
    if (btn) { btn.disabled = true; btn.innerHTML = '<span class="spinner-border spinner-border-sm me-1"></span>압축 중'; }
    try {
      const res = await authFetch(`/api/todos/${todoId}/attachments/zip`);
      if (!res) return;
      const data = await res.json();
      if (!data.success) { alert(data.message || '압축 파일을 만들지 못했습니다.'); return; }
      const a = document.createElement('a');
      a.href = data.data.url;
      a.download = data.data.fileName;
      document.body.appendChild(a);
      a.click();
      a.remove();
    } catch (e) {
      alert('압축 파일을 만들지 못했습니다.');
    } finally {
      if (btn) { btn.disabled = false; btn.innerHTML = label; }
    }
  }

  // ── 목록 항목 ───────────────────────────────────────────
  // 본문 이미지(kind=IMAGE)는 본문에서 지워야 사라지므로 삭제 버튼 대신 '본문' 표시
  function itemHtml(a, removable) {
    const isImage = a.kind === 'IMAGE';
    const ready = a.id && !a.uploading && !a.error;
    const preview = ready && a.url ? previewKind(a) : null;
    return `
      <li class="attach-item" data-key="${esc(a.key ?? '')}" data-id="${esc(a.id ?? '')}"${preview ? ` data-preview="${preview}" data-url="${esc(a.url)}"` : ''}>
        <i class="bi ${iconFor(a.name, a.contentType)} attach-icon"></i>
        ${a.url
          ? `<a class="attach-name" href="${esc(a.url)}"${isImage ? ' target="_blank" rel="noopener"' : ''} title="${esc(a.name)}">${esc(a.name)}</a>`
          : `<span class="attach-name">${esc(a.name)}</span>`}
        ${isImage ? '<span class="attach-badge" title="본문에 들어 있는 이미지입니다. 본문에서 지우면 목록에서도 사라집니다.">본문</span>' : ''}
        <span class="attach-size">${a.error ? `<span class="text-danger">${esc(a.error)}</span>` : formatSize(a.size)}</span>
        ${preview ? `<button type="button" class="btn btn-sm btn-link attach-preview-btn" title="${preview === 'image' ? '미리보기' : '새 탭에서 열기'}"><i class="bi ${preview === 'image' ? 'bi-eye' : 'bi-box-arrow-up-right'}"></i></button>` : ''}
        ${ready ? '<button type="button" class="btn btn-sm btn-link attach-share-btn" title="외부 공유 링크 만들기"><i class="bi bi-share"></i></button>' : ''}
        ${removable && !isImage ? '<button type="button" class="btn-close attach-remove" aria-label="첨부 삭제" title="삭제"></button>' : ''}
        ${a.uploading ? `<div class="attach-progress"><div style="width:${a.progress || 0}%"></div></div>` : ''}
      </li>`;
  }

  function renderList(ul, attachments) {
    bindShare(ul);
    ul.innerHTML = (attachments || []).map(a => itemHtml(a, false)).join('');
  }

  // ── 편집용 첨부 영역 ─────────────────────────────────────
  // dropTarget: 파일을 떨어뜨리면 첨부할 영역 (기본: 첨부 영역 자신). 에디터 iframe 위에 떨어뜨린 이미지는
  //             TinyMCE가 본문 이미지로 처리한다.
  function createBox(container, { dropTarget } = {}) {
    container.classList.add('attach-box');
    container.innerHTML = `
      <div class="attach-drop" role="button" tabindex="0">
        <i class="bi bi-cloud-arrow-up"></i>
        <span>파일을 끌어다 놓거나 <span class="attach-pick">클릭해서 선택</span> <small class="text-muted attach-limit"></small></span>
        <input type="file" multiple hidden>
      </div>
      <ul class="attach-list"></ul>`;
    const drop = container.querySelector('.attach-drop');
    const input = container.querySelector('input[type=file]');
    const list = container.querySelector('.attach-list');
    let items = [];
    let seq = 0;
    bindShare(list);
    getConfig().then(c => { container.querySelector('.attach-limit').textContent = `(최대 ${c.maxFileSizeLabel})`; })
      .catch(() => {});

    const render = () => { list.innerHTML = items.map(a => itemHtml(a, true)).join(''); };

    async function addFiles(fileList) {
      const files = Array.from(fileList);   // input.value 초기화 전에 복사
      let config = null;
      try { config = await getConfig(); } catch (_) { /* 크기 검사는 서버가 다시 한다 */ }
      for (const file of files) {
        const item = { key: `u${++seq}`, name: file.name, size: file.size, uploading: true, progress: 0 };
        if (config && file.size > config.maxFileSize) {
          Object.assign(item, { uploading: false, error: `${config.maxFileSizeLabel}를 초과해 첨부할 수 없습니다.` });
          items.push(item);
          continue;
        }
        items.push(item);
        item.ctl = uploadControl();
        uploadFile(file, p => {
          item.progress = p;
          const bar = list.querySelector(`[data-key="${item.key}"] .attach-progress > div`);
          if (bar) bar.style.width = `${p}%`;
        }, item.ctl, config)
          .then(a => Object.assign(item, a, { uploading: false }))
          .catch(e => Object.assign(item, { uploading: false, error: e.message }))
          .finally(render);
      }
      render();
    }

    drop.addEventListener('click', () => input.click());
    drop.addEventListener('keydown', e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); input.click(); } });
    input.addEventListener('change', () => { addFiles(input.files); input.value = ''; });
    list.addEventListener('click', e => {
      const btn = e.target.closest('.attach-remove');
      if (!btn) return;
      const key = btn.closest('.attach-item').dataset.key;
      items.filter(a => a.key === key && a.uploading && a.ctl).forEach(a => a.ctl.cancel());
      items = items.filter(a => a.key !== key);
      render();
    });

    const target = dropTarget || container;
    let dragDepth = 0;
    target.addEventListener('dragenter', e => { if (hasFiles(e)) { dragDepth++; drop.classList.add('attach-dragover'); } });
    target.addEventListener('dragleave', e => { if (hasFiles(e) && --dragDepth <= 0) { dragDepth = 0; drop.classList.remove('attach-dragover'); } });
    target.addEventListener('dragover', e => { if (hasFiles(e)) { e.preventDefault(); e.dataTransfer.dropEffect = 'copy'; } });
    target.addEventListener('drop', e => {
      if (!hasFiles(e)) return;
      e.preventDefault();
      dragDepth = 0;
      drop.classList.remove('attach-dragover');
      addFiles(e.dataTransfer.files);
    });

    return {
      set(attachments) {
        items = (attachments || []).map(a => ({ ...a, key: `s${a.id}` }));
        render();
      },
      // 업로드가 끝난 첨부 파일의 ID 목록 (저장 요청에 사용). 본문 이미지는 본문 내용으로 관리된다.
      ids() { return items.filter(a => a.id && a.kind !== 'IMAGE' && !a.uploading && !a.error).map(a => a.id); },
      isUploading() { return items.some(a => a.uploading); }
    };
  }

  return { keepCdnSession, refreshCdnSession, editorImageOptions, createBox, renderList, formatSize, openShareManager, downloadAll,
           openImageViewer, bindBodyImages };
})();
