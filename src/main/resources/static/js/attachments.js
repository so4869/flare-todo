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

  // ── 업로드 (진행률 표시를 위해 XHR 사용) ───────────────────
  function upload(url, file, filename, onProgress) {
    return new Promise((resolve, reject) => {
      const form = new FormData();
      form.append('file', file, filename || file.name);
      const xhr = new XMLHttpRequest();
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
      xhr.send(form);
    });
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

  function sharePanelHtml() {
    return `
      <div class="attach-share">
        <div class="attach-share-row">
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

  function updateSharePreview(panel) {
    const days = parseFloat(panel.querySelector('.attach-share-days input').value);
    const preview = panel.querySelector('.attach-share-preview');
    preview.textContent = days > 0
      ? `${describeDays(days)} 후 만료 (${formatDateTime(new Date(Date.now() + days * 86400000))})`
      : '0보다 큰 값을 입력하세요';
  }

  async function createShareLink(panel, attachmentId) {
    const errorEl = panel.querySelector('.attach-share-error');
    const btn = panel.querySelector('.attach-share-create');
    errorEl.classList.replace('text-muted', 'text-danger');
    errorEl.textContent = '';
    const days = parseFloat(panel.querySelector('.attach-share-days input').value);
    if (!(days > 0)) { errorEl.textContent = '유효기간을 0보다 크게 입력하세요.'; return; }
    btn.disabled = true;
    try {
      const res = await authFetch(`/api/attachments/${attachmentId}/share`, {
        method: 'POST',
        body: JSON.stringify({ days })
      });
      const data = await res.json();
      if (!data.success) { errorEl.textContent = data.message; return; }
      panel.dataset.linkId = data.data.id;
      panel.querySelector('.attach-share-url').value = data.data.url;
      panel.querySelector('.attach-share-expires').textContent =
        `${formatDateTime(new Date(data.data.expiresAt))}까지 누구나 이 링크로 받을 수 있습니다.`;
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
      if (e.target.closest('.attach-share-btn')) {
        const open = li.querySelector('.attach-share');
        if (open) { open.remove(); return; }
        li.insertAdjacentHTML('beforeend', sharePanelHtml());
        const panel = li.querySelector('.attach-share');
        updateSharePreview(panel);
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

  // ── 목록 항목 ───────────────────────────────────────────
  // 본문 이미지(kind=IMAGE)는 본문에서 지워야 사라지므로 삭제 버튼 대신 '본문' 표시
  function itemHtml(a, removable) {
    const isImage = a.kind === 'IMAGE';
    const ready = a.id && !a.uploading && !a.error;
    return `
      <li class="attach-item" data-key="${esc(a.key ?? '')}" data-id="${esc(a.id ?? '')}">
        <i class="bi ${iconFor(a.name, a.contentType)} attach-icon"></i>
        ${a.url
          ? `<a class="attach-name" href="${esc(a.url)}"${isImage ? ' target="_blank" rel="noopener"' : ''} title="${esc(a.name)}">${esc(a.name)}</a>`
          : `<span class="attach-name">${esc(a.name)}</span>`}
        ${isImage ? '<span class="attach-badge" title="본문에 들어 있는 이미지입니다. 본문에서 지우면 목록에서도 사라집니다.">본문</span>' : ''}
        <span class="attach-size">${a.error ? `<span class="text-danger">${esc(a.error)}</span>` : formatSize(a.size)}</span>
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
        upload('/api/attachments', file, file.name, p => {
          item.progress = p;
          const bar = list.querySelector(`[data-key="${item.key}"] .attach-progress > div`);
          if (bar) bar.style.width = `${p}%`;
        })
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

  return { keepCdnSession, refreshCdnSession, editorImageOptions, createBox, renderList, formatSize, openShareManager };
})();
