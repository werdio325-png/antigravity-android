#!/usr/bin/env python3
"""
Antigravity Web UI Offline Cache & Persistence Patcher (Fast Boot & Clean Architecture)

1. Removes all fake auth mocks and hardcoded account credentials.
   Real auth (hasAuthToken, getAuthStatus, fetchUserInfo) always flows to core.
2. index.html uses the non-blocking fast network queue with adaptive probing.
3. main.js injects real cache persistence, rich event logging, and pre-seeding:
   - Projects: Cached in `jetski.cachedProjectsJson_v6`, pre-seeded into richProjectsProvider at startup with hasInitialized=true.
   - Discussions / Conversations: Cached in `jetski.cachedSummariesJson_v6`, synced via writes/deletes/stream, pre-seeded into Redux store at startup.
   - App State: Cached in `jetski.cachedAppStateJson_v6`, pre-seeded into c8b rootProvider at startup.
   - Available Models: Cached in `jetski.cachedAvailableModelsJson_v6`, fallback served if server returns error or offline.
   - User Status: Cached in `jetski.cachedUserStatusJson_v6`, pre-seeded into J8b at startup.
   - User Info & Home Dir: Cached in `jetski.cachedUserInfoJson_v6`, served immediately so UI doesn't block waiting for core.
   - Server Config: Returns default sidecar config if core is booting, avoiding startup stall.
   - Mendel Flags & Sidecars: Decoupled from startup blocking path so React mounts in < 1.5s!
   - NUX System: Initialized in kind:1 mode immediately so UI components render without waiting for getCascadeNuxes.
"""

import os
import sys
import shutil

TARGET_SHIM_MARKER = "/* Jetski Web/Core Decoupling, Fast Network & Cache Shim */"

PATCHED_DECOUPLING_SHIM = """  <script>
/* Jetski Web/Core Decoupling, Fast Network & Cache Shim */
(function(){
  // 0. BigInt JSON serialization polyfill
  try {
    if (typeof BigInt !== "undefined" && !BigInt.prototype.toJSON) {
      BigInt.prototype.toJSON = function() { return this.toString(); };
    }
  } catch(_) {}

  // 1. Service Worker registration
  if("serviceWorker" in navigator){
    window.addEventListener("load",()=>{
      navigator.serviceWorker.register("/sw.js",{scope:"/"}).catch(()=>{});
    });
  }

  // 2. Cache Eviction hook
  window.evictJetskiCache=async function(m="all"){
    if("caches" in window){
      const k=await caches.keys();
      for(const x of k) if("all"===m||x.includes(m)) await caches.delete(x);
    }
    if(navigator.serviceWorker?.controller){
      navigator.serviceWorker.controller.postMessage({action:"PURGE_CACHE"});
    }
    console.log("[Jetski] Cache completely unloaded.");
  };

  // 3. Fast non-blocking network queue with adaptive probing
  const origFetch=window.fetch;
  let coreReady=!1;
  const queue=[];
  let probeDelay=25;

  function probe(){
    origFetch(window.location.origin+"/?csrf_token=probe",{method:"HEAD",cache:"no-store"})
      .then(()=>{
        coreReady=!0;
        console.log("[Jetski] Core server ready! Flushing " + queue.length + " queued requests.");
        while(queue.length>0){
          const it=queue.shift();
          origFetch(it.in,it.opts).then(it.res).catch(it.rej);
        }
      })
      .catch(()=>{
        probeDelay=Math.min(250,Math.round(probeDelay*1.5));
        setTimeout(probe,probeDelay);
      });
  }
  probe();

  window.fetch=function(inp,opts){
    let u="";
    if(typeof inp==="string") u=inp;
    else if(inp&&inp.url) u=inp.url;

    const isExt=u.startsWith("http://")||u.startsWith("https://");
    const isLocal=!isExt||u.startsWith(window.location.origin);

    // External requests (OAuth, Google Auth, CDN, APIs): dispatch immediately without blocking
    if(!isLocal) return origFetch(inp,opts);

    // Local requests: dispatch if core is ready or probe
    if(coreReady||u.includes("csrf_token=probe")||u.endsWith("/sw.js")){
      return origFetch(inp,opts).catch(err=>new Promise((res,rej)=>{
        queue.push({in:inp,opts:opts,res:res,rej:rej});
        probe();
      }));
    }

    // Core booting: queue non-blocking
    return new Promise((res,rej)=>{
      console.log("[JetskiQueue] Queued: " + u);
      queue.push({in:inp,opts:opts,res:res,rej:rej});
      setTimeout(()=>{
        const idx=queue.findIndex(q=>q.res===res);
        if(idx!==-1){
          queue.splice(idx,1);
          origFetch(inp,opts).then(res).catch(rej);
        }
      },15000);
    });
  };
})();
</script>"""

