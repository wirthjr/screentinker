import { api } from '../api.js';
import { showToast } from '../components/toast.js';
import { esc, canWrite } from '../utils.js';
import { t } from '../i18n.js';

let container = null;
let pollTimer = null;
let currentDevices = [];

export function render(el) {
  container = el;
  el.innerHTML = `
    <div class="view-header" style="display:flex;justify-content:space-between;align-items:flex-start;flex-wrap:wrap;gap:16px;margin-bottom:24px">
      <div>
        <h1 style="display:flex;align-items:center;gap:10px;margin:0 0 6px 0">
          <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="color:var(--primary)">
            <path d="M23 7l-7 5 7 5V7z"/>
            <rect x="1" y="5" width="15" height="14" rx="2" ry="2"/>
          </svg>
          <span data-i18n="live_cameras.title">${esc(t('live_cameras.title') || 'Câmeras ao vivo')}</span>
        </h1>
        <p class="view-description" style="margin:0;color:var(--text-secondary);font-size:14px" data-i18n="live_cameras.desc">
          ${esc(t('live_cameras.desc') || 'Monitore em tempo real as câmeras das telas ativas e inclua a transmissão em outras playlists.')}
        </p>
      </div>
      <div style="display:flex;align-items:center;gap:10px">
        <span id="liveCamCountBadge" class="badge" style="background:var(--bg-secondary);padding:6px 12px;font-size:13px;border-radius:20px;border:1px solid var(--border)">
          Carregando...
        </span>
        <button id="btnRefreshLiveCameras" class="btn btn-secondary btn-sm" style="display:flex;align-items:center;gap:6px">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <polyline points="23 4 23 10 17 10"/><polyline points="1 20 1 14 7 14"/>
            <path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"/>
          </svg>
          <span id="btnRefreshText">Atualizar</span>
        </button>
      </div>
    </div>

    <div id="liveCamerasGrid" style="display:grid;grid-template-columns:repeat(auto-fill, minmax(320px, 1fr));gap:20px">
      <div style="grid-column:1/-1;text-align:center;padding:48px 16px;color:var(--text-muted)">
        ${esc(t('common.loading') || 'Carregando telas e câmeras...')}
      </div>
    </div>
  `;

  document.getElementById('btnRefreshLiveCameras')?.addEventListener('click', () => loadCameras(true));

  loadCameras();
  startPolling();
}

export function cleanup() {
  if (pollTimer) {
    clearInterval(pollTimer);
    pollTimer = null;
  }
}

function startPolling() {
  cleanup();
  pollTimer = setInterval(() => {
    // Atualiza status silenciosamente
    loadCameras(false);
  }, 10000);
}

async function loadCameras(showLoading = false) {
  const grid = document.getElementById('liveCamerasGrid');
  const countBadge = document.getElementById('liveCamCountBadge');
  if (!grid) return;

  if (showLoading) {
    grid.innerHTML = `<div style="grid-column:1/-1;text-align:center;padding:48px 16px;color:var(--text-muted)">
      ${esc(t('common.loading') || 'Carregando...')}
    </div>`;
  }

  try {
    const devices = await api.get('/live-cameras');
    currentDevices = devices || [];
    renderCards(currentDevices);
  } catch (err) {
    console.error('[LiveCameras] Error loading devices:', err);
    if (grid && showLoading) {
      grid.innerHTML = `
        <div style="grid-column:1/-1;background:var(--bg-secondary);border:1px solid var(--danger);padding:24px;border-radius:8px;text-align:center">
          <p style="color:var(--danger);margin:0 0 8px 0;font-weight:600">Erro ao carregar câmeras</p>
          <p style="color:var(--text-muted);font-size:13px;margin:0">${esc(err.message)}</p>
        </div>
      `;
    }
  }
}

