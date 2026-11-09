'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const Database = require('better-sqlite3');
const { BUILTIN_WIDGET_TYPES } = require('../lib/plugins/reserved');

process.env.JWT_SECRET = 'test-secret-camera-widget';

const db = new Database(':memory:');
db.exec(`CREATE TABLE widgets (id TEXT PRIMARY KEY, widget_type TEXT, config TEXT, workspace_id TEXT);`);
db.exec(`CREATE TABLE rooms (id TEXT PRIMARY KEY, workspace_id TEXT, name TEXT, timezone TEXT);`);
const dbModulePath = require.resolve('../db/database');
require.cache[dbModulePath] = { id: dbModulePath, filename: dbModulePath, loaded: true, exports: { db } };

const express = require('express');
const widgetsRouter = require('../routes/widgets');
const app = express();
app.use(express.json());
app.use('/api/widgets', widgetsRouter);
const server = app.listen(0);
let base;

test.before(async () => {
  await new Promise(r => server.listening ? r() : server.once('listening', r));
  base = `http://127.0.0.1:${server.address().port}`;
});
test.after(() => {
  server.close();
  db.close();
});

const seed = (id, type, config, workspaceId = 'ws1') =>
  db.prepare('INSERT INTO widgets (id, widget_type, config, workspace_id) VALUES (?,?,?,?)').run(id, type, JSON.stringify(config), workspaceId);

test('builtin widget types includes camera', () => {
  assert.ok(BUILTIN_WIDGET_TYPES.includes('camera'), 'camera must be a registered builtin widget type');
});

test('camera widget renders with device camera and Permissions-Policy header', async () => {
  seed('cam1', 'camera', {
    source_type: 'device',
    camera_facing: 'user',
    mirror: true,
    fit: 'cover',
    show_badge: true,
    badge_text: 'AO VIVO',
    title: 'Portaria Principal',
    show_clock: true,
    background: '#000000'
  });

  const res = await fetch(`${base}/api/widgets/cam1/render`);
  assert.equal(res.status, 200);
  assert.equal(res.headers.get('permissions-policy'), 'camera=*, microphone=*');
  const html = await res.text();

  assert.ok(html.includes('<video id="cameraVideo"'), 'video element rendered');
  assert.ok(html.includes('Portaria Principal'), 'title rendered');
  assert.ok(html.includes('AO VIVO'), 'badge text rendered');
  assert.ok(html.includes('navigator.mediaDevices.getUserMedia'), 'getUserMedia call present');
  assert.ok(html.includes('transform: scaleX(-1)'), 'mirror mode applies scaleX(-1)');
});

test('camera widget renders stream mode correctly and escapes XSS', async () => {
  seed('cam2', 'camera', {
    source_type: 'stream',
    stream_url: 'https://example.com/live/feed.m3u8',
    title: '<b>Dangerous</b>',
    badge_text: '<script>alert(1)</script>',
  });

  const res = await fetch(`${base}/api/widgets/cam2/render`);
  assert.equal(res.status, 200);
  const html = await res.text();

  assert.ok(!html.includes('<script>alert(1)</script>'), 'badge_text must be escaped');
  assert.ok(html.includes('&lt;script&gt;alert(1)&lt;/script&gt;'), 'escaped script tag in badge');
  assert.ok(!html.includes('<b>Dangerous</b>'), 'title must be HTML-escaped');
  assert.ok(html.includes('&lt;b&gt;Dangerous&lt;/b&gt;'), 'escaped title rendered');
  assert.ok(html.includes('https://example.com/live/feed.m3u8'), 'stream url present in safe config');
});

