// API 토큰(PAT) 관리 모달. MCP 클라이언트·외부 스크립트가 쓰는 장기 Bearer 토큰을 발급·삭제한다.
// index.html 의 authFetch / showToast 를 사용한다.
const ApiTokens = (() => {
  let modalEl = null;
  let scopes = [];

  function esc(s) {
    return String(s ?? '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }

  function fmt(iso) {
    if (!iso) return '-';
    return new Date(iso).toLocaleString('ko-KR', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });
  }

  function ensureModal() {
    if (modalEl) return modalEl;
    modalEl = document.createElement('div');
    modalEl.className = 'modal fade';
    modalEl.tabIndex = -1;
    modalEl.innerHTML = `
      <div class="modal-dialog modal-lg modal-dialog-scrollable">
        <div class="modal-content">
          <div class="modal-header">
            <h6 class="modal-title"><i class="bi bi-key me-2"></i>API 토큰 (MCP 연결용)</h6>
            <button type="button" class="btn-close" data-bs-dismiss="modal"></button>
          </div>
          <div class="modal-body">
            <p class="text-muted small mb-3">
              Claude Desktop, Claude Code 같은 MCP 클라이언트나 스크립트가 이 계정으로 할 일을 다룰 때 쓰는 토큰입니다.
              로그인 비밀번호와는 별개이며, 여기서 언제든 삭제(폐기)할 수 있습니다.
            </p>
            <div class="api-token-created d-none"></div>
            <form class="api-token-form border rounded p-3 mb-3 bg-light">
              <div class="row g-2 align-items-end">
                <div class="col-md-5">
                  <label class="form-label small mb-1">이름</label>
                  <input type="text" class="form-control form-control-sm api-token-name" maxlength="100" placeholder="예: 맥북 Claude Desktop" required>
                </div>
                <div class="col-md-3">
                  <label class="form-label small mb-1">만료</label>
                  <select class="form-select form-select-sm api-token-expires">
                    <option value="30">30일</option>
                    <option value="90" selected>90일</option>
                    <option value="365">1년</option>
                    <option value="">만료 없음</option>
                  </select>
                </div>
                <div class="col-md-4">
                  <label class="form-label small mb-1">권한</label>
                  <div class="api-token-scopes small"></div>
                </div>
              </div>
              <div class="mt-2 text-end">
                <button type="submit" class="btn btn-primary btn-sm"><i class="bi bi-plus-lg me-1"></i>토큰 발급</button>
              </div>
            </form>
            <div class="api-token-list"></div>
          </div>
        </div>
      </div>`;
    document.body.appendChild(modalEl);
    modalEl.querySelector('.api-token-form').addEventListener('submit', onCreate);
    modalEl.querySelector('.api-token-list').addEventListener('click', onListClick);
    modalEl.querySelector('.api-token-created').addEventListener('click', onCreatedClick);
    return modalEl;
  }

  async function loadScopes() {
    if (scopes.length) return;
    const res = await authFetch('/api/tokens/scopes');
    if (!res) return;
    const data = await res.json();
    scopes = data.data || [];
    modalEl.querySelector('.api-token-scopes').innerHTML = scopes.map(s => `
      <div class="form-check form-check-inline me-2">
        <input class="form-check-input" type="checkbox" value="${esc(s.value)}" id="scope-${esc(s.value).replace(/[^a-z]/g, '-')}" checked>
        <label class="form-check-label" for="scope-${esc(s.value).replace(/[^a-z]/g, '-')}" title="${esc(s.description)}">${esc(s.value)}</label>
      </div>`).join('');
  }

  async function open() {
    const el = ensureModal();
    el.querySelector('.api-token-created').classList.add('d-none');
    bootstrap.Modal.getOrCreateInstance(el).show();
    await loadScopes();
    await refresh();
  }

  async function refresh() {
    const list = modalEl.querySelector('.api-token-list');
    list.innerHTML = '<div class="text-center text-muted py-3">불러오는 중...</div>';
    const res = await authFetch('/api/tokens');
    if (!res) return;
    const data = await res.json();
    const items = data.data || [];
    if (!items.length) {
      list.innerHTML = '<div class="text-center text-muted py-3 small">발급된 토큰이 없습니다.</div>';
      return;
    }
    list.innerHTML = `
      <table class="table table-sm align-middle mb-0">
        <thead class="small text-muted"><tr><th>이름</th><th>토큰</th><th>권한</th><th>마지막 사용</th><th>만료</th><th></th></tr></thead>
        <tbody>${items.map(t => `
          <tr class="${t.expired ? 'text-muted' : ''}">
            <td>${esc(t.name)}</td>
            <td><code>${esc(t.prefix)}…</code></td>
            <td class="small">${(t.scopes || []).map(esc).join('<br>')}</td>
            <td class="small">${fmt(t.lastUsedAt)}</td>
            <td class="small">${t.expiresAt ? fmt(t.expiresAt) + (t.expired ? ' (만료됨)' : '') : '없음'}</td>
            <td class="text-end"><button type="button" class="btn btn-sm btn-outline-danger api-token-revoke" data-id="${t.id}" data-name="${esc(t.name)}" title="삭제(폐기)"><i class="bi bi-trash"></i></button></td>
          </tr>`).join('')}
        </tbody>
      </table>`;
  }

  async function onCreate(e) {
    e.preventDefault();
    const name = modalEl.querySelector('.api-token-name').value.trim();
    const expires = modalEl.querySelector('.api-token-expires').value;
    const selected = Array.from(modalEl.querySelectorAll('.api-token-scopes input:checked')).map(i => i.value);
    if (!selected.length) { alert('권한을 하나 이상 선택해 주세요.'); return; }
    const res = await authFetch('/api/tokens', {
      method: 'POST',
      body: JSON.stringify({ name, expiresInDays: expires ? Number(expires) : null, scopes: selected })
    });
    if (!res) return;
    const data = await res.json();
    if (!data.success) { alert(data.message || '토큰을 발급하지 못했습니다.'); return; }
    modalEl.querySelector('.api-token-name').value = '';
    showCreated(data.data);
    await refresh();
  }

  function showCreated(t) {
    const base = location.origin;
    const box = modalEl.querySelector('.api-token-created');
    box.classList.remove('d-none');
    box.innerHTML = `
      <div class="alert alert-success">
        <div class="fw-semibold mb-1"><i class="bi bi-check-circle me-1"></i>토큰이 발급되었습니다. 이 값은 다시 볼 수 없으니 지금 복사해 두세요.</div>
        <div class="input-group input-group-sm mb-2">
          <input type="text" class="form-control font-monospace api-token-value" readonly value="${esc(t.token)}">
          <button type="button" class="btn btn-outline-secondary api-token-copy" data-copy=".api-token-value">복사</button>
        </div>
        <details class="small">
          <summary>MCP 클라이언트 설정 예시</summary>
          <div class="mt-2 mb-1 fw-semibold">Claude Code</div>
          <pre class="bg-white border rounded p-2 mb-2"><code class="api-token-cc">claude mcp add --transport http flare-todo ${esc(base)}/mcp --header "Authorization: Bearer ${esc(t.token)}"</code></pre>
          <div class="mb-1 fw-semibold">Claude Desktop / Cursor (mcpServers 항목)</div>
          <pre class="bg-white border rounded p-2 mb-0"><code class="api-token-json">${esc(JSON.stringify({
            'flare-todo': { type: 'http', url: base + '/mcp', headers: { Authorization: 'Bearer ' + t.token } }
          }, null, 2))}</code></pre>
        </details>
      </div>`;
  }

  async function onCreatedClick(e) {
    const btn = e.target.closest('.api-token-copy');
    if (!btn) return;
    const input = modalEl.querySelector(btn.dataset.copy);
    try {
      await navigator.clipboard.writeText(input.value);
      btn.textContent = '복사됨';
      setTimeout(() => { btn.textContent = '복사'; }, 1500);
    } catch (_) {
      input.select();
    }
  }

  async function onListClick(e) {
    const btn = e.target.closest('.api-token-revoke');
    if (!btn) return;
    if (!confirm(`'${btn.dataset.name}' 토큰을 삭제할까요?\n이 토큰을 쓰는 MCP 클라이언트는 즉시 연결이 끊어집니다.`)) return;
    const res = await authFetch(`/api/tokens/${btn.dataset.id}`, { method: 'DELETE' });
    if (!res) return;
    const data = await res.json();
    if (!data.success) { alert(data.message || '삭제하지 못했습니다.'); return; }
    if (typeof showToast === 'function') showToast('토큰이 삭제되었습니다');
    await refresh();
  }

  return { open };
})();
