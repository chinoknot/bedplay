'use strict';
// ---------------------------------------------------------------- language (IT / EN, from the device locale)
var IT = /^it/i.test(navigator.language || '');
var EN = {
  'Collega il tuo account Xbox': 'Link your Xbox account',
  'Caricamento...': 'Loading...',
  'Dal telefono o dal PC apri': 'On your phone or PC open',
  'e inserisci questo codice:': 'and enter this code:',
  'Attendo la conferma...': 'Waiting for confirmation...',
  'Accesso non riuscito': 'Sign-in failed',
  'Riprova': 'Retry',
  'Errore di rete': 'Network error',
  'La tua console': 'Your console',
  'Cerco le console...': 'Looking for consoles...',
  'Altro': 'More',
  'Qualita video: ': 'Video quality: ',
  '  (premi per cambiare)': '  (press to change)',
  '540p (leggera)': '540p (light)',
  'Gioca dal cloud (Game Pass)': 'Play from the cloud (Game Pass)',
  "Esci dall'account": 'Sign out',
  'Chiudi app': 'Quit app',
  'Nessuna console trovata. Accendila e attiva le funzioni remote.': 'No console found. Turn it on and enable remote features.',
  '  - fuori rete di casa': '  - outside your home network',
  'Errore: ': 'Error: ',
  'Carico i giochi...': 'Loading games...',
  'Indietro': 'Back',
  'Nessun gioco disponibile. ': 'No games available. ',
  'Avvio ': 'Starting ',
  'In coda...': 'Waiting in queue...',
  'Avvio collegamento alla console...': 'Connecting to your console...',
  'Avvio del gioco...': 'Starting the game...',
  'Sessione fallita: ': 'Session failed: ',
  'Collego il video...': 'Connecting video...',
  'Non riesco a partire: ': 'Could not start: ',
  'Collegamento chiuso': 'Connection closed',
  'Disconnesso: ': 'Disconnected: ',
  'Disconnesso dalla console': 'Disconnected from the console',
  'Premi ancora Indietro per uscire': 'Press Back again to quit',
  'persi': 'lost'
};
function t(s) { return IT ? s : (EN[s] || s); }
var $ = function (id) { return document.getElementById(id); };
var player = null, session = null, keepalive = null, statsTimer = null, backArmed = 0, hudOn = false;

