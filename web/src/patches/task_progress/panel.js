/**
 * patches/task_progress/panel.js - MVP floating task monitor (async gateway exec).
 * Uses Android.gateway(bridgeToken, jsonBody) proxy. Safe in trusted top-level UI.
 */
(function(){
  if (window.__agTaskProgress && window.__agTaskProgress.__installed) return;
  var state = { tasks: {}, pollers: {}, open: false };
  var TOKEN = function(){ return window.__AG_NATIVE_BRIDGE_TOKEN__ || ''; };
  function gw(packet){
    try {
      if (!window.Android || typeof window.Android.gateway !== 'function') return { ok:false, error:'bridge unavailable' };
      var raw = window.Android.gateway(TOKEN(), JSON.stringify(packet||{}));
      return typeof raw === 'string' ? JSON.parse(raw) : (raw || {ok:false});
    } catch(e){ return { ok:false, error:String(e && e.message || e) }; }
  }
  function esc(s){ return String(s==null?'':s).replace(/[&<>"]/g,function(c){return ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'})[c];}); }
  function ensureUI(){
    if (document.getElementById('ag-task-fab')) return;
    var style = document.createElement('style');
    style.textContent = '#ag-task-fab{position:fixed;right:14px;bottom:14px;z-index:2147483000;border:0;border-radius:999px;padding:10px 14px;background:#2563eb;color:#fff;font:600 13px/1.2 system-ui;box-shadow:0 6px 20px rgba(0,0,0,.25)}'
      +'#ag-task-panel{position:fixed;right:14px;bottom:64px;z-index:2147483000;width:min(92vw,420px);max-height:55vh;overflow:auto;background:rgba(15,23,42,.96);color:#e5e7eb;border:1px solid rgba(148,163,184,.25);border-radius:14px;box-shadow:0 10px 30px rgba(0,0,0,.35);font:13px/1.35 system-ui;display:none}'
      +'#ag-task-panel.open{display:block}'
      +'#ag-task-panel .hd{display:flex;align-items:center;justify-content:space-between;padding:10px 12px;border-bottom:1px solid rgba(148,163,184,.18);font-weight:600}'
      +'#ag-task-panel .bd{padding:10px 12px}'
      +'#ag-task-panel input,#ag-task-panel button{font:inherit}'
      +'#ag-task-panel .row{display:flex;gap:8px;margin-bottom:10px}'
      +'#ag-task-panel .cmd{flex:1;min-width:0;border:1px solid rgba(148,163,184,.25);border-radius:10px;padding:8px 10px;background:#0b1220;color:#e5e7eb}'
      +'#ag-task-panel .btn{border:0;border-radius:10px;padding:8px 10px;background:#334155;color:#fff}'
      +'#ag-task-panel .btn.primary{background:#2563eb}'
      +'#ag-task-panel .btn.danger{background:#b91c1c}'
      +'#ag-task-panel .task{border:1px solid rgba(148,163,184,.18);border-radius:10px;padding:8px 10px;margin-top:8px;background:rgba(255,255,255,.02)}'
      +'#ag-task-panel .meta{font-size:12px;color:#94a3b8;word-break:break-all}'
      +'#ag-task-panel pre{margin:6px 0 0;white-space:pre-wrap;word-break:break-word;max-height:120px;overflow:auto;font:12px/1.3 ui-monospace,monospace;color:#cbd5e1}'
      +'#ag-task-panel .st{font-weight:600}'
      +'#ag-task-panel .ok{color:#4ade80}.warn{color:#fbbf24}.bad{color:#f87171}';
    document.head.appendChild(style);
    var fab = document.createElement('button');
    fab.id='ag-task-fab'; fab.type='button'; fab.textContent='Tugas';
    fab.onclick=function(){ state.open=!state.open; panel.classList.toggle('open', state.open); };
    var panel = document.createElement('div');
    panel.id='ag-task-panel';
    panel.innerHTML='<div class="hd"><span>Monitor Tugas</span><button class="btn" id="ag-task-close" type="button">Tutup</button></div>'
      +'<div class="bd"><div class="row"><input class="cmd" id="ag-task-cmd" placeholder="Contoh: echo halo" />'
      +'<button class="btn primary" id="ag-task-run" type="button">Jalankan</button></div>'
      +'<div class="meta">Menjalankan perintah lewat layanan aplikasi (bisa dipantau & dibatalkan).</div>'
      +'<div id="ag-task-list"></div></div>';
    document.body.appendChild(fab);
    document.body.appendChild(panel);
    panel.querySelector('#ag-task-close').onclick=function(){ state.open=false; panel.classList.remove('open'); };
    panel.querySelector('#ag-task-run').onclick=function(){
      var cmd = (panel.querySelector('#ag-task-cmd').value||'').trim();
      if (!cmd) return;
      var res = gw({command:'exec_start', payload:{cmd:cmd, source:'ui_task_panel'}});
      if (res && res.ok && res.id){ track(res.id, cmd); panel.querySelector('#ag-task-cmd').value=''; }
      else { alert('Gagal mulai: ' + (res && res.error || 'unknown')); }
    };
  }
  function statusClass(st){ st=String(st||''); if(st==='SUCCESS') return 'ok'; if(st==='RUNNING'||st==='CANCELLING') return 'warn'; return 'bad'; }
  function render(){
    var list = document.getElementById('ag-task-list');
    if (!list) return;
    var ids = Object.keys(state.tasks).sort(function(a,b){ return (state.tasks[b].started||0)-(state.tasks[a].started||0); });
    if (!ids.length){ list.innerHTML='<div class="meta" style="margin-top:10px">Belum ada tugas.</div>'; return; }
    list.innerHTML = ids.map(function(id){
      var t = state.tasks[id]||{};
      var out = String(t.stdout||'');
      if (out.length>600) out = out.slice(-600);
      var canCancel = !!t.running;
      return '<div class="task"><div><span class="st '+statusClass(t.status)+'">'+esc(t.status||'?')+'</span> <span class="meta">'+esc(id)+'</span></div>'
        +'<div class="meta">'+esc(t.cmd||'')+'</div>'
        +(out?'<pre>'+esc(out)+'</pre>':'')
        +'<div style="margin-top:8px;display:flex;gap:8px"><button class="btn" data-act="refresh" data-id="'+esc(id)+'" type="button">Muat ulang</button>'
        +(canCancel?'<button class="btn danger" data-act="cancel" data-id="'+esc(id)+'" type="button">Batalkan</button>':'')+'</div></div>';
    }).join('');
    list.querySelectorAll('button[data-id]').forEach(function(btn){
      btn.onclick=function(){
        var id=btn.getAttribute('data-id'), act=btn.getAttribute('data-act');
        if(act==='cancel') gw({command:'exec_cancel', payload:{id:id}});
        poll(id);
      };
    });
  }
  function poll(id){
    var res = gw({command:'exec_status', payload:{id:id, stdout_offset:0, stderr_offset:0, include_output:true}});
    if (res && (res.ok || res.id)){
      var t = state.tasks[id] = state.tasks[id] || {id:id, started:Date.now()};
      t.status = res.status; t.running = !!res.running; t.stdout = res.stdout || ''; t.stderr = res.stderr || ''; t.exit_code = res.exit_code;
      if (!t.cmd && res.cmd) t.cmd = res.cmd;
      if (!res.running && state.pollers[id]){ clearInterval(state.pollers[id]); delete state.pollers[id]; }
    }
    render();
  }
  function track(id, cmd){
    state.tasks[id] = {id:id, cmd:cmd, status:'RUNNING', running:true, started:Date.now(), stdout:''};
    render(); poll(id);
    if (state.pollers[id]) clearInterval(state.pollers[id]);
    state.pollers[id] = setInterval(function(){ poll(id); }, 1200);
  }
  function init(){ try{ ensureUI(); render(); }catch(e){} }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init); else init();
  window.__agTaskProgress = { __installed:true, track:track, poll:poll, gateway:gw, state:state };
})();