def patch_index_html(index_html_path):
    print(f"[patch_cache] Inspecting {index_html_path}...")
    if not os.path.isfile(index_html_path):
        print(f"[-] [patch_cache] File not found: {index_html_path}")
        return False

    with open(index_html_path, "r", encoding="utf-8") as f:
        content = f.read()

    old_start = content.find('<script id="offline-cache-seed-and-interceptor">')
    if old_start != -1:
        old_end = content.find('</script>', old_start)
        if old_end != -1:
            content = content[:old_start] + content[old_end + len('</script>'):]
            print("[*] [patch_cache] Removed legacy offline-cache-seed-and-interceptor from index.html.")

    shim_start = content.find(TARGET_SHIM_MARKER)
    if shim_start != -1:
        script_tag_start = content.rfind("<script", 0, shim_start)
        script_tag_end = content.find("</script>", shim_start)
        if script_tag_start != -1 and script_tag_end != -1:
            content = content[:script_tag_start] + PATCHED_DECOUPLING_SHIM + content[script_tag_end + len("</script>"):]
            print("[+] [patch_cache] Replaced decoupling shim in index.html.")
    else:
        head_end = content.find("</head>")
        if head_end != -1:
            content = content[:head_end] + PATCHED_DECOUPLING_SHIM + "\n" + content[head_end:]
            print("[+] [patch_cache] Inserted decoupling shim before </head> in index.html.")
        else:
            print("[-] [patch_cache] Could not find </head> in index.html!")
            return False

    with open(index_html_path, "w", encoding="utf-8") as f:
        f.write(content)

    print(f"[+] [patch_cache] Successfully patched {index_html_path}!")
    return True