function renderCards(devices) {
  const grid = document.getElementById('liveCamerasGrid');
  const countBadge = document.getElementById('liveCamCountBadge');
  if (!grid) return;

  const liveCount = devices.filter(d => d.camera_live).length;
  if (countBadge) {
    countBadge.innerHTML = `
      <span style="display:inline-block;width:8px;height:8px;border-radius:50%;background:${liveCount > 0 ? '#ff3b30' : 'var(--text-muted)'};margin-right:6px"></span>
      <strong>${liveCount}</strong> ao vivo / ${devices.length} telas
    `;
  }

  if (devices.length === 0) {
    grid.innerHTML = `
      <div style="grid-column:1/-1;text-align:center;padding:64px 20px;background:var(--bg-secondary);border-radius:12px;border:1px dashed var(--border)">
        <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" style="color:var(--text-muted);margin-bottom:12px">
          <path d="M23 7l-7 5 7 5V7z"/><rect x="1" y="5" width="15" height="14" rx="2" ry="2"/>
        </svg>
        <h3 style="margin:0 0 8px 0;font-size:18px">Nenhuma tela cadastrada</h3>
        <p style="margin:0;color:var(--text-muted);font-size:14px">Conecte uma tela no painel de Telas para utilizar o recurso de câmera ao vivo.</p>
      </div>
    `;
    return;
  }

  // Ordena colocando primeiro as telas que estão com câmera ao vivo
  const sorted = [...devices].sort((a, b) => {
    if (a.camera_live && !b.camera_live) return -1;
    if (!a.camera_live && b.camera_live) return 1;
    if (a.online && !b.online) return -1;
    if (!a.online && b.online) return 1;
    return (a.name || '').localeCompare(b.name || '');
  });

  grid.innerHTML = sorted.map(d => {
    const isLive = d.camera_live;
    const isOnline = d.online;

    let statusPill = '';
    if (isLive) {
      statusPill = `
        <span style="display:inline-flex;align-items:center;gap:6px;background:rgba(255,59,48,0.15);color:#ff3b30;border:1px solid rgba(255,59,48,0.3);padding:3px 10px;border-radius:12px;font-size:12px;font-weight:700">
          <span style="width:8px;height:8px;border-radius:50%;background:#ff3b30;box-shadow:0 0 6px #ff3b30"></span>
          AO VIVO
        </span>
      `;
    } else if (isOnline) {
      statusPill = `
        <span style="display:inline-flex;align-items:center;gap:6px;background:rgba(22,163,74,0.12);color:var(--success, #16a34a);border:1px solid rgba(22,163,74,0.25);padding:3px 10px;border-radius:12px;font-size:12px;font-weight:600">
          <span style="width:8px;height:8px;border-radius:50%;background:var(--success, #16a34a)"></span>
          Online (Aguardando feed)
        </span>
      `;
    } else {
      statusPill = `
        <span style="display:inline-flex;align-items:center;gap:6px;background:var(--bg-secondary);color:var(--text-muted);border:1px solid var(--border);padding:3px 10px;border-radius:12px;font-size:12px">
          <span style="width:8px;height:8px;border-radius:50%;background:var(--text-muted)"></span>
          Offline
        </span>
      `;
    }

    const previewMedia = isLive
      ? `<img src="${d.stream_url}" alt="${esc(d.name)}" style="width:100%;height:190px;object-fit:cover;background:#000;display:block;" />`
      : `<div style="width:100%;height:190px;background:#111827;display:flex;flex-direction:column;align-items:center;justify-content:center;color:#6b7280;gap:8px">
          <svg width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
            <path d="M23 7l-7 5 7 5V7z"/><rect x="1" y="5" width="15" height="14" rx="2" ry="2"/>
            ${!isLive ? '<line x1="1" y1="1" x2="23" y2="23" stroke-width="2"/>' : ''}
          </svg>
          <span style="font-size:12px">${isOnline ? 'Câmera inativa no momento' : 'Tela desconectada'}</span>
        </div>`;

    return `
      <div class="card" style="display:flex;flex-direction:column;border-radius:10px;overflow:hidden;border:1px solid var(--border);background:var(--bg-card)">
        <div style="position:relative;background:#000;border-bottom:1px solid var(--border)">
          ${previewMedia}
          <div style="position:absolute;top:10px;left:10px;z-index:2">
            ${statusPill}
          </div>
          <div style="position:absolute;bottom:8px;right:8px;z-index:2">
            <button class="btn btn-secondary btn-sm" data-action="open-stream" data-id="${esc(d.id)}" style="background:rgba(0,0,0,0.65);border-color:rgba(255,255,255,0.2);color:#fff;backdrop-filter:blur(4px);padding:4px 8px;font-size:11px" title="Abrir stream em tela cheia">
              ⛶ Expandir
            </button>
          </div>
        </div>

        <div style="padding:16px;flex:1;display:flex;flex-direction:column;justify-content:space-between;gap:14px">
          <div>
            <div style="display:flex;justify-content:space-between;align-items:flex-start;gap:8px;margin-bottom:6px">
              <h3 style="margin:0;font-size:16px;font-weight:600;word-break:break-word">${esc(d.name)}</h3>
            </div>
            <div style="font-size:12px;color:var(--text-secondary);display:flex;flex-direction:column;gap:3px">
              <div><strong>Dispositivo:</strong> ${esc(d.platform || d.client_type || 'Android/Web')}</div>
              ${d.ip_address ? `<div><strong>IP:</strong> ${esc(d.ip_address)}</div>` : ''}
              ${d.location ? `<div><strong>Local:</strong> ${esc(d.location)}</div>` : ''}
            </div>
          </div>

          ${canWrite('admin') ? `
            <div style="display:flex;flex-direction:column;gap:8px;padding-top:12px;border-top:1px solid var(--border)">
              <button class="btn btn-primary btn-sm" data-action="add-to-playlist" data-id="${esc(d.id)}" data-name="${esc(d.name)}" style="width:100%;display:flex;align-items:center;justify-content:center;gap:6px">
                <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/>
                </svg>
                <span>Adicionar à Playlist</span>
              </button>
            </div>
          ` : ''}
        </div>
      </div>
    `;
  }).join('');

  // Event handlers
  grid.querySelectorAll('[data-action="open-stream"]').forEach(btn => {
    btn.addEventListener('click', (e) => {
      const devId = e.currentTarget.dataset.id;
      window.open(`/api/devices/${encodeURIComponent(devId)}/camera/live.mjpg`, '_blank');
    });
  });

  grid.querySelectorAll('[data-action="add-to-playlist"]').forEach(btn => {
    btn.addEventListener('click', (e) => {
      const devId = e.currentTarget.dataset.id;
      const devName = e.currentTarget.dataset.name;
      openAddToPlaylistModal(devId, devName);
    });
  });
}