function show(id) {
  ['login', 'menu', 'stream', 'busy'].forEach(function (s) { $(s).classList.toggle('hidden', s !== id); });
}
function busy(text) { $('busyText').textContent = text; show('busy'); }
function toast(t) {
  var el = $('toast'); el.innerHTML = '<span></span>'; el.firstChild.textContent = t; el.classList.remove('hidden');
  clearTimeout(toast.t); toast.t = setTimeout(function () { el.classList.add('hidden'); }, 2500);
}
function esc(s) { return String(s).replace(/[&<>"]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]; }); }
function sleep(ms) { return new Promise(function (r) { setTimeout(r, ms); }); }

function api(path, opt) {
  return fetch(path, opt).then(function (r) {
    if (r.status === 204) return null;
    return r.text().then(function (t) {
      var j = null; try { j = JSON.parse(t); } catch (e) { }
      if (!r.ok) { var err = new Error((j && (j.error || j.message)) || ('HTTP ' + r.status)); err.status = r.status; err.body = t; throw err; }
      return j;
    });
  });
}

// ---------------------------------------------------------------- sign-in
function login() {
  show('login');
  $('login').innerHTML = '<h1>' + t('Collega il tuo account Xbox') + '</h1><div class="big">' + t('Caricamento...') + '</div>';
  api('/api/login/start', { method: 'POST' }).then(function (d) {
    $('login').innerHTML = '<h1>' + t('Collega il tuo account Xbox') + '</h1><div class="big">' + t('Dal telefono o dal PC apri') + '<br><b>' + esc(d.verification_uri) +
      '</b><br>' + t('e inserisci questo codice:') + '</div><div class="code">' + esc(d.user_code) + '</div><div class="dim">' + t('Attendo la conferma...') + '</div>';
    var poll = function () {
      api('/api/login/poll', { method: 'POST' }).then(function (r) {
        if (r.ok) menu(); else setTimeout(poll, Math.max(2, d.interval) * 1000);
      }).catch(function (e) {
        $('login').innerHTML = '<h1>' + t('Accesso non riuscito') + '</h1><div class="big">' + esc(e.message) + '</div><button id="retry">' + t('Riprova') + '</button>';
        $('retry').onclick = login; $('retry').focus();
      });
    };
    setTimeout(poll, d.interval * 1000);
  }).catch(function (e) {
    $('login').innerHTML = '<h1>' + t('Errore di rete') + '</h1><div class="big">' + esc(e.message) + '</div><button id="retry">' + t('Riprova') + '</button>';
    $('retry').onclick = login; $('retry').focus();
  });
}

// ---------------------------------------------------------------- menu
function menu(note) {
  show('menu');
  var m = $('menu');
  m.innerHTML = '<h1>BedPlay</h1>' + (note ? '<div class="dim">' + esc(note) + '</div>' : '') + '<h2>' + t('La tua console') + '</h2><div id="consoles" class="dim">' + t('Cerco le console...') + '</div>' +
    '<h2>' + t('Altro') + '</h2><button id="bQual"></button><button id="bCloud">' + t('Gioca dal cloud (Game Pass)') + '</button><button id="bOut">' + t("Esci dall'account") + '</button><button id="bQuit">' + t('Chiudi app') + '</button>';
  var qualLabel = function () { $('bQual').textContent = t('Qualita video: ') + t(QUALITIES[getQuality()][2]) + t('  (premi per cambiare)'); };
  qualLabel();
  $('bQual').onclick = function () { var k = Object.keys(QUALITIES), i = k.indexOf(getQuality()); setQuality(k[(i + 1) % k.length]); qualLabel(); };
  $('bCloud').onclick = cloudList;
  $('bOut').onclick = function () { api('/api/logout', { method: 'POST' }).then(login); };
  $('bQuit').onclick = function () { if (window.Android) Android.quit(); };
  api('/api/mode?m=home').then(function () { return api('/v6/servers/home'); }).then(function (r) {
    var list = (r && r.results) || [], box = $('consoles');
    if (!list.length) { box.textContent = t('Nessuna console trovata. Accendila e attiva le funzioni remote.'); $('bCloud').focus(); return; }
    box.innerHTML = '';
    list.forEach(function (c, i) {
      var b = document.createElement('button');
      b.textContent = c.deviceName + '  (' + c.consoleType.replace('Xbox', 'Xbox ') + ')  -  ' + c.powerState + (c.outOfHomeWarning ? t('  - fuori rete di casa') : '');
      b.onclick = function () { start('home', c.serverId, c.deviceName); };
      box.appendChild(b);
      if (i === 0) b.focus();
    });
  }).catch(function (e) {
    if (e.status === 401 || /token/i.test(e.message)) { login(); return; }
    $('consoles').textContent = t('Errore: ') + e.message; $('bCloud').focus();
  });
}

function cloudList() {
  busy(t('Carico i giochi...'));
  api('/api/mode?m=cloud').then(function () { return api('/v2/titles'); }).then(function (r) {
    var items = (r && r.results) || [];
    show('menu'); var m = $('menu');
    m.innerHTML = '<h1>Cloud</h1><div id="titles"></div><button id="bBack">' + t('Indietro') + '</button>';
    $('bBack').onclick = function () { menu(); };
    var box = $('titles');
    items.slice(0, 40).forEach(function (t, i) {
      var name = (t.details && t.details.name) || t.name || t.titleId;
      var b = document.createElement('button'); b.textContent = name;
      b.onclick = function () { start('cloud', t.titleId, name); };
      box.appendChild(b); if (i === 0) b.focus();
    });
    if (!items.length) { box.textContent = t('Nessun gioco disponibile. ') + JSON.stringify(r).slice(0, 200); $('bBack').focus(); }
  }).catch(function (e) { menu('Cloud: ' + e.message); });
}

// ---------------------------------------------------------------- stream
// requested quality: the console encodes at the resolution of the "screen" we declare
var QUALITIES = { '1080': [1920, 1080, '1080p'], '720': [1280, 720, '720p'], '540': [960, 540, '540p (leggera)'] };
function getQuality() { var q = null; try { q = localStorage.getItem('q'); } catch (e) { } return QUALITIES[q] ? q : '720'; }
function setQuality(q) { try { localStorage.setItem('q', q); } catch (e) { } }
function deviceInfo() {
  var d = QUALITIES[getQuality()];
  window.__dims = [d[0], d[1]];   // read by the player when it declares the screen size to the console
  window.__customRes = false;
  return JSON.stringify({
    appInfo: { env: { clientAppId: 'Microsoft.GamingApp', clientAppType: 'native', clientAppVersion: '2203.1001.4.0', clientSdkVersion: '8.5.2', httpEnvironment: 'prod', sdkInstallId: '' } },
    dev: { hw: { make: 'Microsoft', model: 'Surface Pro', sdktype: 'native' }, os: { name: 'Windows 11', ver: '22631.2715', platform: 'desktop' },
      displayInfo: { dimensions: { widthInPixels: d[0], heightInPixels: d[1] }, pixelDensity: { dpiX: 1, dpiY: 1 } } }
  });
}

function post(path, body, headers) {
  var h = { 'Content-Type': 'application/json' }; for (var k in (headers || {})) h[k] = headers[k];
  return api(path, { method: 'POST', headers: h, body: body === undefined ? undefined : JSON.stringify(body) });
}

function waitState(type, id) {
  return new Promise(function (resolve, reject) {
    var tick = function () {
      api('/v5/sessions/' + type + '/' + id + '/state').then(function (s) {
        var st = s && s.state;
        if (st === 'Provisioned') return resolve(s);
        if (st === 'Failed') return reject(new Error(t('Sessione fallita: ') + JSON.stringify(s.errorDetails || s)));
        if (st === 'ReadyToConnect') {
          api('/api/lpt').then(function (l) { return post('/v5/sessions/' + type + '/' + id + '/connect', { userToken: l.lpt }); })
            .then(function () { setTimeout(tick, 1000); }).catch(reject);
          return;
        }
        busy(st === 'WaitingForResources' ? t('In coda...') : (type === 'home' ? t('Avvio collegamento alla console...') : t('Avvio del gioco...')));
        setTimeout(tick, 1000);
      }).catch(function (e) { if (e.status === 404) reject(e); else setTimeout(tick, 1500); });
    };
    tick();
  });
}

function getExchange(path) {
  return new Promise(function (resolve, reject) {
    var tick = function () {
      api(path).then(function (r) { if (r === null) setTimeout(tick, 750); else resolve(r); }).catch(reject);
    };
    tick();
  });
}

function sendSdp(type, id, sdp, chat) {
  var cfg = chat ? { isMediaStreamsChatRenegotiation: true } : {
    chatConfiguration: { bytesPerSample: 2, expectedClipDurationMs: 20, format: { codec: 'opus', container: 'webm' }, numChannels: 1, sampleFrequencyHz: 24000 },
    chat: { minVersion: 1, maxVersion: 1 }, control: { minVersion: 1, maxVersion: 3 }, input: { minVersion: 1, maxVersion: 8 }, message: { minVersion: 1, maxVersion: 1 }
  };
  return post('/v5/sessions/' + type + '/' + id + '/sdp', { messageType: 'offer', sdp: sdp, configuration: cfg })
    .then(function () { return getExchange('/v5/sessions/' + type + '/' + id + '/sdp'); })
    .then(function (r) { return JSON.parse(r.exchangeResponse); });
}

// Teredo address (2001:0:server:flags:port:client) -> client IPv4 and port
function teredo(addr) {
  var parts = addr.split('::'), head = parts[0] ? parts[0].split(':') : [], tail = parts.length > 1 && parts[1] ? parts[1].split(':') : [];
  var g = head.slice(); if (parts.length > 1) { while (g.length + tail.length < 8) g.push('0'); } g = g.concat(tail);
  if (g.length !== 8) return null;
  var n = g.map(function (x) { return parseInt(x || '0', 16); });
  var c = [(n[6] >> 8) ^ 255, (n[6] & 255) ^ 255, (n[7] >> 8) ^ 255, (n[7] & 255) ^ 255];
  return { ip: c.join('.'), port: n[5] ^ 0xffff };
}

function sendIce(type, id, candidates) {
  return post('/v5/sessions/' + type + '/' + id + '/ice', { messageType: 'iceCandidate', candidate: candidates })
    .then(function () { return getExchange('/v5/sessions/' + type + '/' + id + '/ice'); })
    .then(function (r) {
      var list = JSON.parse(r.exchangeResponse), out = [];
      list.forEach(function (c) {
        var f = String(c.candidate || '').split(' ');
        if (f.length > 4 && f[4].substr(0, 4) === '2001') {
          var t = teredo(f[4]);
          if (t) {
            out.push({ candidate: 'a=candidate:10 1 UDP 1 ' + t.ip + ' 9002 typ host ', messageType: 'iceCandidate', sdpMLineIndex: '0', sdpMid: '0' });
            out.push({ candidate: 'a=candidate:11 1 UDP 1 ' + t.ip + ' ' + t.port + ' typ host ', messageType: 'iceCandidate', sdpMLineIndex: '0', sdpMid: '0' });
          }
        }
        out.push(c);
      });
      return out;
    });
}

function start(type, target, name) {
  busy(t('Avvio ') + name + '...');
  api('/api/mode?m=' + type).then(function () {
    return post('/v5/sessions/' + type + '/play', {
      titleId: type === 'cloud' ? target : '', systemUpdateGroup: '', clientSessionId: '',
      settings: { nanoVersion: 'V3;WebrtcTransport.dll', enableTextToSpeech: false, highContrast: 0, locale: 'it-IT', useIceConnection: false, timezoneOffsetMinutes: 60, sdkType: 'web', osName: 'windows' },
      serverId: type === 'home' ? target : '', fallbackRegionNames: []
    }, { 'X-MS-Device-Info': deviceInfo() });
  }).then(function (r) {
    var id = r.sessionId || String(r.sessionPath || '').split('/').filter(Boolean).pop();
    session = { type: type, id: id };
    return waitState(type, id);
  }).then(function () {
    busy(t('Collego il video...'));
    return connectPlayer();
  }).catch(function (e) {
    session && endSession(true);
    menu(t('Non riesco a partire: ') + e.message);
  });
}

function connectPlayer() {
  show('stream');
  $('videoHolder').innerHTML = '';
  player = new xCloudPlayer.default('videoHolder', { ui_systemui: [], ui_touchenabled: false, input_legacykeyboard: false, input_touch: false, input_mousekeyboard: false });
  player.bind();
  try { player.setVideoBitrate({ '1080': 15000, '720': 8000, '540': 5000 }[getQuality()]); } catch (e) { }
  var type = session.type, id = session.id;
  player.setSdpHandler(function (c, offer) {
    sendSdp(type, id, offer.sdp, true).then(function (r) { player.setRemoteOffer(r.sdp); }).catch(function (e) { console.log('chat sdp', e); });
  });
  player.getEventBus().on('connectionstate', function (ev) {
    console.log('connectionstate', ev.state);
    if (ev.state === 'connected') onConnected();
    else if (ev.state === 'closed') { if (session) { endSession(false); menu(t('Collegamento chiuso')); } }
  });
  player.getEventBus().on('message', function (ev) {
    if (ev.target === '/streaming/sessionLifetimeManagement/serverInitiatedDisconnect') {
      try { toast(t('Disconnesso: ') + JSON.parse(ev.content).reason); } catch (e) { }
      endSession(false); menu(t('Disconnesso dalla console'));
    }
  });
  return player.createOffer().then(function (offer) {
    return sendSdp(type, id, offer.sdp, false);
  }).then(function (r) {
    player.setRemoteOffer(r.sdp);
    var cand = player.getIceCandidates().map(function (c) { return { candidate: c.candidate, sdpMLineIndex: c.sdpMLineIndex, sdpMid: c.sdpMid }; });
    return sendIce(type, id, cand);
  }).then(function (ice) {
    player.setIceCandidates(ice);
  });
}

function onConnected() {
  if (window.Android) Android.streaming(true);
  show('stream');
  try {
    player._webrtcClient.getReceivers().forEach(function (r) {
      if (!r.track) return;
      try { r.jitterBufferTarget = 0; } catch (e) { }
      try { r.playoutDelayHint = 0; } catch (e) { }
    });
  } catch (e) { }
  clearInterval(keepalive);
  keepalive = setInterval(function () { if (session) post('/v5/sessions/' + session.type + '/' + session.id + '/keepalive').catch(function () { }); }, 30000);
  clearInterval(statsTimer);
  var last = null;
  statsTimer = setInterval(function () {
    if (!hudOn || !player || !player._webrtcClient) return;
    player._webrtcClient.getStats().then(function (st) {
      var txt = '';
      st.forEach(function (r) {
        if (r.type === 'inbound-rtp' && r.kind === 'video') {
          txt += 'video ' + (r.frameWidth || '?') + 'x' + (r.frameHeight || '?') + '  ' + (r.framesPerSecond || 0) + ' fps  ' + t('persi') + ' ' + (r.packetsLost || 0) +
            '  jitter ' + ((r.jitter || 0) * 1000).toFixed(1) + ' ms\n';
          if (r.jitterBufferDelay !== undefined && r.jitterBufferEmittedCount) txt += 'buffer ' + (r.jitterBufferDelay / r.jitterBufferEmittedCount * 1000).toFixed(0) + ' ms';
          if (r.totalDecodeTime !== undefined && r.framesDecoded) txt += '  decode ' + (r.totalDecodeTime / r.framesDecoded * 1000).toFixed(1) + ' ms\n';
          txt += (r.decoderImplementation ? 'decoder ' + r.decoderImplementation : '') + '\n';
        }
        if (r.type === 'candidate-pair' && r.state === 'succeeded' && r.nominated) txt += 'rtt ' + (r.currentRoundTripTime * 1000).toFixed(0) + ' ms\n';
      });
      $('hud').textContent = txt;
    });
  }, 1000);
}

function endSession(silent) {
  var s = session; session = null;
  clearInterval(keepalive); clearInterval(statsTimer);
  if (window.Android) Android.streaming(false);
  if (player) { try { player.close(); } catch (e) { } player = null; }
  if (s) api('/v5/sessions/' + s.type + '/' + s.id, { method: 'DELETE' }).catch(function () { });
}

// Back: two quick presses end the session
window.__back = function () {
  if (!session) return;
  var now = Date.now();
  if (now - backArmed < 2500) { endSession(false); menu(); } else { backArmed = now; toast(t('Premi ancora Indietro per uscire')); }
};

// LB + RB + Y = show/hide the stats overlay
var hudLatch = false;
window.__padHook = function (b) {
  var combo = b[4] > 0.5 && b[5] > 0.5 && b[3] > 0.5;
  if (combo && !hudLatch) { hudOn = !hudOn; $('hud').classList.toggle('hidden', !hudOn); }
  hudLatch = combo;
};

// ---------------------------------------------------------------- start
api('/api/status').then(function (s) { if (s.loggedIn) menu(); else login(); }).catch(function (e) { busy(t('Errore: ') + e.message); });
