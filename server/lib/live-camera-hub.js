'use strict';

/**
 * Hub central de distribuição de vídeo e snapshots de câmeras ao vivo de telas.
 * Gerencia recepção de frames de dispositivos (telas) e transmissão via WebSocket e MJPEG.
 */

const BOUNDARY = 'livecamera';

class LiveCameraHub {
  constructor() {
    this.cameras = new Map(); // deviceId -> { lastFrame: Buffer, updatedAt: number, listeners: Set<Response>, wsSubscribers: Set<WebSocket>, wsPublishers: Set<WebSocket>, meta: {} }
    // Limpeza periódica de câmeras inativas a cada 60s
    setInterval(() => this.cleanup(), 60000);
  }

  _getOrCreateEntry(deviceId) {
    let entry = this.cameras.get(deviceId);
    if (!entry) {
      entry = {
        lastFrame: null,
        updatedAt: 0,
        listeners: new Set(),
        wsSubscribers: new Set(),
        wsPublishers: new Set(),
        meta: {}
      };
      this.cameras.set(deviceId, entry);
    }
    return entry;
  }

  /**
   * Anexa o servidor WebSocket para receber e transmitir feeds de vídeo em tempo real.
   */
  attach(server) {
    const WebSocket = require('ws');
    const wss = new WebSocket.Server({ noServer: true, maxPayload: 3 * 1024 * 1024 });
    const WS_PATH_RE = /^\/api\/devices\/([^/]+)\/camera\/live\.ws$/;

    server.on('upgrade', (req, socket, head) => {
      let u;
      try {
        u = new URL(req.url, 'http://localhost');
      } catch (_) {
        return;
      }
      const match = WS_PATH_RE.exec(u.pathname);
      if (!match) return;

      const deviceId = decodeURIComponent(match[1]);
      const isPublisher = u.searchParams.get('role') === 'pub';

      wss.handleUpgrade(req, socket, head, (ws) => {
        this.handleWsConnection(deviceId, ws, isPublisher);
      });
    });
  }

  /**
   * Gerencia conexões WebSocket de câmeras (publicador ou assinante).
   */
  handleWsConnection(deviceId, ws, isPublisher) {
    const entry = this._getOrCreateEntry(deviceId);

    if (isPublisher) {
      entry.wsPublishers.add(ws);
      ws.on('message', (data) => {
        let buf;
        if (Buffer.isBuffer(data)) {
          buf = data;
        } else if (data instanceof ArrayBuffer) {
          buf = Buffer.from(data);
        } else if (typeof data === 'string') {
          buf = Buffer.from(data, 'base64');
        }
        if (buf) {
          this.pushFrame(deviceId, buf);
        }
      });
      const cleanupPub = () => {
        entry.wsPublishers.delete(ws);
      };
      ws.on('close', cleanupPub);
      ws.on('error', cleanupPub);
    } else {
      entry.wsSubscribers.add(ws);
      // Se já houver um frame recente, envia imediatamente
      if (entry.lastFrame && (Date.now() - entry.updatedAt < 60000)) {
        try {
          ws.send(entry.lastFrame);
        } catch (_) {}
      }
      const cleanupSub = () => {
        entry.wsSubscribers.delete(ws);
      };
      ws.on('close', cleanupSub);
      ws.on('error', cleanupSub);
    }
  }

