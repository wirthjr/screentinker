'use strict';

const express = require('express');
const crypto = require('crypto');
const router = express.Router();
const { db } = require('../db/database');
const liveCameraHub = require('../lib/live-camera-hub');
const { requireWorkspaceAdmin } = require('../lib/permissions');

/**
 * Handlers públicos para streaming e ingestão de frames de câmera de tela.
 */

// Stream contínuo MJPEG de uma tela
function mjpegHandler(req, res) {
  const deviceId = req.params.id;
  if (!deviceId) return res.status(400).send('Device ID missing');
  
  const device = db.prepare('SELECT id, name FROM devices WHERE id = ?').get(deviceId);
  if (!device) return res.status(404).send('Device not found');

  liveCameraHub.subscribeMjpeg(deviceId, req, res);
}

// Snapshot mais recente em JPEG
function snapshotHandler(req, res) {
  const deviceId = req.params.id;
  if (!deviceId) return res.status(400).send('Device ID missing');

  const snap = liveCameraHub.getSnapshot(deviceId);
  if (snap && snap.buffer) {
    res.setHeader('Content-Type', 'image/jpeg');
    res.setHeader('Cache-Control', 'no-cache, no-store, must-revalidate');
    res.setHeader('Pragma', 'no-cache');
    res.setHeader('Expires', '0');
    return res.end(snap.buffer);
  }

  // Placeholder SVG quando não há frame ativo
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="640" height="360" viewBox="0 0 640 360">
    <rect width="100%" height="100%" fill="#111827"/>
    <circle cx="320" cy="160" r="44" fill="#374151" stroke="#4b5563" stroke-width="3"/>
    <circle cx="320" cy="160" r="18" fill="#1f2937"/>
    <text x="320" y="240" font-family="system-ui, sans-serif" font-size="16" fill="#9ca3af" text-anchor="middle">
      Aguardando transmissão da câmera
    </text>
  </svg>`;
  res.setHeader('Content-Type', 'image/svg+xml');
  res.setHeader('Cache-Control', 'no-cache, no-store');
  return res.send(svg);
}

// Ingestão de frame vindo do player da tela
function frameHandler(req, res) {
  const deviceId = req.params.id;
  if (!deviceId) return res.status(400).json({ error: 'Device ID missing' });

  // req.body pode ser raw Buffer (se body-parser raw/octet-stream) ou JSON { frame: 'data:image/jpeg...' }
  let frame = null;
  if (Buffer.isBuffer(req.body)) {
    frame = req.body;
  } else if (req.body && req.body.frame) {
    frame = req.body.frame;
  }

  if (!frame) {
    return res.status(400).json({ error: 'No frame provided' });
  }

  const success = liveCameraHub.pushFrame(deviceId, frame, {
    ip: req.ip,
    timestamp: Date.now()
  });

  return res.json({ success, timestamp: Date.now() });
}

/**
 * Endpoints autenticados do Dashboard (montados sob /api/live-cameras com tenancy)
 */

// Listar telas com estado de câmera ao vivo
router.get('/', (req, res) => {
  if (!req.workspaceId) return res.json([]);

  const devices = db.prepare(`
    SELECT id, name, status, last_heartbeat, client_type, platform, ip_address, location_label, tags
    FROM devices
    WHERE workspace_id = ?
    ORDER BY name ASC
  `).all(req.workspaceId);

  const nowSec = Math.floor(Date.now() / 1000);
  const result = devices.map(d => {
    const isLive = liveCameraHub.isCameraLive(d.id);
    const lastSec = d.last_heartbeat ? Number(d.last_heartbeat) : 0;
    const isOnline = d.status === 'online' || (nowSec - lastSec < 45);

    return {
      id: d.id,
      name: d.name || 'Tela sem nome',
      status: d.status,
      online: isOnline,
      last_heartbeat: d.last_heartbeat,
      client_type: d.client_type,
      platform: d.platform,
      ip_address: d.ip_address,
      location: d.location_label,
      camera_live: isLive,
      stream_url: `/api/devices/${d.id}/camera/live.mjpg`,
      snapshot_url: `/api/devices/${d.id}/camera/snapshot`
    };
  });

  res.json(result);
});

// Adicionar a câmera de uma tela diretamente a uma playlist (apenas administradores)
router.post('/:id/add-to-playlist', requireWorkspaceAdmin, (req, res) => {
  if (!req.workspaceId) return res.status(401).json({ error: 'Unauthorized' });

  const deviceId = req.params.id;
  const { playlist_id, zone_id, duration_sec, name } = req.body || {};

  if (!playlist_id) {
    return res.status(400).json({ error: 'playlist_id is required' });
  }

  const device = db.prepare('SELECT id, name FROM devices WHERE id = ? AND workspace_id = ?').get(deviceId, req.workspaceId);
  if (!device) {
    return res.status(404).json({ error: 'Device not found in workspace' });
  }

  const playlist = db.prepare('SELECT id, name FROM playlists WHERE id = ? AND workspace_id = ?').get(playlist_id, req.workspaceId);
  if (!playlist) {
    return res.status(404).json({ error: 'Playlist not found' });
  }

  // 1. Criar widget de câmera de outra tela
  const widgetId = crypto.randomUUID();
  const widgetName = name || `Câmera: ${device.name}`;
  const widgetConfig = {
    source_type: 'screen_camera',
    target_device_id: device.id,
    stream_url: `/api/devices/${device.id}/camera/live.mjpg`,
    snapshot_url: `/api/devices/${device.id}/camera/snapshot`,
    show_badge: true,
    badge_text: `Câmera - ${device.name}`,
    fit: 'cover',
    mirror: false
  };

  db.prepare(`
    INSERT INTO widgets (id, workspace_id, name, widget_type, config, created_at, updated_at)
    VALUES (?, ?, ?, 'camera', ?, datetime('now'), datetime('now'))
  `).run(widgetId, req.workspaceId, widgetName, JSON.stringify(widgetConfig));

  // 2. Determinar sort_order
  const maxOrder = db.prepare(`
    SELECT COALESCE(MAX(sort_order), -1) as m
    FROM playlist_items
    WHERE playlist_id = ?
  `).get(playlist_id);
  const nextOrder = (maxOrder ? maxOrder.m : -1) + 1;

  // 3. Inserir item na playlist (schema correto de playlist_items)
  const resInsert = db.prepare(`
    INSERT INTO playlist_items (playlist_id, content_id, widget_id, child_playlist_id, zone_id, duration_sec, sort_order)
    VALUES (?, NULL, ?, NULL, ?, ?, ?)
  `).run(
    playlist_id,
    widgetId,
    zone_id || null,
    Math.max(5, parseInt(duration_sec, 10) || 30),
    nextOrder
  );
  const itemId = resInsert.lastInsertRowid;

  // 4. Tentar publicar para entrar no ar imediatamente nas telas; caso contrário, marca como rascunho (draft)
  let published = false;
  try {
    const releases = require('../lib/releases');
    releases.releasePlaylist(db, playlist_id, req, { actor: releases.actorOf(req) });
    published = true;
  } catch (err) {
    db.prepare("UPDATE playlists SET status = 'draft', updated_at = strftime('%s','now') WHERE id = ?").run(playlist_id);
  }

  res.json({
    success: true,
    published,
    widget_id: widgetId,
    item_id: itemId,
    playlist_id: playlist.id,
    playlist_name: playlist.name
  });
});

module.exports = router;
module.exports.mjpegHandler = mjpegHandler;
module.exports.snapshotHandler = snapshotHandler;
module.exports.frameHandler = frameHandler;

