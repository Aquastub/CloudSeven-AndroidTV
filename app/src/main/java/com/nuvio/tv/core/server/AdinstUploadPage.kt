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
  .err { color:#ff6b6b; } .ok { color:#6bff95; } a { color:#9cf; }
</style></head><body>
<h1>Install a local addon</h1>
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
    msg(j.ok ? (j.message || 'Installed.') : (j.error || 'Install failed.'), j.ok ? 'ok' : 'err');
  }).catch(function () { msg('Install failed.', 'err'); });
};
</script></body></html>
"""
}
