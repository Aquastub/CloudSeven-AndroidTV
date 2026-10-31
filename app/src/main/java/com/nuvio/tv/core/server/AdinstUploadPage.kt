package com.nuvio.tv.core.server

/** A small page, opened from a phone or computer on the same network, for installing local addons (ADINST). */
internal object AdinstUploadPage {
    val HTML = """
<!doctype html>
<html lang="en"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Install local addon</title>
<style>
  body { font-family: system-ui, sans-serif; background:#0d0d0f; color:#eee; margin:0; padding:24px; max-width:560px; margin:auto; }
  h1 { font-size:1.3rem; } .card { background:#1a1a1f; border-radius:12px; padding:16px; margin-top:16px; }
  button { background:#fff; color:#000; border:0; border-radius:8px; padding:12px 18px; font-size:1rem; margin-top:12px; }
  button.secondary { background:#333; color:#eee; margin-left:8px; } input[type=file] { width:100%; }
  .perm { display:inline-block; background:#2a2a32; border-radius:6px; padding:2px 8px; margin:2px; font-size:.85rem; }
  input[type=text], input[type=url], input[type=password], input[type=number], select { width:100%; box-sizing:border-box; padding:8px; border-radius:6px; border:1px solid #444; background:#111; color:#eee; margin:4px 0 10px; }
  pre { white-space:pre-wrap; word-break:break-word; font-size:.75rem; background:#111; padding:10px; border-radius:8px; max-height:320px; overflow:auto; }
  label { font-size:.85rem; color:#bbb; } .hint { font-size:.75rem; color:#888; } .danger { background:#5a1f1f; color:#fff; }
  .err { color:#ff6b6b; } .ok { color:#6bff95; } a { color:#9cf; }
</style></head><body>
<h1>Local addons</h1>
<p>Choose an <b>.adinst</b> file. Local addons run on this TV and can only do what they ask permission for.</p>
<div class="card" id="pick">
  <input type="file" id="file">
  <button id="upload">Upload</button>
  <div id="msg"></div>
</div>
<div class="card" id="confirm" style="display:none">
  <div>Install <b id="name"></b>?</div>
  <div id="perms" style="margin-top:8px"></div>
  <button id="yes">Install</button><button class="secondary" id="no">Cancel</button>
</div>
<h1 style="margin-top:28px">Installed</h1>
<div id="installed"><div class="card">Loading...</div></div>
<div class="card">
  <b>Logs</b>
  <select id="logsource"><option value="">Everything</option></select>
  <button class="secondary" id="logrefresh">Refresh</button><button class="secondary" id="logclear">Clear</button>
  <pre id="logs">No log lines yet.</pre>
</div>
<p><a href="/">Back to addon manager</a></p>
<script>
var token = null;
function msg(text, cls) { var m = document.getElementById('msg'); m.className = cls || ''; m.textContent = text; }
document.getElementById('upload').onclick = function () {
  var f = document.getElementById('file').files[0];
  if (!f) { msg('Pick a file first.', 'err'); return; }
  var data = new FormData(); data.append('file', f, f.name);
  msg('Uploading...');
  fetch('/api/adinst', { method: 'POST', body: data }).then(function (r) { return r.json(); }).then(function (j) {
    if (!j.ok) { msg(j.error || 'Not a valid ADINST file.', 'err'); return; }
    token = j.token; msg('');
    document.getElementById('name').textContent = j.name;
    var p = document.getElementById('perms'); p.textContent = '';
    var list = j.permissions || [];
    if (!list.length) { p.textContent = 'No special permissions.'; }
    list.forEach(function (x) { var s = document.createElement('span'); s.className = 'perm'; s.textContent = x; p.appendChild(s); });
    document.getElementById('pick').style.display = 'none';
    document.getElementById('confirm').style.display = 'block';
  }).catch(function () { msg('Upload failed.', 'err'); });
};
document.getElementById('no').onclick = function () { location.reload(); };
document.getElementById('yes').onclick = function () {
  var body = new URLSearchParams(); body.append('token', token);
  fetch('/api/adinst/confirm', { method: 'POST', body: body }).then(function (r) { return r.json(); }).then(function (j) {
    document.getElementById('confirm').style.display = 'none';
    document.getElementById('pick').style.display = 'block';
    msg(j.ok ? (j.message || 'Installed.') : (j.error || 'Install failed.'), j.ok ? 'ok' : 'err'); loadAddons();
  }).catch(function () { msg('Install failed.', 'err'); });
};

function api(path, opts) { return fetch(path, opts).then(function (r) { return r.json(); }); }
function form(obj) { var b = new URLSearchParams(); Object.keys(obj).forEach(function (k) { b.append(k, obj[k]); }); return b; }
function el(tag, cls, text) { var e = document.createElement(tag); if (cls) e.className = cls; if (text) e.textContent = text; return e; }

function renderAddons(list) {
  var box = document.getElementById('installed'); box.textContent = '';
  var sel = document.getElementById('logsource'); var keep = sel.value; sel.textContent = '';
  ['', 'preroll', 'programs'].forEach(function (v) { var o = el('option', '', v || 'Everything'); o.value = v; sel.appendChild(o); });
  if (!list.length) { box.appendChild(el('div', 'card', 'No local addons installed.')); }
  list.forEach(function (a) {
    var o = el('option', '', a.name); o.value = a.slug; sel.appendChild(o);
    var card = el('div', 'card'); card.appendChild(el('b', '', a.name));
    var perms = el('div'); perms.style.margin = '6px 0';
    (a.permissions || []).forEach(function (p) { perms.appendChild(el('span', 'perm', p)); });
    card.appendChild(perms);
    var inputs = {};
    (a.settings || []).forEach(function (st) {
      card.appendChild(el('label', '', st.label));
      var i = document.createElement('input'); i.type = st.type === 'password' ? 'password' : st.type === 'url' ? 'url' : st.type === 'number' ? 'number' : 'text';
      i.value = st.value || ''; inputs[st.key] = i; card.appendChild(i);
      if (st.hint) card.appendChild(el('div', 'hint', st.hint));
    });
    var row = el('div');
    if ((a.settings || []).length) {
      var save = el('button', '', 'Save settings');
      save.onclick = function () {
        var values = {}; Object.keys(inputs).forEach(function (k) { values[k] = inputs[k].value; });
        api('/api/local-addons/settings', { method: 'POST', body: form({ slug: a.slug, values: JSON.stringify(values) }) })
          .then(function (j) { save.textContent = j.ok ? 'Saved' : (j.error || 'Failed'); });
      };
      row.appendChild(save);
    }
    var rm = el('button', 'secondary danger', 'Remove');
    rm.onclick = function () {
      if (!confirm('Remove ' + a.name + '?')) return;
      api('/api/local-addons/remove', { method: 'POST', body: form({ slug: a.slug }) }).then(loadAddons);
    };
    row.appendChild(rm); card.appendChild(row); box.appendChild(card);
  });
  sel.value = keep;
}
function loadAddons() { api('/api/local-addons').then(function (j) { renderAddons(j.addons || []); }); loadLogs(); }
function loadLogs() {
  var s = document.getElementById('logsource').value;
  api('/api/local-addons/logs' + (s ? '?slug=' + encodeURIComponent(s) : '')).then(function (j) {
    var lines = j.lines || []; document.getElementById('logs').textContent = lines.length ? lines.join('\n') : 'No log lines yet.';
  });
}
document.getElementById('logrefresh').onclick = loadLogs;
document.getElementById('logsource').onchange = loadLogs;
document.getElementById('logclear').onclick = function () {
  var s = document.getElementById('logsource').value;
  api('/api/local-addons/logs/clear', { method: 'POST', body: form(s ? { slug: s } : {}) }).then(loadLogs);
};
loadAddons();
</script></body></html>
"""
}
