// ── 첨부파일 공통 모듈 (index.html, detail.html) ─────────────────────────
// 파일은 S3에 저장되고 CloudFront(todo-att.flare.im)로 제공된다.
// CDN 접근은 서명 쿠키로 인증하므로, 페이지 로드 시 keepCdnSession()으로 쿠키를 발급받아야
// 본문 이미지와 첨부 링크가 열린다.
const Attachments = (() => {
  const MAX_FILE_SIZE = 50 * 1024 * 1024;
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

  // ── 보기용 목록 ─────────────────────────────────────────
  function itemHtml(a, removable) {
    return `
      <li class="attach-item" data-key="${esc(a.key ?? '')}">
        <i class="bi ${iconFor(a.name, a.contentType)} attach-icon"></i>
        ${a.url
          ? `<a class="attach-name" href="${esc(a.url)}" title="${esc(a.name)} 다운로드">${esc(a.name)}</a>`
          : `<span class="attach-name">${esc(a.name)}</span>`}
        <span class="attach-size">${a.error ? `<span class="text-danger">${esc(a.error)}</span>` : formatSize(a.size)}</span>
        ${removable ? '<button type="button" class="btn-close attach-remove" aria-label="첨부 삭제" title="삭제"></button>' : ''}
        ${a.uploading ? `<div class="attach-progress"><div style="width:${a.progress || 0}%"></div></div>` : ''}
      </li>`;
  }

  function renderList(ul, attachments) {
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
        <span>파일을 끌어다 놓거나 <span class="attach-pick">클릭해서 선택</span> <small class="text-muted">(최대 50MB)</small></span>
        <input type="file" multiple hidden>
      </div>
      <ul class="attach-list"></ul>`;
    const drop = container.querySelector('.attach-drop');
    const input = container.querySelector('input[type=file]');
    const list = container.querySelector('.attach-list');
    let items = [];
    let seq = 0;

    const render = () => { list.innerHTML = items.map(a => itemHtml(a, true)).join(''); };

    function addFiles(files) {
      for (const file of files) {
        const item = { key: `u${++seq}`, name: file.name, size: file.size, uploading: true, progress: 0 };
        if (file.size > MAX_FILE_SIZE) {
          Object.assign(item, { uploading: false, error: '50MB를 초과해 첨부할 수 없습니다.' });
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
      // 업로드가 끝난 첨부의 ID 목록 (저장 요청에 사용)
      ids() { return items.filter(a => a.id && !a.uploading && !a.error).map(a => a.id); },
      isUploading() { return items.some(a => a.uploading); }
    };
  }

  return { keepCdnSession, refreshCdnSession, editorImageOptions, createBox, renderList, formatSize };
})();
