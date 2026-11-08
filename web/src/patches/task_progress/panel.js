/**
 * patches/task_progress/panel.js - Task monitor overlay (async gateway exec).
 * Uses Android.gateway(bridgeToken, jsonBody) proxy. Safe in trusted top-level UI.
 */
(function(){
  if (window.__agTaskProgress && window.__agTaskProgress.__installed) return;
  var state = { tasks: {}, pollers: {}, open: false, stick: {} };
  var TOKEN = function(){ return window.__AG_NATIVE_BRIDGE_TOKEN__ || ''; };
  function gw(packet){
    try {
      if (!window.Android || typeof window.Android.gateway !== 'function') return { ok:false, error:'Jembatan aplikasi belum siap' };
      var raw = window.Android.gateway(TOKEN(), JSON.stringify(packet||{}));
      return typeof raw === 'string' ? JSON.parse(raw) : (raw || {ok:false});
    } catch(e){ return { ok:false, error:String(e && e.message || e) }; }
  }
  function esc(s){ return String(s==null?'':s).replace(/[&<>"']/g,function(c){return ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'})[c];}); }
  function fmtDur(ms){ ms=Math.max(0,Number(ms)||0); if(ms<1000) return ms+' md'; var s=Math.round(ms/1000); if(s<60) return s+' dtk'; var m=Math.floor(s/60); return m+' mnt '+(s%60)+' dtk'; }
  function prune(){
    var ids = Object.keys(state.tasks).sort(function(a,b){ return (state.tasks[b].started||0)-(state.tasks[a].started||0); });
    ids.slice(15).forEach(function(id){ if(state.pollers[id]) clearInterval(state.pollers[id]); delete state.pollers[id]; delete state.tasks[id]; });
  }
  function ensureUI(){
    if (document.getElementById('ag-task-fab')) return;
    var style = document.createElement('style');
    style.textContent = '#ag-task-fab{position:fixed;right:14px;bottom:14px;z-index:2147483000;border:0;border-radius:999px;padding:10px 14px;background:#2563eb;color:#fff;font:600 13px/1.2 system-ui;box-shadow:0 6px 20px rgba(0,0,0,.25)}'
      +'#ag-task-fab .dot{display:inline-block;width:8px;height:8px;border-radius:99px;background:#22c55e;margin-left:6px;vertical-align:middle}'
      +'#ag-task-panel{position:fixed;right:14px;bottom:64px;z-index:2147483000;width:min(94vw,440px);max-height:62vh;overflow:auto;background:rgba(15,23,42,.97);color:#e5e7eb;border:1px solid rgba(148,163,184,.25);border-radius:14px;box-shadow:0 10px 30px rgba(0,0,0,.35);font:13px/1.35 system-ui;display:none}'
      +'#ag-task-panel.open{display:block}'
      +'#ag-task-panel .hd{display:flex;align-items:center;justify-content:space-between;padding:10px 12px;border-bottom:1px solid rgba(148,163,184,.18);font-weight:600;position:sticky;top:0;background:rgba(15,23,42,.98)}'
      +'#ag-task-panel .bd{padding:10px 12px}'
      +'#ag-task-panel input,#ag-task-panel button{font:inherit}'
      +'#ag-task-panel .row{display:flex;gap:8px;margin-bottom:10px}'
      +'#ag-task-panel .cmd{flex:1;min-width:0;border:1px solid rgba(148,163,184,.25);border-radius:10px;padding:8px 10px;background:#0b1220;color:#e5e7eb}'
      +'#ag-task-panel .btn{border:0;border-radius:10px;padding:8px 10px;background:#334155;color:#fff;cursor:pointer}'
      +'#ag-task-panel .btn.primary{background:#2563eb}.btn.danger{background:#b91c1c}'
      +'#ag-task-panel .task{border:1px solid rgba(148,163,184,.18);border-radius:10px;padding:8px 10px;margin-top:8px;background:rgba(255,255,255,.02)}'
      +'#ag-task-panel .meta{font-size:12px;color:#94a3b8;word-break:break-word}'
      +'#ag-task-panel pre{margin:6px 0 0;white-space:pre-wrap;word-break:break-word;max-height:150px;overflow:auto;font:12px/1.3 ui-monospace,monospace;color:#cbd5e1;background:rgba(0,0,0,.18);padding:8px;border-radius:8px}'
      +'#ag-task-panel pre.err{color:#fecaca}'
      +'#ag-task-panel .st{font-weight:700}.ok{color:#4ade80}.warn{color:#fbbf24}.bad{color:#f87171}'
      +'#ag-task-panel .actions{margin-top:8px;display:flex;gap:8px;flex-wrap:wrap}';
    document.head.appendChild(style);
    var fab = document.createElement('button');
    fab.id='ag-task-fab'; fab.type='button'; fab.innerHTML='Tugas <span class="dot" id="ag-task-dot" style="display:none"></span>';
    var panel = document.createElement('div');
    panel.id='ag-task-panel';
    panel.innerHTML='<div class="hd"><span>Monitor Tugas</span><button class="btn" id="ag-task-close" type="button">Tutup</button></div>'
      +'<div class="bd"><div class="row"><input class="cmd" id="ag-task-cmd" placeholder="Contoh: echo halo" />'
      +'<button class="btn primary" id="ag-task-run" type="button">Jalankan</button></div>'
      +'<div class="meta">Perintah berjalan lewat layanan aplikasi: progres diperbarui otomatis, dan bisa dibatalkan.</div>'
      +'<div id="ag-task-list"></div></div>';
    document.body.appendChild(fab);
    document.body.appendChild(panel);
    fab.onclick=function(){ state.open=!state.open; panel.classList.toggle('open', state.open); };
    panel.querySelector('#ag-task-close').onclick=function(){ state.open=false; panel.classList.remove('open'); };
    panel.querySelector('#ag-task-run').onclick=function(){
      var inp=panel.querySelector('#ag-task-cmd'); var cmd=(inp.value||'').trim();
      if (!cmd) return;
      var res = gw({command:'exec_start', payload:{cmd:cmd, source:'ui_task_panel'}});
      if (res && res.ok && res.id){ track(res.id, cmd); inp.value=''; }
      else { alert('Gagal mulai tugas: ' + (res && res.error || 'layanan belum siap / kunci ditolak')); }
    };
  }
  function statusClass(st){ st=String(st||''); if(st==='SUCCESS') return 'ok'; if(st==='RUNNING'||st==='CANCELLING'||st==='STARTING') return 'warn'; return 'bad'; }
  function statusLabel(st){ st=String(st||''); if(st==='RUNNING') return 'Sedang berjalan'; if(st==='SUCCESS') return 'Selesai'; if(st==='FAILED') return 'Gagal'; if(st==='CANCELLED') return 'Dibatalkan'; if(st==='TIMEOUT') return 'Terlalu lama'; if(st==='CANCELLING') return 'Membatalkan'; return st||'?'; }
  function render(){
    var list = document.getElementById('ag-task-list'); var dot = document.getElementById('ag-task-dot');
    if (!list) return;
    var ids = Object.keys(state.tasks).sort(function(a,b){ return (state.tasks[b].started||0)-(state.tasks[a].started||0); });
    var hasRunning = ids.some(function(id){ return state.tasks[id] && state.tasks[id].running; });
    if (dot) dot.style.display = hasRunning ? 'inline-block' : 'none';
    if (!ids.length){ list.innerHTML='<div class="meta" style="margin-top:10px">Belum ada tugas. Jalankan perintah di atas untuk melihat progresnya.</div>'; return; }
    list.innerHTML = ids.map(function(id){
      var t = state.tasks[id]||{};
      var out = String(t.stdout||''); if (out.length>1400) out = '…\n'+out.slice(-1400);
      var err = String(t.stderr||''); if (err.length>800) err = '…\n'+err.slice(-800);
      var canCancel = !!t.running;
      return '<div class="task"><div><span class="st '+statusClass(t.status)+'">'+esc(statusLabel(t.status))+'</span> <span class="meta">· '+esc(fmtDur(t.duration_ms||0))+'</span></div>'
        +'<div class="meta"><b>Perintah:</b> '+esc(t.cmd||'')+'</div>'
        +(t.last_error?'<div class="meta bad">Masalah: '+esc(t.last_error)+'</div>':'')
        +(out?'<pre>'+esc(out)+'</pre>':'')
        +(err?'<pre class="err">'+esc(err)+'</pre>':'')
        +'<div class="actions"><button class="btn" data-act="refresh" data-id="'+esc(id)+'" type="button">Perbarui</button>'
        +(canCancel?'<button class="btn danger" data-act="cancel" data-id="'+esc(id)+'" type="button">Batalkan</button>':'')
        +'<button class="btn" data-act="remove" data-id="'+esc(id)+'" type="button">Sembunyikan</button></div></div>';
    }).join('');
    list.querySelectorAll('button[data-id]').forEach(function(btn){
      btn.onclick=function(){
        var id=btn.getAttribute('data-id'), act=btn.getAttribute('data-act');
        if(act==='cancel'){ gw({command:'exec_cancel', payload:{id:id}}); poll(id,true); }
        else if(act==='remove'){ if(state.pollers[id]) clearInterval(state.pollers[id]); delete state.pollers[id]; delete state.tasks[id]; render(); }
        else poll(id,true);
      };
    });
  }
  function poll(id, forceRender){
    var t = state.tasks[id] = state.tasks[id] || {id:id, started:Date.now(), stdout:'', stderr:'', outOff:0, errOff:0};
    var res = gw({command:'exec_status', payload:{id:id, stdout_offset:t.outOff||0, stderr_offset:t.errOff||0, include_output:true}});
    if (res && (res.ok || res.id || res.status)){
      if (typeof res.stdout === 'string' && res.stdout.length) { t.stdout = (t.stdout||'') + res.stdout; }
      else if (typeof res.stdout === 'string' && (res.stdout_offset||0)===0 && (t.outOff||0)===0) { t.stdout = res.stdout; }
      if (typeof res.stderr === 'string' && res.stderr.length) { t.stderr = (t.stderr||'') + res.stderr; }
      else if (typeof res.stderr === 'string' && (res.stderr_offset||0)===0 && (t.errOff||0)===0) { t.stderr = res.stderr; }
      if (typeof res.stdout_total === 'number') t.outOff = res.stdout_total;
      if (typeof res.stderr_total === 'number') t.errOff = res.stderr_total;
      if (res.status) t.status = res.status;
      if (typeof res.running === 'boolean') t.running = res.running;
      if (typeof res.duration_ms === 'number') t.duration_ms = res.duration_ms;
      if (typeof res.exit_code === 'number') t.exit_code = res.exit_code;
      if (!t.cmd && res.cmd) t.cmd = res.cmd;
      if (res.error) t.last_error = res.error;
      if (!res.running && state.pollers[id]){ clearInterval(state.pollers[id]); delete state.pollers[id]; }
    } else if (res && res.error) {
      t.last_error = res.error;
    }
    if (state.open || forceRender) render();
  }
  function track(id, cmd){
    state.tasks[id] = {id:id, cmd:cmd, status:'RUNNING', running:true, started:Date.now(), stdout:'', stderr:'', outOff:0, errOff:0, duration_ms:0};
    prune(); render(); poll(id,true);
    if (state.pollers[id]) clearInterval(state.pollers[id]);
    state.pollers[id] = setInterval(function(){ poll(id,false); }, 900);
  }
  function init(){ try{ ensureUI(); render(); }catch(e){} }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init); else init();
  window.__agTaskProgress = { __installed:true, track:track, poll:poll, gateway:gw, state:state };
})();