MAIN_JS_PATCHES = [
    # 1. Summary write sync
    (
        'n.jetboxWriteSummary(r(fia,m))))}finally{p()}})',
        'n.jetboxWriteSummary(r(fia,m))))}finally{try{var __c=JSON.parse(localStorage.getItem("jetski.cachedSummariesJson_v6")||"{}");__c.updates=__c.updates||{};__c.updates[m.summary.cascadeId]=m.summary;localStorage.setItem("jetski.cachedSummariesJson_v6",JSON.stringify(__c));console.log("[Discussions] Saved discussion to cache: id="+m.summary.cascadeId+", title="+(m.summary.title||""));}catch(_){}p()}})'
    ),
    # 2. Summary delete sync
    (
        'n.jetboxDeleteSummary(r(gia,m))))}finally{p()}})',
        'n.jetboxDeleteSummary(r(gia,m))))}finally{try{var __c=JSON.parse(localStorage.getItem("jetski.cachedSummariesJson_v6")||"{}");if(__c.updates){delete __c.updates[m.cascadeId];localStorage.setItem("jetski.cachedSummariesJson_v6",JSON.stringify(__c));console.log("[Discussions] Deleted discussion from cache: id="+m.cascadeId);}}catch(_){}p()}})'
    ),
    # 3. Read projects sync
    (
        '$$(a,g.projects,[...c,...g.notFoundIds]);',
        '$$(a,g.projects,[...c,...g.notFoundIds]);(()=>{try{var __cp=JSON.parse(localStorage.getItem("jetski.cachedProjectsJson_v6")||"{}");var __map=new Map((__cp.projects||[]).map(x=>[x.id,x]));(g.projects||[]).forEach(p=>{if(p&&p.id)__map.set(p.id,p);});([...c,...g.notFoundIds]||[]).forEach(delId=>{__map.delete(delId);});localStorage.setItem("jetski.cachedProjectsJson_v6",JSON.stringify({projects:Array.from(__map.values())}));console.log("[Projects] Synced projects from server ("+g.projects.length+"): "+g.projects.map(x=>x.name||x.id).join(", "));}catch(_){}})();'
    ),
    # 4. Stream summary cache sync
    (
        'b&&(c.dispatch(Vna()),b=!1);c.dispatch($m({updates:Object.entries(l.updates),deletes:l.deletes}))',
        'b&&(c.dispatch(Vna()),b=!1);c.dispatch($m({updates:Object.entries(l.updates),deletes:l.deletes}));(()=>{try{var __c=JSON.parse(localStorage.getItem("jetski.cachedSummariesJson_v6")||"{}");__c.updates=__c.updates||{};if(l.updates)for(var k in l.updates)__c.updates[k]=l.updates[k];if(l.deletes&&Array.isArray(l.deletes))l.deletes.forEach(delId=>{delete __c.updates[delId];});localStorage.setItem("jetski.cachedSummariesJson_v6",JSON.stringify(__c));console.log("[Discussions] Stream synced discussions: updates="+(l.updates?Object.keys(l.updates).length:0)+", deletes="+(l.deletes?l.deletes.length:0));}catch(_){}})();'
    ),
    # 5. Pre-seed discussions into Redux store at startup
    (
        'let k=new Or(h,"trajectory_summary_load");',
        'let k=new Or(h,"trajectory_summary_load");(()=>{try{var __cs=JSON.parse(localStorage.getItem("jetski.cachedSummariesJson_v6")||"{}");if(__cs.updates&&Object.keys(__cs.updates).length>0){c.dispatch($m({updates:Object.entries(__cs.updates),deletes:[]}));console.log("[Discussions] Pre-seeded "+Object.keys(__cs.updates).length+" discussions from cache");}}catch(_){}})();'
    ),
    # 6. Pre-seed projects into richProjectsProvider at startup and set hasInitialized=true
    (
        'this.lsClient=a;this.options=c;this.richProjectsProvider=new Oi(new Map);this.hasInitialized=!1;',
        'this.lsClient=a;this.options=c;let __initProjMap=new Map, __hasCachedProj=false;try{var __cp=JSON.parse(localStorage.getItem("jetski.cachedProjectsJson_v6")||"{}");if(__cp.projects&&Array.isArray(__cp.projects)){for(var p of __cp.projects)if(p&&p.id)__initProjMap.set(p.id,{project:p});if(__initProjMap.size>0){__hasCachedProj=true;console.log("[Projects] Pre-seeded "+__initProjMap.size+" projects from cache");}}}catch(_){}this.richProjectsProvider=new Oi(__initProjMap);this.hasInitialized=!0;'
    ),
    # 7. Pre-seed appState into c8b rootProvider at startup
    (
        'this.lsClient=a;this.abortController=null;this._onDidChange=new of;this.rootProvider=new Oi({});',
        'this.lsClient=a;this.abortController=null;this._onDidChange=new of;let __initSt={};try{var __cst=JSON.parse(localStorage.getItem("jetski.cachedAppStateJson_v6")||"{}");if(__cst&&typeof __cst==="object"&&Object.keys(__cst).length>0)__initSt=__cst;}catch(_){}this.rootProvider=new Oi(__initSt);'
    ),
    # 8. Sync live appState to localStorage in c8b onMessage
    (
        'b&&(this.rootProvider.setState(c),this.initialized=!0,this._onDidChange.fire())',
        'b&&(this.rootProvider.setState(c),this.initialized=!0,this._onDidChange.fire(),(()=>{try{localStorage.setItem("jetski.cachedAppStateJson_v6",JSON.stringify(c));}catch(_){}})())'
    ),
    # 9. Available models caching and fallback (instant cache hit + background refresh)
    (
        'let m=l(await k(b.getAvailableModels(r(ega,{forceRefresh:!0}))));if(!m.response)throw Error("[CloudCodeService] getAvailableModels returned no response");return m.response',
        'try{var __cachedModelsStr=localStorage.getItem("jetski.cachedAvailableModelsJson_v6");if(__cachedModelsStr){var __cm=JSON.parse(__cachedModelsStr);if(__cm&&__cm.models&&__cm.models.length>0){console.log("[Models] Instant cache hit (0 ms): served "+__cm.models.length+" cached models");b.getAvailableModels(r(ega,{forceRefresh:!0})).then(res=>{if(res&&res.response){try{localStorage.setItem("jetski.cachedAvailableModelsJson_v6",JSON.stringify(res.response));console.log("[Models] Background updated models from server ("+(res.response.models||[]).length+"): "+(res.response.models||[]).map(x=>x.model||x.id).join(", "));}catch(_){}}}).catch(()=>{});return __cm;}}}catch(_){}let m;try{m=l(await k(b.getAvailableModels(r(ega,{forceRefresh:!0}))));if(m&&m.response){try{localStorage.setItem("jetski.cachedAvailableModelsJson_v6",JSON.stringify(m.response));console.log("[Models] Updated models from server ("+(m.response.models||[]).length+"): "+(m.response.models||[]).map(x=>x.model||x.id).join(", "));}catch(_){}return m.response;}}catch(__e){try{var __cm=JSON.parse(localStorage.getItem("jetski.cachedAvailableModelsJson_v6")||"null");if(__cm&&__cm.models&&__cm.models.length>0){console.log("[Models] Offline fallback: served "+__cm.models.length+" cached models");return __cm;}}catch(_){}throw __e;}if(!m?.response)throw Error("[CloudCodeService] getAvailableModels returned no response");return m.response'
    ),
    # 10. User status caching
    (
        'e.userStatus&&a.provider.setState(e.userStatus)',
        'if(e.userStatus){a.provider.setState(e.userStatus);try{var __usStr=JSON.stringify(e.userStatus,(k,v)=>typeof v==="bigint"?v.toString():v);console.log("[Auth] UserStatus received from server: email="+(e.userStatus.email||e.userStatus.name||"unknown")+", tier="+(e.userStatus.tier||"free"));localStorage.setItem("jetski.cachedUserStatusJson_v6",__usStr);}catch(_){}}'
    ),
    # 11. Pre-seed user status in J8b constructor
    (
        'this.lsClient=a;this.metadata=b;this.authStateProvider=c;this.provider=new Oi(r(H.UserStatusSchema));',
        'this.lsClient=a;this.metadata=b;this.authStateProvider=c;let __initUs=r(H.UserStatusSchema);try{var __cus=JSON.parse(localStorage.getItem("jetski.cachedUserStatusJson_v6")||"null");if(__cus&&typeof __cus==="object"){__initUs=__cus;console.log("[Auth] Pre-seeded UserStatus from cache: email="+(__cus.email||__cus.name||"unknown"));}}catch(_){}this.provider=new Oi(__initUs);'
    ),
    # 12. Non-blocking getLocalUserInfo fast path
    (
        'async function B8b(a){var b=d,c=d;try{let e=c(await b(a.lsClient.getLocalUserInfo(r(nga,{})))),f=e.username;return{homeDirUri:tf(e.homeDirUri),username:f}}finally{b()}}',
        'async function B8b(a){var b=d,c=d;try{try{a.lsClient.getLocalUserInfo(r(nga,{})).then(e=>{if(e&&e.homeDirUri){console.log("[Auth] LocalUserInfo received: username="+(e.username||"") + ", homeDirUri="+e.homeDirUri);try{localStorage.setItem("jetski.cachedUserInfoJson_v6",JSON.stringify({homeDirUri:e.homeDirUri,username:e.username||""}));}catch(_){}}}).catch(()=>{});}catch(_){}var __u;try{__u=JSON.parse(localStorage.getItem("jetski.cachedUserInfoJson_v6")||"null");}catch(_){}if(!__u||!__u.homeDirUri)__u={homeDirUri:"file:///data/user/0/"+(window.location.port==="48000"?"com.antigravity.mobile":"com.antigravity.mobile.dev")+"/files",username:""};return{homeDirUri:tf(__u.homeDirUri),username:__u.username||""}}finally{b()}}'
    ),
    # 13. Non-blocking getServerConfiguration fast path
    (
        'function q8b(a){var b=d,c=d;try{try{let e=c(await b(a.lsClient.getServerConfiguration({})));return{enabled:!!e.config?.sidecars?.enabled,allowAll:!!e.config?.sidecars?.allowAll}}catch(e){return c(),console.warn("Failed to fetch server configuration, falling back to Mendel flags:",e),{enabled:!0,allowAll:!1}}}finally{b()}}',
        'function q8b(a){var b=d,c=d;try{try{let e=c(await b(a.lsClient.getServerConfiguration({})));return{enabled:!!e.config?.sidecars?.enabled,allowAll:!!e.config?.sidecars?.allowAll}}catch(e){return c(),{enabled:!0,allowAll:!1}}}finally{b()}}'
    ),
    # 14. Non-blocking getMendelFlags fast path
    (
        'async function $ja(a){var b=d,c=d;try{try{let e=c(await b(a.getMendelFlags(r(qha,{})))).experimentConfig?.experiments??[];a={};for(let f of e)f.keyString&&(a[f.keyString]=f);return a}catch(e){c(),console.error("Failed to fetch Mendel flags",e)}}finally{b()}}',
        'async function $ja(a){var b=d,c=d;try{try{var __mf=null;try{__mf=JSON.parse(localStorage.getItem("jetski.cachedMendelFlagsJson_v6")||"null");}catch(_){}try{a.getMendelFlags(r(qha,{})).then(res=>{var exps=res?.experimentConfig?.experiments??[];var obj={};for(var i=0;i<exps.length;i++){if(exps[i].keyString)obj[exps[i].keyString]=exps[i];}if(Object.keys(obj).length>0){localStorage.setItem("jetski.cachedMendelFlagsJson_v6",JSON.stringify(obj));}}).catch(()=>{});}catch(_){}if(__mf&&typeof __mf==="object"&&Object.keys(__mf).length>0){console.log("[Mendel] Instant cached Mendel flags served ("+Object.keys(__mf).length+" flags)");return __mf;}return{};}catch(__err){c();return{};}}finally{b()}}'
    ),
    # 15. Do not block React startup on Mendel flags initPromise
    (
        'u.dispatch(jta(K.features.storageService,t)),c(await b(I.JSC$5910_initPromise)),\ndka(I)&&u.dispatch(Isa(K.features.storageService,t)));',
        'u.dispatch(jta(K.features.storageService,t)),(I.JSC$5910_initPromise.then(()=>dka(I)&&u.dispatch(Isa(K.features.storageService,t))).catch(()=>{}),null));'
    ),
    # 16. Do not block React startup on extensibility/sidecars r8b
    (
        'let [pa,fa]=c(await b(Promise.all([r8b(q,I,K,P.homeDirUri),F])));',
        'let fa=c(await b(F)),pa={extensibilityFeature:{},paneDescriptors:[]};r8b(q,I,K,P.homeDirUri).catch(()=>{});'
    ),
    # 17. Instant NUX readiness (kind: 1) so UI doesn't await NUX configs
    (
        'var e=new Oi({kind:0}),f=[],g=!1,h=async()=>{var k=d,l=d;try{if(!g){g=!0;try{var m=l(await k(b.getCascadeNuxes({}))).nuxes?.map(q=>',
        'var e=new Oi({kind:1,seenNuxes:{},fetchedConfigs:[],mountedLocations:{},ranTriggers:{}}),f=[],g=!1,h=async()=>{var k=d,l=d;try{if(!g){g=!0;try{var m=l(await k(b.getCascadeNuxes({}))).nuxes?.map(q=>'
    ),
    # 18. Clear cached user on logout
    (
        'async logout(){var a=d,b=d;try{this.isLoggingOut=!0;this._currentSessionId++;',
        'async logout(){var a=d,b=d;try{this.isLoggingOut=!0;this._currentSessionId++;console.log("[Auth] User logged out: clearing cached UserStatus and UserInfo");try{localStorage.removeItem("jetski.cachedUserStatusJson_v6");localStorage.removeItem("jetski.cachedUserInfoJson_v6");}catch(_){}'
    ),
    # 19. Immediate c8b initialization so p0b renders J1a immediately without waiting for server state
    (
        'this.initialized=!1;this.connectionHealthProvider=new Oi(!0);this.subscribe()',
        'this.initialized=!0;this.connectionHealthProvider=new Oi(!0);this.subscribe()'
    )
]