  /**
   * Recebe um novo frame enviado por uma tela.
   * @param {string} deviceId ID do dispositivo de origem
   * @param {Buffer|string} frameData Buffer JPEG ou data URL base64
   * @param {object} meta Metadados opcionais (resolução, facing, etc.)
   */
  pushFrame(deviceId, frameData, meta = {}) {
    if (!deviceId || !frameData) return false;

    let buffer;
    if (Buffer.isBuffer(frameData)) {
      buffer = frameData;
    } else if (typeof frameData === 'string') {
      const base64Index = frameData.indexOf('base64,');
      const raw = base64Index !== -1 ? frameData.slice(base64Index + 7) : frameData;
      buffer = Buffer.from(raw, 'base64');
    } else {
      return false;
    }

    // Limitar tamanho máximo por frame para segurança (2MB)
    if (buffer.length > 2 * 1024 * 1024) return false;

    const entry = this._getOrCreateEntry(deviceId);
    entry.lastFrame = buffer;
    entry.updatedAt = Date.now();
    if (meta && Object.keys(meta).length > 0) {
      entry.meta = Object.assign(entry.meta, meta);
    }

    // 1. Transmissão imediata via WebSocket para os assinantes conectados
    if (entry.wsSubscribers.size > 0) {
      for (const ws of entry.wsSubscribers) {
        if (ws.readyState === 1 /* OPEN */) {
          try {
            ws.send(buffer);
          } catch (_) {
            entry.wsSubscribers.delete(ws);
          }
        }
      }
    }

    // 2. Transmissão imediata para os ouvintes do stream MJPEG (RFC 2046 compliance)
    if (entry.listeners.size > 0) {
      const header = Buffer.from(
        `--${BOUNDARY}\r\nContent-Type: image/jpeg\r\nContent-Length: ${buffer.length}\r\nX-Timestamp: ${entry.updatedAt}\r\n\r\n`
      );
      const footer = Buffer.from('\r\n');

      for (const res of entry.listeners) {
        try {
          res.write(header);
          res.write(buffer);
          res.write(footer);
        } catch (_) {
          entry.listeners.delete(res);
        }
      }
    }

    return true;
  }

  /**
   * Retorna o snapshot mais recente da câmera da tela.
   */
  getSnapshot(deviceId) {
    const entry = this.cameras.get(deviceId);
    if (!entry || !entry.lastFrame) return null;
    // Considera expirado se não houver frame há mais de 2 minutos
    if (Date.now() - entry.updatedAt > 120000) return null;
    return {
      buffer: entry.lastFrame,
      updatedAt: entry.updatedAt,
      meta: entry.meta
    };
  }

  /**
   * Informa se a câmera de um dispositivo está ativa e transmitindo nos últimos 25 segundos.
   */
  isCameraLive(deviceId) {
    const entry = this.cameras.get(deviceId);
    if (!entry || !entry.lastFrame) return false;
    return Date.now() - entry.updatedAt < 25000;
  }

  /**
   * Registra uma conexão HTTP para receber o feed de vídeo MJPEG contínuo.
   */
  subscribeMjpeg(deviceId, req, res) {
    const entry = this._getOrCreateEntry(deviceId);

    res.setHeader('Content-Type', `multipart/x-mixed-replace; boundary=${BOUNDARY}`);
    res.setHeader('Cache-Control', 'no-cache, no-store, must-revalidate, max-age=0');
    res.setHeader('Pragma', 'no-cache');
    res.setHeader('Connection', 'close');
    res.setHeader('Expires', '0');
    res.setHeader('X-Accel-Buffering', 'no'); // Desativa buffering no Nginx para fluidez imediata
    if (res.socket && res.socket.setNoDelay) {
      res.socket.setNoDelay(true);
    }

    entry.listeners.add(res);

    // Se já houver um frame recente, envia de imediato para não esperar o próximo ciclo
    if (entry.lastFrame && (Date.now() - entry.updatedAt < 60000)) {
      try {
        const header = Buffer.from(
          `--${BOUNDARY}\r\nContent-Type: image/jpeg\r\nContent-Length: ${entry.lastFrame.length}\r\nX-Timestamp: ${entry.updatedAt}\r\n\r\n`
        );
        res.write(header);
        res.write(entry.lastFrame);
        res.write(Buffer.from('\r\n'));
      } catch (_) {
        entry.listeners.delete(res);
        return;
      }
    }

    const cleanup = () => {
      entry.listeners.delete(res);
    };

    req.on('close', cleanup);
    req.on('end', cleanup);
    res.on('error', cleanup);
  }

  /**
   * Remove referências antigas de câmeras inativas sem ouvintes.
   */
  cleanup() {
    const now = Date.now();
    for (const [id, entry] of this.cameras.entries()) {
      if (
        entry.listeners.size === 0 &&
        entry.wsSubscribers.size === 0 &&
        entry.wsPublishers.size === 0 &&
        now - entry.updatedAt > 300000
      ) {
        this.cameras.delete(id);
      }
    }
  }
}

module.exports = new LiveCameraHub();