/**
 * Modal para adicionar a câmera da tela selecionada a uma playlist.
 */
async function openAddToPlaylistModal(deviceId, deviceName) {
  const overlay = document.createElement('div');
  overlay.className = 'modal-overlay';
  overlay.innerHTML = `
    <div class="modal" style="max-width:500px;width:95%">
      <div class="modal-header">
        <h3 style="display:flex;align-items:center;gap:8px">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="color:var(--primary)">
            <path d="M23 7l-7 5 7 5V7z"/><rect x="1" y="5" width="15" height="14" rx="2" ry="2"/>
          </svg>
          Adicionar Câmera à Playlist
        </h3>
        <button class="btn-icon" type="button" data-close aria-label="Fechar">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
          </svg>
        </button>
      </div>

      <div class="modal-body">
        <div style="margin-bottom:16px;background:var(--bg-secondary);padding:10px 14px;border-radius:6px;font-size:13px">
          <strong>Origem:</strong> Câmera de <em>${esc(deviceName)}</em>
        </div>

        <div class="form-group" style="margin-bottom:14px">
          <label for="camModalPlaylist" style="display:block;margin-bottom:6px;font-weight:600">Selecione a Playlist de destino</label>
          <select id="camModalPlaylist" class="input" style="width:100%">
            <option value="">Carregando playlists...</option>
          </select>
        </div>

        <div id="camZoneGroup" class="form-group" style="margin-bottom:14px;display:none">
          <label for="camModalZone" style="display:block;margin-bottom:6px;font-weight:600">Zona do Layout</label>
          <select id="camModalZone" class="input" style="width:100%">
            <option value="">Zona Principal (Padrão)</option>
          </select>
        </div>

        <div class="form-group" style="margin-bottom:14px">
          <label for="camModalDuration" style="display:block;margin-bottom:6px;font-weight:600">Duração na rotação (segundos)</label>
          <select id="camModalDuration" class="input" style="width:100%">
            <option value="15">15 segundos</option>
            <option value="30" selected>30 segundos</option>
            <option value="60">1 minuto</option>
            <option value="120">2 minutos</option>
            <option value="300">5 minutos</option>
            <option value="3600">Contínuo (1 hora)</option>
          </select>
        </div>

        <div class="form-group" style="margin-bottom:14px">
          <label for="camModalWidgetName" style="display:block;margin-bottom:6px;font-weight:600">Nome do Item</label>
          <input id="camModalWidgetName" type="text" class="input" style="width:100%" value="Câmera: ${esc(deviceName)}" maxlength="80" />
        </div>

        <div id="camModalError" style="display:none;color:var(--danger);font-size:13px;margin-top:8px"></div>
      </div>

      <div class="modal-footer" style="display:flex;justify-content:flex-end;gap:10px">
        <button class="btn btn-secondary" type="button" data-close>Cancelar</button>
        <button class="btn btn-primary" type="button" id="camModalConfirm">
          Adicionar
        </button>
      </div>
    </div>
  `;

  document.body.appendChild(overlay);

  const playlistSelect = overlay.querySelector('#camModalPlaylist');
  const zoneGroup = overlay.querySelector('#camZoneGroup');
  const zoneSelect = overlay.querySelector('#camModalZone');
  const durSelect = overlay.querySelector('#camModalDuration');
  const nameInput = overlay.querySelector('#camModalWidgetName');
  const confirmBtn = overlay.querySelector('#camModalConfirm');
  const errorBox = overlay.querySelector('#camModalError');

  function close() {
    overlay.remove();
  }

  overlay.querySelectorAll('[data-close]').forEach(btn => btn.addEventListener('click', close));

  let playlists = [];

  try {
    playlists = await api.get('/playlists');
    if (!playlists || playlists.length === 0) {
      playlistSelect.innerHTML = `<option value="">Nenhuma playlist encontrada</option>`;
      confirmBtn.disabled = true;
      return;
    }

    playlistSelect.innerHTML = playlists.map(p => `
      <option value="${esc(p.id)}">${esc(p.name)}</option>
    `).join('');

    // Ao mudar playlist, verifica layout
    async function updateZones() {
      const plId = playlistSelect.value;
      const pl = playlists.find(x => x.id === plId);
      if (pl && pl.layout_id) {
        try {
          const layout = await api.get(`/layouts/${encodeURIComponent(pl.layout_id)}`);
          if (layout && layout.zones && layout.zones.length > 0) {
            zoneGroup.style.display = 'block';
            zoneSelect.innerHTML = layout.zones.map(z => `
              <option value="${esc(z.id)}">${esc(z.name || z.id)} (${z.width}x${z.height})</option>
            `).join('');
            return;
          }
        } catch (_) { }
      }
      zoneGroup.style.display = 'none';
      zoneSelect.innerHTML = `<option value="">Padrão</option>`;
    }

    playlistSelect.addEventListener('change', updateZones);
    await updateZones();

  } catch (e) {
    playlistSelect.innerHTML = `<option value="">Erro ao carregar playlists</option>`;
    confirmBtn.disabled = true;
  }

  confirmBtn.addEventListener('click', async () => {
    const playlistId = playlistSelect.value;
    if (!playlistId) {
      errorBox.textContent = 'Por favor, selecione uma playlist.';
      errorBox.style.display = 'block';
      return;
    }

    confirmBtn.disabled = true;
    confirmBtn.textContent = 'Adicionando...';
    errorBox.style.display = 'none';

    try {
      const res = await api.post(`/live-cameras/${encodeURIComponent(deviceId)}/add-to-playlist`, {
        playlist_id: playlistId,
        zone_id: zoneSelect.value || null,
        duration_sec: parseInt(durSelect.value, 10) || 30,
        name: nameInput.value.trim() || `Câmera: ${deviceName}`
      });

      showToast(`Câmera adicionada à playlist com sucesso!`, 'success');
      close();
    } catch (err) {
      console.error('[LiveCameras] Error adding to playlist:', err);
      confirmBtn.disabled = false;
      confirmBtn.textContent = 'Adicionar';
      errorBox.textContent = err.message || 'Erro ao adicionar câmera à playlist.';
      errorBox.style.display = 'block';
    }
  });
}