def patch_main_js(main_js_path):
    print(f"[patch_cache] Inspecting {main_js_path}...")
    if not os.path.isfile(main_js_path):
        return False

    with open(main_js_path, "r", encoding="utf-8") as f:
        content = f.read()

    changed = False
    for idx, (target, replace) in enumerate(MAIN_JS_PATCHES, 1):
        if target in content:
            content = content.replace(target, replace)
            changed = True
            print(f"[+] [patch_cache] Applied main.js patch {idx}/{len(MAIN_JS_PATCHES)}")
        elif replace in content:
            print(f"[*] [patch_cache] main.js patch {idx}/{len(MAIN_JS_PATCHES)} already applied.")
        else:
            print(f"[-] [patch_cache] Warning: Target pattern {idx} not found in main.js!")

    if changed:
        with open(main_js_path, "w", encoding="utf-8") as f:
            f.write(content)
        print(f"[+] [patch_cache] Successfully updated {main_js_path}!")
    else:
        print("[*] [patch_cache] main.js is already fully up-to-date.")

    return True

def patch_cache_pipeline(web_dir=None):
    candidates = []
    if web_dir:
        candidates.append(web_dir)

    script_dir = os.path.dirname(os.path.abspath(__file__))
    repo_root = os.path.abspath(os.path.join(script_dir, ".."))
    candidates.append(os.path.join(repo_root, "web_ui"))
    candidates.append("web_ui")

    patched_any = False
    for d in candidates:
        index_path = os.path.join(d, "index.html") if os.path.isdir(d) else d
        if os.path.isfile(index_path):
            if patch_index_html(index_path):
                patched_any = True
            js_path = os.path.join(os.path.dirname(index_path), "main.js")
            if os.path.isfile(js_path):
                clean_backup = os.path.join(repo_root, "staging", "clean_web_ui", "main.js")
                if os.path.isfile(clean_backup):
                    shutil.copyfile(clean_backup, js_path)
                    print(f"[*] [patch_cache] Restored {js_path} from clean base.")
                    try:
                        from patch_touch import patch_main_js as patch_touch_main_js
                        patch_touch_main_js(js_path)
                    except Exception as e:
                        print(f"[-] [patch_cache] Warning re-applying touch patch: {e}")
                patch_main_js(js_path)
            break

    return patched_any

if __name__ == "__main__":
    target = sys.argv[1] if len(sys.argv) > 1 else None
    success = patch_cache_pipeline(web_dir=target)
    sys.exit(0 if success else 1)
