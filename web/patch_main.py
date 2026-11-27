#!/usr/bin/env python3
"""
web/patch_main.py - Dynamic patcher for web/main.js.

Applies:
1. Appearance settings:
   - Desktop Mode toggle
   - Themed in-app Modal Dialog for OpenAI-compatible model configuration (API Key, Base URL, Model Name, and Auto-Detect Models)
   - Projects Storage Folder selector (Android Files app / Documents)
2. Interactive Folder Picker (wqc):
   - Direct connection to Android native SAF directory picker (window.dialog.showOpenDialog)
3. Custom Model Modal (Gzb):
   - OpenAI-compatible provider tab with API Key field (show/hide toggle)
   - Real-time model detection button (querying endpoint /models) with dropdown selection
   - Persistence to AndroidBridge and localStorage
   - Support for custom OpenAI-compatible endpoints (DeepSeek, Groq, Ollama, OpenRouter, etc.)
"""

import sys
import os
import re

def patch_main(file_path):
    if not os.path.isfile(file_path):
        print(f"[web-patch] file not found: {file_path}", file=sys.stderr)
        return False

    with open(file_path, "r", encoding="utf-8") as f:
        content = f.read()

    if "Desktop Mode" in content and "OpenAI Compatible" in content and "showOpenAiModal" in content:
        print("[web-patch] Customizations already present in main.js")
        return True

    # 1. State hook injection pattern in Appearance component:
    hook_pattern = re.compile(r'(var\s+[A-Za-z0-9_$]+=[A-Za-z0-9_$]+!==3,\s*[A-Za-z0-9_$]+=[A-Za-z0-9_$]+!==2,\s*[A-Za-z0-9_$]+=[A-Za-z0-9_$]+=>\s*[A-Za-z0-9_$]+=>\{.*?\};)')
    hook_match = hook_pattern.search(content)

    # 2. JSX Theme element injection: find Theme element in Appearance
    theme_idx = content.find('{label:"Theme"')
    theme_match = None
    react_id = "A"
    if theme_idx != -1:
        window_start = max(0, theme_idx - 100)
        window_end = min(len(content), theme_idx + 500)
        window_text = content[window_start:window_end]
        theme_pattern = re.compile(
            r'([A-Za-z0-9_$]+)\.createElement\(([A-Za-z0-9_$]+),\{label:"Theme",.*?onSelect:[A-Za-z0-9_$]+\}\)'
        )
        theme_match = theme_pattern.search(window_text)
        if theme_match:
            react_id = theme_match.group(1)

    if not hook_match or not theme_match:
        print(f"[web-patch] Appearance patterns not matched: hook={bool(hook_match)}, theme={bool(theme_match)}", file=sys.stderr)
        return False

    orig_hook = hook_match.group(1)
    injected_hook = (
        f"var [isDesktop,setIsDesktop]=(0,{react_id}.useState)(()=>{{"
        "try{if(window.Android&&typeof window.Android.isDesktopMode==='function')return window.Android.isDesktopMode();"
        "var s=localStorage.getItem('ag_desktop_mode');return s===null?!1:s==='true'}catch(_){return !1}});"
        f"var onDesktopToggle=(0,{react_id}.useCallback)(v=>{{"
        "setIsDesktop(v);try{localStorage.setItem('ag_desktop_mode',String(v));"
        "if(window.Android&&typeof window.Android.setDesktopMode==='function')window.Android.setDesktopMode(v)}catch(_){}},[]);"
        f"var [colorTheme,setColorTheme]=(0,{react_id}.useState)(()=>{{"
        "try{return localStorage.getItem('ag_color_theme')||'default'}catch(_){return 'default'}});"
        f"var onColorThemeSelect=(0,{react_id}.useCallback)(id=>{{"
        "var v=typeof window.__AG_SET_COLOR_THEME__==='function'?window.__AG_SET_COLOR_THEME__(id):id;"
        "setColorTheme(v);},[]);"
        f"var [showOpenAiModal,setShowOpenAiModal]=(0,{react_id}.useState)(!1);"
        f"var [openAiConfigs,setOpenAiConfigs]=(0,{react_id}.useState)(()=>{{"
        "try{var raw='';if(window.Android&&typeof window.Android.getOpenAiConfigs==='function'){raw=window.Android.getOpenAiConfigs();}"
        "if(!raw)raw=localStorage.getItem('ag_openai_configs')||'';"
        "if(raw){var arr=JSON.parse(raw);if(Array.isArray(arr)&&arr.length>0)return arr.slice(0,5);}"
        "var legacy=localStorage.getItem('ag_openai_config')||(window.Android&&typeof window.Android.getOpenAiConfig==='function'?window.Android.getOpenAiConfig():'');"
        "if(legacy){var c=JSON.parse(legacy);var mods=Array.isArray(c.models)?c.models:(typeof c.models==='string'?c.models.split(',').map(x=>x.trim()).filter(Boolean):[]);"
        "return [{id:'api-1',name:'API 1',url:c.url||'https://api.openai.com/v1',apiKey:c.apiKey||'',model:c.model||mods[0]||'gpt-4o',models:mods}];}"
        "}catch(_){}"
        "return [{id:'api-1',name:'API 1',url:'https://api.openai.com/v1',apiKey:'',model:'gpt-4o',models:[]}];});"
        f"var [activeApiIdx,setActiveApiIdx]=(0,{react_id}.useState)(0);"
        "var activeCfg=openAiConfigs[activeApiIdx]||openAiConfigs[0]||{id:'api-1',name:'API 1',url:'https://api.openai.com/v1',apiKey:'',model:'gpt-4o',models:[]};"
        f"var updateActiveCfg=(0,{react_id}.useCallback)(patch=>{{"
        "setOpenAiConfigs(prev=>prev.map((item,i)=>i===activeApiIdx?Object.assign({},item,patch):item));"
        "},[activeApiIdx]);"
        f"var [showKeyText,setShowKeyText]=(0,{react_id}.useState)(!1);"
        f"var [isDetecting,setIsDetecting]=(0,{react_id}.useState)(!1);"
        f"var [detectStatus,setDetectStatus]=(0,{react_id}.useState)('');"
        f"var [claudeUsage,setClaudeUsage]=(0,{react_id}.useState)(()=>{{"
        "try{if(window.Android&&typeof window.Android.getLastClaudeUsage==='function'){return JSON.parse(window.Android.getLastClaudeUsage());}}catch(_){}return null;});"
        f"var [isCheckingUsage,setIsCheckingUsage]=(0,{react_id}.useState)(!1);"
        f"var checkClaudeUsageFn=(0,{react_id}.useCallback)((tok)=>{{"
        "if(!window.Android||typeof window.Android.getClaudeUsage!=='function')return;"
        "setIsCheckingUsage(!0);try{var raw=window.Android.getClaudeUsage(tok||'');if(raw){setClaudeUsage(JSON.parse(raw));}}catch(_){}finally{setIsCheckingUsage(!1);}"
        "},[]);"
        "var fmtReset=function(r){if(!r)return '';try{var d=/^\\d+$/.test(String(r))?new Date(Number(r)*1000):new Date(r);var diff=Math.max(0,d.getTime()-Date.now());if(isNaN(diff))return '';var mins=Math.floor(diff/60000),hrs=Math.floor(mins/60),days=Math.floor(hrs/24);if(days>0)return 'resets in '+days+'d '+(hrs%24)+'h';if(hrs>0)return 'resets in '+hrs+'h '+(mins%60)+'m';return 'resets in '+mins+'m';}catch(_){return '';}};"
        f"var addApiSlot=(0,{react_id}.useCallback)(()=>{{"
        "setOpenAiConfigs(prev=>{if(prev.length>=5)return prev;"
        "var nextIdx=prev.length;"
        "var next=prev.concat([{id:'api-'+(nextIdx+1),name:'API '+(nextIdx+1),url:'https://api.openai.com/v1',apiKey:'',model:'gpt-4o',models:[]}]);"
        "setActiveApiIdx(nextIdx);setDetectStatus('');return next;});"
        "},[]);"
        f"var removeApiSlot=(0,{react_id}.useCallback)(idx=>{{"
        "setOpenAiConfigs(prev=>{if(prev.length<=1)return prev;"
        "var next=prev.filter((_,i)=>i!==idx);"
        "setActiveApiIdx(a=>a>=next.length?next.length-1:(a>idx?a-1:a));setDetectStatus('');return next;});"
        "},[]);"
        f"var detectModelsFn=(0,{react_id}.useCallback)(async(u,k)=>{{"
        "setIsDetecting(!0);setDetectStatus('Detecting models...');try{"
        "var isClaude=activeCfg.providerType==='anthropic-setup-token';"
        "var targetUrl=isClaude?'https://api.anthropic.com/v1':(u||'https://api.openai.com/v1');"
        "var raw='';"
        "if(window.Android&&typeof window.Android.fetchOpenAiModels==='function'){"
        "raw=window.Android.fetchOpenAiModels(targetUrl,k);"
        "}else{"
        "var fu=targetUrl.replace(/\\/+$/,'')+'/models';"
        "var res=await fetch(fu,{headers:k?{'Authorization':'Bearer '+k}:{}});"
        "raw=await res.text();"
        "}"
        "var d=JSON.parse(raw);"
        "if(isClaude){checkClaudeUsageFn(k);}"
        "if(d.error){"
        "if(isClaude){var defC=['claude-sonnet-4-5-20250929','claude-opus-4-5-20251101','claude-3-7-sonnet-20250219','claude-3-5-sonnet-20241022','claude-3-5-haiku-20241022'];updateActiveCfg({models:defC,model:defC.includes(activeCfg.model)?activeCfg.model:defC[0]});setDetectStatus('Loaded '+defC.length+' Claude models');return;}"
        "var em=typeof d.error==='string'?d.error:(d.error.message||JSON.stringify(d.error));setDetectStatus('Error: '+em);return;}"
        "var l=[];if(Array.isArray(d.data)){l=d.data.map(m=>m.id||m.name).filter(Boolean);}"
        "else if(Array.isArray(d.models)){l=d.models.map(m=>m.name||m.id||m.model).filter(Boolean);}"
        "if(l.length>0){l.sort();setDetectStatus('Found '+l.length+' models!');"
        "updateActiveCfg({models:l,model:l.includes(activeCfg.model)?activeCfg.model:l[0]});}"
        "else{setDetectStatus('No models found at endpoint.');}"
        "}catch(err){setDetectStatus('Failed: '+(err.message||String(err)));}"
        "finally{setIsDetecting(!1);}"
        "},[activeCfg,updateActiveCfg,checkClaudeUsageFn]);"
        f"var saveOpenAiConfigFn=(0,{react_id}.useCallback)(()=>{{"
        "var bridgeUrl=(window.Android&&typeof window.Android.getAnthropicBridgeUrl==='function')?window.Android.getAnthropicBridgeUrl():'';"
        "var normalized=openAiConfigs.slice(0,5).map((c,idx)=>{"
        "var pt=c.providerType||'openai';"
        "var u=pt==='anthropic-setup-token'?(bridgeUrl||(c.url||'').trim()||'https://api.anthropic.com/v1'):((c.url||'').trim()||'https://api.openai.com/v1');"
        "var k=c.apiKey||'';var m=(c.model||'').trim()||(pt==='anthropic-setup-token'?'claude-sonnet-4-5-20250929':'gpt-4o');"
        "var mods=Array.isArray(c.models)&&c.models.length>0?c.models.slice():[m];"
        "if(!mods.includes(m))mods.unshift(m);"
        "return {id:c.id||('api-'+(idx+1)),name:(c.name||'').trim()||('API '+(idx+1)),providerType:pt,url:u,apiKey:k,model:m,models:mods};});"
        "var act=normalized[activeApiIdx]||normalized[0];"
        "try{"
        "localStorage.setItem('ag_openai_configs',JSON.stringify(normalized));"
        "localStorage.setItem('ag_openai_config',JSON.stringify({url:act.url,apiKey:act.apiKey,model:act.model,models:act.models}));"
        "if(window.Android&&typeof window.Android.setOpenAiConfigs==='function'){window.Android.setOpenAiConfigs(JSON.stringify(normalized),activeApiIdx);}"
        "else if(window.Android&&typeof window.Android.setOpenAiConfig==='function'){window.Android.setOpenAiConfig(act.url,act.apiKey,act.models.join(','));}"
        "}catch(_){}"
        "setOpenAiConfigs(normalized);setShowOpenAiModal(!1);"
        "},[openAiConfigs,activeApiIdx]);"
        + orig_hook
    )

    # Detect switch and setting item components
    item_comp = "v3"
    switch_comp = "dX"
    re_idx = content.find('rightElement:')
    if re_idx != -1:
        re_window = content[max(0, re_idx - 150):min(len(content), re_idx + 150)]
        item_m = re.search(r'([A-Za-z0-9_$]+)\.createElement\(([A-Za-z0-9_$]+),\{label:[^,]+,description:[^,]+,rightElement:', re_window)
        if item_m:
            item_comp = item_m.group(2)
        sw_m = re.search(r'rightElement:[A-Za-z0-9_$]+\.createElement\(([A-Za-z0-9_$]+),\{checked:', re_window)
        if sw_m:
            switch_comp = sw_m.group(1)

    modal_jsx = (
        f'showOpenAiModal?{react_id}.createElement(NC,{{'
        'isOpen:showOpenAiModal,'
        'onClose:()=>setShowOpenAiModal(!1),'
        'title:"Model Sources (OpenAI & Claude)",'
        'className:"w-[92vw] max-w-[480px] rounded-xl",'
        'zClassName:"z-[8500]"'
        f'}},{react_id}.createElement("div",{{className:"flex flex-col gap-3 py-1 text-foreground"}},'
        f'{react_id}.createElement("p",{{className:"text-xs text-muted-foreground m-0 -mt-2"}},"Configure up to 5 OpenAI-compatible APIs or Claude (setup-token)"),'
        f'{react_id}.createElement("div",{{className:"flex flex-wrap items-center gap-1.5 pb-1 border-b border-border"}},'
        f'openAiConfigs.map(function(cfg,idx){{var isSel=idx===activeApiIdx;var hasKey=Boolean(cfg.apiKey&&cfg.apiKey.trim());'
        f'return {react_id}.createElement("button",{{key:cfg.id||idx,type:"button",onClick:()=>{{setActiveApiIdx(idx);setDetectStatus("");}},'
        'className:"px-2.5 py-1 text-xs rounded-md font-medium cursor-pointer flex items-center gap-1.5 transition-colors "+(isSel?"bg-primary text-primary-foreground border border-primary":"bg-secondary text-secondary-foreground border border-border hover:bg-muted")},'
        f'hasKey?{react_id}.createElement("span",{{className:"w-1.5 h-1.5 rounded-full "+(isSel?"bg-primary-foreground":"bg-emerald-500")}}):null,'
        'cfg.name||("API "+(idx+1)));}),'
        f'openAiConfigs.length<5?{react_id}.createElement("button",{{type:"button",onClick:addApiSlot,'
        'className:"px-2.5 py-1 text-xs rounded-md font-medium cursor-pointer bg-muted/60 text-foreground border border-dashed border-border hover:bg-muted transition-colors"},"+ Add API ("+openAiConfigs.length+"/5)"):null'
        '),'
        f'{react_id}.createElement("div",{{className:"flex items-center gap-1.5 p-1 bg-muted rounded-md"}},'
        f'{react_id}.createElement("button",{{type:"button",onClick:()=>updateActiveCfg({{providerType:"openai",url:(!activeCfg.url||activeCfg.url.includes("127.0.0.1")||activeCfg.url.includes("anthropic.com"))?"https://api.openai.com/v1":activeCfg.url}}),'
        'className:"flex-1 py-1 text-xs rounded font-medium cursor-pointer border-none transition-colors "+((activeCfg.providerType||"openai")==="openai"?"bg-background text-foreground shadow-xs":"bg-transparent text-muted-foreground")},"OpenAI Compatible"),'
        f'{react_id}.createElement("button",{{type:"button",onClick:()=>{{var bu=(window.Android&&typeof window.Android.getAnthropicBridgeUrl==="function")?window.Android.getAnthropicBridgeUrl():"https://api.anthropic.com/v1";var defM=["claude-sonnet-4-5-20250929","claude-opus-4-5-20251101","claude-3-7-sonnet-20250219","claude-3-5-sonnet-20241022","claude-3-5-haiku-20241022"];updateActiveCfg({{providerType:"anthropic-setup-token",name:(activeCfg.name||"").startsWith("API ")?"Claude":activeCfg.name,url:bu,model:(!activeCfg.model||activeCfg.model==="gpt-4o")?defM[0]:activeCfg.model,models:(Array.isArray(activeCfg.models)&&activeCfg.models.length>0)?activeCfg.models:defM}});}},'
        'className:"flex-1 py-1 text-xs rounded font-medium cursor-pointer border-none transition-colors "+(activeCfg.providerType==="anthropic-setup-token"?"bg-background text-foreground shadow-xs":"bg-transparent text-muted-foreground")},"Claude (setup-token)")'
        '),'
        f'{react_id}.createElement("div",{{className:"flex flex-col gap-1.5"}},'
        f'{react_id}.createElement("div",{{className:"flex items-center justify-between"}},'
        f'{react_id}.createElement("label",{{className:"text-xs font-medium text-foreground"}},"Provider Label"),'
        f'openAiConfigs.length>1?{react_id}.createElement("button",{{type:"button",onClick:()=>removeApiSlot(activeApiIdx),'
        'className:"text-xs text-destructive hover:underline cursor-pointer bg-transparent border-none p-0"},"Remove Slot"):null'
        '),'
        f'{react_id}.createElement("input",{{type:"text",value:activeCfg.name||"",onChange:e=>updateActiveCfg({{name:e.target.value}}),'
        'placeholder:"e.g. OpenAI, Claude, DeepSeek, Groq, Ollama",'
        'className:"w-full rounded-md border border-border bg-muted/40 px-3 py-2 text-xs text-foreground outline-none focus:border-primary focus:ring-1 focus:ring-primary placeholder:text-muted-foreground transition-colors"})'
        '),'
        f'activeCfg.providerType==="anthropic-setup-token"?{react_id}.createElement("div",{{className:"text-[11px] text-emerald-500 bg-emerald-500/10 border border-emerald-500/30 rounded-md px-2.5 py-1.5"}},"Built-in OpenAI <-> Anthropic bridge active (paste token from claude setup-token)"):'
        f'{react_id}.createElement("div",{{className:"flex flex-col gap-1.5"}},'
        f'{react_id}.createElement("label",{{className:"text-xs font-medium text-foreground"}},"Base URL (Endpoint)"),'
        f'{react_id}.createElement("input",{{type:"text",value:activeCfg.url||"",onChange:e=>updateActiveCfg({{url:e.target.value}}),'
        'placeholder:"https://api.openai.com/v1",'
        'className:"w-full rounded-md border border-border bg-muted/40 px-3 py-2 text-xs text-foreground outline-none focus:border-primary focus:ring-1 focus:ring-primary placeholder:text-muted-foreground transition-colors"})'
        '),'
        f'{react_id}.createElement("div",{{className:"flex flex-col gap-1.5"}},'
        f'{react_id}.createElement("div",{{className:"flex items-center justify-between"}},'
        f'{react_id}.createElement("label",{{className:"text-xs font-medium text-foreground"}},activeCfg.providerType==="anthropic-setup-token"?"Claude Setup Token (claude setup-token)":"API Key"),'
        f'{react_id}.createElement("button",{{type:"button",onClick:()=>setShowKeyText(!showKeyText),'
        'className:"text-xs text-primary hover:underline cursor-pointer bg-transparent border-none p-0"},showKeyText?"Hide":"Show")'
        '),'
        f'{react_id}.createElement("input",{{type:showKeyText?"text":"password",value:activeCfg.apiKey||"",onChange:e=>updateActiveCfg({{apiKey:e.target.value}}),'
        'placeholder:activeCfg.providerType==="anthropic-setup-token"?"sk-ant-oat01-...":"sk-...",'
        'className:"w-full rounded-md border border-border bg-muted/40 px-3 py-2 text-xs text-foreground outline-none focus:border-primary focus:ring-1 focus:ring-primary placeholder:text-muted-foreground transition-colors"})'
        '),'
        f'activeCfg.providerType==="anthropic-setup-token"?{react_id}.createElement("div",{{className:"flex flex-col gap-1.5 rounded-md border border-border bg-muted/30 p-2.5"}},'
        f'{react_id}.createElement("div",{{className:"flex items-center justify-between"}},'
        f'{react_id}.createElement("span",{{className:"text-[11px] font-semibold text-foreground"}},"Claude Quota (5h & 7d)"),'
        f'{react_id}.createElement("button",{{type:"button",disabled:isCheckingUsage||!activeCfg.apiKey,onClick:()=>checkClaudeUsageFn(activeCfg.apiKey),'
        'className:"text-[11px] text-primary hover:underline cursor-pointer bg-transparent border-none p-0 disabled:opacity-50"},isCheckingUsage?"Checking...":"Check Usage")'
        '),'
        f'claudeUsage&&(claudeUsage.fiveHourRemaining!==undefined||claudeUsage.sevenDayRemaining!==undefined)?'
        f'{react_id}.createElement("div",{{className:"flex flex-col gap-1 text-[11px] text-muted-foreground"}},'
        f'claudeUsage.fiveHourRemaining!==undefined?{react_id}.createElement("div",{{className:"flex items-center justify-between"}},'
        f'{react_id}.createElement("span",null,"5-Hour: "+claudeUsage.fiveHourRemaining+"% left"),'
        f'{react_id}.createElement("span",{{className:"text-[10px] opacity-80"}},fmtReset(claudeUsage.fiveHourReset))'
        '):null,'
        f'claudeUsage.sevenDayRemaining!==undefined?{react_id}.createElement("div",{{className:"flex items-center justify-between"}},'
        f'{react_id}.createElement("span",null,"7-Day: "+claudeUsage.sevenDayRemaining+"% left"),'
        f'{react_id}.createElement("span",{{className:"text-[10px] opacity-80"}},fmtReset(claudeUsage.sevenDayReset))'
        '):null'
        '):'
        f'{react_id}.createElement("span",{{className:"text-[11px] text-muted-foreground"}},"Tap Check Usage (or send a message) to read 5h & 7d remaining quota.")'
        '):null,'
        f'{react_id}.createElement("div",{{className:"flex flex-wrap items-center gap-2 pt-1"}},'
        f'{react_id}.createElement("button",{{type:"button",disabled:isDetecting||!activeCfg.url,onClick:()=>detectModelsFn(activeCfg.url,activeCfg.apiKey),'
        'className:"px-3 py-1.5 text-xs rounded-md bg-secondary text-secondary-foreground font-medium hover:bg-muted border border-border cursor-pointer flex items-center gap-1.5 transition-colors disabled:opacity-50"},isDetecting?"Detecting...":"🔍 Detect Models"),'
        f'detectStatus?{react_id}.createElement("span",{{className:"text-xs "+(detectStatus.startsWith("Error")?"text-destructive font-medium":(detectStatus.startsWith("Found")?"text-emerald-500 font-medium":"text-muted-foreground"))}},detectStatus):null'
        '),'
        f'{react_id}.createElement("div",{{className:"flex flex-col gap-1.5 mt-1"}},'
        f'{react_id}.createElement("div",{{className:"flex items-center justify-between"}},'
        f'{react_id}.createElement("label",{{className:"text-xs font-medium text-foreground"}},"Model Name"),'
        f'Array.isArray(activeCfg.models)&&activeCfg.models.length>0?{react_id}.createElement("span",{{className:"text-[11px] text-muted-foreground"}},activeCfg.models.length+" detected"):null'
        '),'
        f'Array.isArray(activeCfg.models)&&activeCfg.models.length>0?{react_id}.createElement("select",{{value:activeCfg.model||"",onChange:e=>updateActiveCfg({{model:e.target.value}}),'
        'className:"w-full rounded-md border border-border bg-background px-3 py-2 text-xs text-foreground outline-none focus:border-primary focus:ring-1 focus:ring-primary cursor-pointer transition-colors"},'
        f'activeCfg.models.map(function(m){{return {react_id}.createElement("option",{{key:m,value:m}},m);}})):'
        f'{react_id}.createElement("input",{{type:"text",value:activeCfg.model||"",onChange:e=>updateActiveCfg({{model:e.target.value}}),'
        'placeholder:"e.g. gpt-4o, deepseek-chat, claude-3-5-sonnet",'
        'className:"w-full rounded-md border border-border bg-muted/40 px-3 py-2 text-xs text-foreground outline-none focus:border-primary focus:ring-1 focus:ring-primary placeholder:text-muted-foreground transition-colors"})'
        '),'
        f'{react_id}.createElement("div",{{className:"flex items-center justify-end gap-2 border-t border-border pt-3 mt-1"}},'
        f'{react_id}.createElement("button",{{type:"button",onClick:()=>setShowOpenAiModal(!1),'
        'className:"px-3.5 py-1.5 text-xs rounded-md bg-secondary text-secondary-foreground font-medium hover:bg-muted border border-border cursor-pointer transition-colors"},"Cancel"),'
        f'{react_id}.createElement("button",{{type:"button",onClick:saveOpenAiConfigFn,'
        'className:"px-4 py-1.5 text-xs rounded-md bg-primary text-primary-foreground font-medium hover:opacity-90 cursor-pointer border-none shadow-xs transition-opacity"},"Save All ("+openAiConfigs.length+")")'
        ')'
        ')):null'
    )

    color_themes_list = "(window.__AG_COLOR_THEMES__||[{id:'default',name:'Default',accent:'#007acc',bg:'#101010'}])"
    orig_theme = theme_match.group(0)
    injected_theme = (
        orig_theme
        + f',{react_id}.createElement({item_comp},{{label:"Color Theme",description:"Choose a colorful theme palette for the interface",'
        + f'rightElement:{react_id}.createElement("div",{{className:"flex flex-wrap items-center justify-end gap-1.5 max-w-[340px]"}},'
        + f'{color_themes_list}.map(function(t){{var active=colorTheme===t.id;'
        + f'return {react_id}.createElement("button",{{key:t.id,type:"button",onClick:()=>onColorThemeSelect(t.id),'
        + 'className:"px-2 py-1 text-[11px] rounded-md font-medium cursor-pointer flex items-center gap-1.5 transition-all border "+(active?"border-primary bg-primary/15 text-foreground shadow-xs":"border-border bg-secondary text-secondary-foreground hover:bg-muted")},'
        + f'{react_id}.createElement("span",{{style:{{width:"10px",height:"10px",borderRadius:"9999px",backgroundColor:t.accent,border:"1.5px solid "+t.bg,display:"inline-block",flexShrink:0}}}}),t.name);}}))}})'
        + f',{react_id}.createElement({item_comp},{{label:"Desktop Mode",description:"Use desktop viewport and user agent for the workspace",'
        + f'rightElement:{react_id}.createElement({switch_comp},{{checked:isDesktop,onCheckedChange:onDesktopToggle}})}})'
        + f',{react_id}.createElement({item_comp},{{label:"OpenAI Compatible Model Sources",description:"Configure up to 5 OpenAI-compatible API keys, endpoints, and auto-detect models ("+openAiConfigs.filter(c=>c.apiKey&&c.apiKey.trim()).length+"/5 active)",'
        + f'rightElement:{react_id}.createElement("button",{{type:"button",onClick:()=>setShowOpenAiModal(!0),'
        + 'className:"px-3 py-1.5 text-xs rounded bg-primary text-primary-foreground font-medium cursor-pointer hover:opacity-90 border-none"},"Configure ("+openAiConfigs.length+"/5)")'
        + f'}})'
        + f',{react_id}.createElement({item_comp},{{label:"Projects Storage Folder",description:"Folder accessed by Android Files app for your projects (default: Documents/Antigravity)",'
        + f'rightElement:{react_id}.createElement("button",{{type:"button",onClick:async()=>{{'
        + 'if(typeof window.dialog?.showOpenDialog==="function"){'
        + 'try{'
        + 'let p=await window.dialog.showOpenDialog();'
        + 'if(p){'
        + 'if(window.Android&&typeof window.Android.setDefaultProjectsDirectory==="function"){'
        + 'window.Android.setDefaultProjectsDirectory(p);'
        + '}'
        + 'alert("Projects storage folder set to: "+p);'
        + '}'
        + '}catch(e){alert("Folder selection error: "+e)}'
        + '}'
        + '},className:"px-3 py-1.5 text-xs rounded bg-secondary text-secondary-foreground font-medium cursor-pointer hover:bg-muted border border-border"},"Choose Folder")'
        + f'}})'
        + f',{modal_jsx}'
    )

    content = content.replace(orig_hook, injected_hook, 1)
    content = content.replace(orig_theme, injected_theme, 1)

    # 3. Interactive Folder Picker (wqc) patch
    wqc_target = 'function wqc(a,b){return{label:"Open Folder (Interactive)",id:"add-folder",isImportant:!0,addWorkspace:async c=>{var e=d,f=d;try{let g=c.showInteractiveFolderPicker'
    wqc_replacement = 'function wqc(a,b){return{label:"Open Folder",id:"add-folder",isImportant:!0,addWorkspace:async c=>{if(typeof window.dialog?.showOpenDialog==="function"){try{let picked=await window.dialog.showOpenDialog();if(picked){let u=picked.startsWith("file://")?picked:Se(picked).toString();return{workspaceUri:u}}return}catch(err){console.warn("[OpenDir] dialog error",err)}}var e=d,f=d;try{let g=c.showInteractiveFolderPicker'
    if wqc_target in content:
        content = content.replace(wqc_target, wqc_replacement, 1)
        print("[web-patch] wqc folder picker hooked to Android SAF dialog")
    else:
        print("[web-patch] wqc target not found, skipping", file=sys.stderr)

    # 4. Custom Model Modal (Gzb) patch
    gzb_hook_target = 'var Gzb=({isOpen:a,operation:b,onClose:c,modelName:e,customModelsState:f,pushCustomModelsUpdate:g,setSelectedModelConfig:h})=>{var [k,l]=(0,A.useState)(""),[m,n]=(0,A.useState)(""),[q,r]=(0,A.useState)(""),[t,u]=(0,A.useState)(!0),[v,w]=(0,A.useState)(!0),[x,y]=(0,A.useState)(!0)'
    gzb_hook_replacement = (
        'var Gzb=({isOpen:a,operation:b,onClose:c,modelName:e,customModelsState:f,pushCustomModelsUpdate:g,setSelectedModelConfig:h})=>{\n'
        'var [k,l]=(0,A.useState)(""),[m,n]=(0,A.useState)(""),[q,r]=(0,A.useState)(""),[t,u]=(0,A.useState)(!0),[v,w]=(0,A.useState)(!0),[x,y]=(0,A.useState)(!0),'
        '[provider,setProvider]=(0,A.useState)("openai"),'
        '[apiKey,setApiKey]=(0,A.useState)(()=>{try{if(window.Android&&typeof window.Android.getOpenAiConfig==="function"){let c=JSON.parse(window.Android.getOpenAiConfig());return c.apiKey||""}let s=localStorage.getItem("ag_openai_config");return s?JSON.parse(s).apiKey||"":""}catch(_){return""}}),'
        '[showKey,setShowKey]=(0,A.useState)(!1),'
        '[gzbDetectedModels,setGzbDetectedModels]=(0,A.useState)([]),'
        '[gzbIsDetecting,setGzbIsDetecting]=(0,A.useState)(!1),'
        '[gzbDetectStatus,setGzbDetectStatus]=(0,A.useState)(""),'
        'detectModelsInGzb=async()=>{\n'
        'setGzbIsDetecting(!0);setGzbDetectStatus("Detecting...");try{\n'
        'var targetU=provider==="claude"?"https://api.anthropic.com/v1":(m||"https://api.openai.com/v1");\n'
        'var raw="";if(window.Android&&typeof window.Android.fetchOpenAiModels==="function"){raw=window.Android.fetchOpenAiModels(targetU,apiKey);}\n'
        'else{var fu=targetU.replace(/\\/+$/,"")+"/models";var res=await fetch(fu,{headers:apiKey?{"Authorization":"Bearer "+apiKey}:{}});raw=await res.text();}\n'
        'var d=JSON.parse(raw);if(d.error){if(provider==="claude"){var defC=["claude-sonnet-4-5-20250929","claude-opus-4-5-20251101","claude-3-7-sonnet-20250219","claude-3-5-sonnet-20241022","claude-3-5-haiku-20241022"];setGzbDetectedModels(defC);setGzbDetectStatus("Loaded "+defC.length+" Claude models");if(!k||!defC.includes(k))l(defC[0]);return;}setGzbDetectStatus("Error: "+(typeof d.error==="string"?d.error:(d.error.message||JSON.stringify(d.error))));return;}\n'
        'var list=[];if(Array.isArray(d.data)){list=d.data.map(item=>item.id||item.name).filter(Boolean);}\n'
        'else if(Array.isArray(d.models)){list=d.models.map(item=>item.name||item.id||item.model).filter(Boolean);}\n'
        'if(list.length>0){list.sort();setGzbDetectedModels(list);setGzbDetectStatus("Found "+list.length+" models!");if(!k||!list.includes(k)){l(list[0]);}}\n'
        'else{setGzbDetectStatus("No models returned.");}\n'
        '}catch(err){setGzbDetectStatus("Failed: "+(err.message||String(err)));}\n'
        'finally{setGzbIsDetecting(!1);}}'
    )
    if gzb_hook_target in content:
        content = content.replace(gzb_hook_target, gzb_hook_replacement, 1)
        print("[web-patch] Gzb state hooks and model detection injected")

    gzb_save_target = 'L=p(I.ModelInfoSchema,{modelId:I.Model.GOOGLE_GEMINI_INTERNAL_BYOM,apiProvider:I.APIProvider.API_PROVIDER_GOOGLE_EVERGREEN,chatModelName:k,modelName:m,maxTokens:Math.max(0,Math.min(Number(q)||0,Number.MAX_SAFE_INTEGER)),modelFeatures:L});b==="edit"&&e?g({type:"editCustomModel",oldLabel:e,label:k,modelInfo:L}):g({type:"addCustomModel",label:k,modelInfo:L});h({label:k,value:I.Model.GOOGLE_GEMINI_INTERNAL_BYOM,isCustom:!0,customModelUrl:L.modelName,disabled:!1});'
    gzb_save_replacement = (
        'if(provider==="openai"||provider==="claude"){try{var isCl=provider==="claude";var bu=(isCl&&window.Android&&typeof window.Android.getAnthropicBridgeUrl==="function")?window.Android.getAnthropicBridgeUrl():"";var u=isCl?(bu||m||"https://api.anthropic.com/v1"):(m||"https://api.openai.com/v1");'
        'var rawC="";if(window.Android&&typeof window.Android.getOpenAiConfigs==="function")rawC=window.Android.getOpenAiConfigs();'
        'if(!rawC)rawC=localStorage.getItem("ag_openai_configs")||"";'
        'var cfgs=[];if(rawC){try{var pa=JSON.parse(rawC);if(Array.isArray(pa))cfgs=pa}catch(_){}}'
        'var pType=isCl?"anthropic-setup-token":"openai";'
        'var matchIdx=cfgs.findIndex(c=>c&&(isCl?c.providerType==="anthropic-setup-token":(c.url===u&&c.apiKey===apiKey)));'
        'if(matchIdx<0)matchIdx=cfgs.findIndex(c=>c&&(!c.apiKey||!c.apiKey.trim()));'
        'var modsToSave=gzbDetectedModels&&gzbDetectedModels.length>0?gzbDetectedModels.slice():[k];if(!modsToSave.includes(k))modsToSave.unshift(k);'
        'if(matchIdx>=0){var exMods=Array.isArray(cfgs[matchIdx].models)?cfgs[matchIdx].models:[];modsToSave.forEach(x=>{if(x&&!exMods.includes(x))exMods.push(x)});cfgs[matchIdx]=Object.assign({},cfgs[matchIdx],{providerType:pType,name:isCl?"Claude":(cfgs[matchIdx].name||"API "+(matchIdx+1)),url:u,apiKey:apiKey,model:k,models:exMods});}'
        'else if(cfgs.length<5){matchIdx=cfgs.length;cfgs.push({id:"api-"+(matchIdx+1),name:isCl?"Claude":"API "+(matchIdx+1),providerType:pType,url:u,apiKey:apiKey,model:k,models:modsToSave});}'
        'else{matchIdx=cfgs.length-1;cfgs[matchIdx]=Object.assign({},cfgs[matchIdx],{providerType:pType,name:isCl?"Claude":(cfgs[matchIdx].name||"API "+(matchIdx+1)),url:u,apiKey:apiKey,model:k,models:modsToSave});}'
        'localStorage.setItem("ag_openai_configs",JSON.stringify(cfgs));'
        'localStorage.setItem("ag_openai_config",JSON.stringify({url:u,apiKey:apiKey,model:k,models:cfgs[matchIdx].models}));'
        'if(window.Android&&typeof window.Android.setOpenAiConfigs==="function")window.Android.setOpenAiConfigs(JSON.stringify(cfgs),matchIdx);'
        'else if(window.Android&&typeof window.Android.setOpenAiConfig==="function")window.Android.setOpenAiConfig(u,apiKey,cfgs[matchIdx].models.join(","));'
        '}catch(_){}}'
        'var _effUrl=(provider==="claude"&&window.Android&&typeof window.Android.getAnthropicBridgeUrl==="function")?(window.Android.getAnthropicBridgeUrl()||m):m;'
        'L=p(I.ModelInfoSchema,{modelId:I.Model.GOOGLE_GEMINI_INTERNAL_BYOM,apiProvider:(provider==="openai"||provider==="claude")?I.APIProvider.API_PROVIDER_OPENAI_VERTEX:I.APIProvider.API_PROVIDER_GOOGLE_EVERGREEN,chatModelName:k,modelName:_effUrl||(provider==="openai"?"https://api.openai.com/v1":""),maxTokens:Math.max(0,Math.min(Number(q)||0,Number.MAX_SAFE_INTEGER)),modelFeatures:L});b==="edit"&&e?g({type:"editCustomModel",oldLabel:e,label:k,modelInfo:L}):g({type:"addCustomModel",label:k,modelInfo:L});h({label:k,value:I.Model.GOOGLE_GEMINI_INTERNAL_BYOM,isCustom:!0,customModelUrl:L.modelName,disabled:!1});'
    )
    if gzb_save_target in content:
        content = content.replace(gzb_save_target, gzb_save_replacement, 1)
        print("[web-patch] Gzb save callback updated with OpenAI & Claude persistence")

    gzb_dis_target = "onClick:J,disabled:!k||!m||!q"
    gzb_dis_replacement = 'onClick:J,disabled:!k||!q||((provider==="openai"||provider==="claude")?!apiKey:!m)'
    if gzb_dis_target in content:
        content = content.replace(gzb_dis_target, gzb_dis_replacement, 1)
        print("[web-patch] Gzb validation logic updated")

    tab_target = 'A.createElement("div",{className:"grid w-full grid-cols-1 gap-2"},A.createElement("div",{className:"flex flex-col gap-1"},\nA.createElement("label",{htmlFor:C,className:"text-sm"},"Model Label")'
    tab_replacement = (
        'A.createElement("div",{className:"grid w-full grid-cols-1 gap-2"},'
        'A.createElement("div",{className:"flex items-center gap-1 mb-1 p-1 bg-muted rounded-md w-full"},'
        'A.createElement("button",{type:"button",onClick:()=>{setProvider("openai");if(!m||m.startsWith("gemini-api:")||m.includes("anthropic.com")||m.includes("127.0.0.1"))n("https://api.openai.com/v1");if(!k||k.startsWith("claude-"))l("gpt-4o");if(!q)r("128000")},'
        'className:"flex-1 py-1.5 text-[11px] rounded-md font-medium cursor-pointer border-none transition-colors "+(provider==="openai"?"bg-background text-foreground shadow-xs":"bg-transparent text-muted-foreground hover:text-foreground")},"OpenAI"),'
        'A.createElement("button",{type:"button",onClick:()=>{setProvider("claude");var bu=(window.Android&&typeof window.Android.getAnthropicBridgeUrl==="function")?window.Android.getAnthropicBridgeUrl():"https://api.anthropic.com/v1";n(bu);if(!k||k==="gpt-4o")l("claude-sonnet-4-5-20250929");if(!q)r("200000");setGzbDetectedModels(["claude-sonnet-4-5-20250929","claude-opus-4-5-20251101","claude-3-7-sonnet-20250219","claude-3-5-sonnet-20241022","claude-3-5-haiku-20241022"])},'
        'className:"flex-1 py-1.5 text-[11px] rounded-md font-medium cursor-pointer border-none transition-colors "+(provider==="claude"?"bg-background text-foreground shadow-xs":"bg-transparent text-muted-foreground hover:text-foreground")},"Claude (setup-token)"),'
        'A.createElement("button",{type:"button",onClick:()=>{setProvider("gemini");if(m==="https://api.openai.com/v1"||m.includes("anthropic.com")||m.includes("127.0.0.1"))n("")},'
        'className:"flex-1 py-1.5 text-[11px] rounded-md font-medium cursor-pointer border-none transition-colors "+(provider==="gemini"?"bg-background text-foreground shadow-xs":"bg-transparent text-muted-foreground hover:text-foreground")},"Gemini")'
        '),'
        'A.createElement("div",{className:"flex flex-col gap-1"},\nA.createElement("label",{htmlFor:C,className:"text-sm"},"Model Label")'
    )
    if tab_target in content:
        content = content.replace(tab_target, tab_replacement, 1)
        print("[web-patch] Gzb provider tabs injected")

    apikey_field_target = "placeholder:'e.g. \"gemini-api:/models/gemini-v3-byom\"'}))),"
    apikey_field_replacement = (
        'placeholder:provider==="openai"?"https://api.openai.com/v1":(provider==="claude"?"Built-in Claude Bridge":\'e.g. "gemini-api:/models/gemini-v3-byom"\')}))),'
        '((provider==="openai"||provider==="claude")?'
        'A.createElement("div",{className:"flex flex-col gap-2"},'
        'A.createElement("div",{className:"flex flex-col gap-1"},'
        'A.createElement("div",{className:"flex items-center justify-between"},'
        'A.createElement("label",{className:"text-sm"},provider==="claude"?"Claude Setup Token (claude setup-token)":"API Key"),'
        'A.createElement("button",{type:"button",onClick:()=>setShowKey(s=>!s),'
        'className:"text-xs text-muted-foreground hover:text-foreground cursor-pointer bg-transparent border-none"},showKey?"Hide":"Show")'
        '),'
        'A.createElement("div",{className:"bg-muted rounded-md w-full"},'
        'A.createElement("input",{type:showKey?"text":"password",value:apiKey,onChange:L=>setApiKey(L.target.value),onKeyDown:K,'
        'placeholder:provider==="claude"?"sk-ant-oat01-...":"sk-...",className:"w-full rounded-md border-none bg-background p-2 text-foreground outline-none focus:outline-none focus-visible:outline-none focus:ring-0 focus:ring-transparent placeholder:text-muted-foreground"})'
        ')'
        '),'
        'A.createElement("div",{className:"flex flex-wrap items-center gap-2 pt-1"},'
        'A.createElement("button",{type:"button",disabled:gzbIsDetecting||(!m&&provider!=="claude"),onClick:detectModelsInGzb,'
        'className:"px-3 py-1.5 text-xs rounded-md bg-secondary text-secondary-foreground font-medium hover:bg-muted border border-border cursor-pointer flex items-center gap-1.5 transition-colors disabled:opacity-50"},gzbIsDetecting?"Detecting...":"🔍 Detect Models"),'
        'gzbDetectStatus?A.createElement("span",{className:"text-xs "+(gzbDetectStatus.startsWith("Error")?"text-destructive font-medium":(gzbDetectStatus.startsWith("Found")||gzbDetectStatus.startsWith("Loaded")?"text-emerald-500 font-medium":"text-muted-foreground"))},gzbDetectStatus):null'
        '),'
        'gzbDetectedModels.length>0?A.createElement("div",{className:"flex flex-col gap-1"},'
        'A.createElement("label",{className:"text-xs text-muted-foreground"},"Pick Detected Model:"),'
        'A.createElement("select",{value:k,onChange:L=>l(L.target.value),className:"w-full rounded-md border border-border bg-background p-2 text-xs text-foreground outline-none cursor-pointer"},'
        'gzbDetectedModels.map(name=>A.createElement("option",{key:name,value:name},name)))):null'
        '):null),'
    )
    if apikey_field_target in content:
        content = content.replace(apikey_field_target, apikey_field_replacement, 1)
        print("[web-patch] Gzb API key input field and model detector injected")

    # 5. Fix phantom "Unknown Project" bug in tsb
    tsb_target = 'if(b==="project")h=c.map(k=>{var l=f.find(n=>n.uri===k),m=e.filter(n=>n.summary.trajectoryMetadata?.projectId===k).map(n=>n.cascadeId);return{id:k,label:l?.label||g.find(n=>n.project.id===k)?.project.name||"Unknown Project",items:m}});'
    tsb_replacement = 'if(b==="project")h=c.map(k=>{var l=f.find(n=>n.uri===k),m=e.filter(n=>n.summary.trajectoryMetadata?.projectId===k).map(n=>n.cascadeId);var pName=l?.label||g.find(n=>n.project.id===k)?.project.name;return{id:k,label:pName||"Unknown Project",items:m,isReal:Boolean(pName)}}).filter(k=>k.isReal||k.items.length>0);'
    if tsb_target in content:
        content = content.replace(tsb_target, tsb_replacement, 1)
        print("[web-patch] Phantom Unknown Project bug patched in tsb")

    # 6. Show OpenAI compatible models in Model Selector dropdown
    # 6a. Inject _getAgOpenAiModels, update cKa, Py, and dKa
    cka_target = 'var cKa=a=>a?.customModels?Object.entries(a.customModels).map(([b,c])=>({label:b,value:c.modelId,isCustom:!0,customModelUrl:c.modelName})):[],Py=(a,b)=>{if(b?.customModels)return b.customModels[a]};var dKa=(a,b,c)=>{if(a=a.find(e=>e.label===c))return a;if(b=b.find(e=>e.label===c))return b};'
    cka_replacement = (
        'var _loadAgOpenAiConfigs=()=>{try{var raw="";'
        'if(window.Android&&typeof window.Android.getOpenAiConfigs==="function")raw=window.Android.getOpenAiConfigs();'
        'var ls=localStorage.getItem("ag_openai_configs");'
        'if(ls){try{var arr=JSON.parse(ls);if(Array.isArray(arr)&&arr.length>0)return arr.slice(0,5)}catch(_){}}'
        'if(raw){try{var arr2=JSON.parse(raw);if(Array.isArray(arr2)&&arr2.length>0)return arr2.slice(0,5)}catch(_){}}'
        'var s=localStorage.getItem("ag_openai_config"),rLegacy="";'
        'if(window.Android&&typeof window.Android.getOpenAiConfig==="function")rLegacy=window.Android.getOpenAiConfig();'
        'var cfg=null;if(s){try{cfg=JSON.parse(s)}catch(_){}}else if(rLegacy){try{var rc=JSON.parse(rLegacy);if(rc.apiKey&&rc.apiKey.trim())cfg=rc}catch(_){}}'
        'if(cfg)return [cfg]}catch(_){}return []};'
        'var _extractMods=cfg=>{var mods=[];if(cfg.model&&typeof cfg.model==="string"&&cfg.model.trim())mods.push(cfg.model.trim());'
        'if(Array.isArray(cfg.models))cfg.models.forEach(m=>{if(m&&typeof m==="string"&&m.trim()&&!mods.includes(m.trim()))mods.push(m.trim())});'
        'else if(typeof cfg.models==="string")cfg.models.split(",").forEach(m=>{m=m.trim();if(m&&!mods.includes(m))mods.push(m)});return mods};'
        'var _resolveAgUrl=cfg=>{if(cfg&&cfg.providerType==="anthropic-setup-token"&&window.Android&&typeof window.Android.getAnthropicBridgeUrl==="function"){try{var bu=window.Android.getAnthropicBridgeUrl();if(bu)return bu}catch(_){}}return (cfg&&cfg.url)||"https://api.openai.com/v1"};'
        'var _getAgOpenAiModels=()=>{var res=[],seen={};try{var cfgs=_loadAgOpenAiConfigs();'
        'for(var cfg of cfgs){if(!cfg)continue;var hasKey=cfg.apiKey&&String(cfg.apiKey).trim();var mods=_extractMods(cfg);'
        'if(!hasKey&&mods.length===0)continue;var url=_resolveAgUrl(cfg);if(mods.length===0)mods.push("gpt-4o");'
        'for(var mod of mods){if(!seen[mod]){seen[mod]=1;res.push({label:mod,value:I.Model.GOOGLE_GEMINI_INTERNAL_BYOM,isCustom:!0,customModelUrl:url,disabled:!1})}}}}catch(_){}'
        'return res};'
        'var cKa=a=>{var base=a?.customModels?Object.entries(a.customModels).map(([b,c])=>({label:b,value:c.modelId,isCustom:!0,customModelUrl:c.modelName,disabled:!1})):[],ag=_getAgOpenAiModels();'
        'for(var m of ag)if(!base.some(x=>x.label===m.label))base.push(m);return base},'
        'Py=(a,b)=>{if(b?.customModels&&b.customModels[a])return b.customModels[a];try{var cfgs=_loadAgOpenAiConfigs();'
        'var matched=cfgs.find(c=>c&&_extractMods(c).includes(a))||cfgs.find(c=>c&&c.apiKey&&String(c.apiKey).trim())||cfgs[0];'
        'if(matched){var mUrl=_resolveAgUrl(matched);return p(I.ModelInfoSchema,{modelId:I.Model.GOOGLE_GEMINI_INTERNAL_BYOM,apiProvider:I.APIProvider.API_PROVIDER_OPENAI_VERTEX,chatModelName:a,modelName:mUrl,maxTokens:128000})}}catch(_){}};'
        'var dKa=(a,b,c)=>{if(a=a?.find(e=>e.label===c))return a;if(b=b?.find(e=>e.label===c))return b;var ag=_getAgOpenAiModels(),found=ag.find(e=>e.label===c);if(found)return found};'
    )
    if cka_target in content:
        content = content.replace(cka_target, cka_replacement, 1)
        print("[web-patch] cKa, Py, and dKa patched to load OpenAI compatible models")

    # 6b. Enable custom models in Ycc
    ycc_target = 'r=k.osName==="linux"&&k.isGoogleInternal;\nk=k.isGoogleInternal;'
    ycc_replacement = 'r=!0;\nk=!0;'
    if ycc_target in content:
        content = content.replace(ycc_target, ycc_replacement, 1)
        print("[web-patch] Ycc custom models gating unblocked")

    # 6c. Enable supportsCustomModels in fKa provider
    fka_target = 'customModelsState:q,supportsCustomModels:n},a))'
    fka_replacement = 'customModelsState:q,supportsCustomModels:!0},a))'
    if fka_target in content:
        content = content.replace(fka_target, fka_replacement, 1)
        print("[web-patch] fKa supportsCustomModels enabled")

    # 6d. Enable supportsCustomModels and showCustomModelHeader in cYb
    cyb_target = 'customModels:g,supportsCustomModels:h,showCustomModelHeader:O,'
    cyb_replacement = 'customModels:g,supportsCustomModels:!0,showCustomModelHeader:!0,'
    if cyb_target in content:
        content = content.replace(cyb_target, cyb_replacement, 1)
        print("[web-patch] cYb custom models header and support enabled")

    # 6e. Dynamic custom models loading in PXb
    pxb_target = 'customModels:a.customModels??[],supportsCustomModels:a.supportsCustomModels??!1,showCustomModelHeader:a.showCustomModelHeader??!1,'
    pxb_replacement = 'customModels:(()=>{var list=a.customModels?[...a.customModels]:[];var ag=_getAgOpenAiModels();for(var m of ag){if(!list.some(x=>x.label===m.label))list.push(m)}return list})(),supportsCustomModels:!0,showCustomModelHeader:!0,'
    if pxb_target in content:
        content = content.replace(pxb_target, pxb_replacement, 1)
        print("[web-patch] PXb dynamic custom model provider enabled")

    # 6f. Keep built-in Gemini/Antigravity models (VXb) first and place Custom/OpenAI models (SXb) below
    wxb_target = '"Model"),q?a.map(r=>r.options.length>0&&A.createElement(VXb,{key:r.groupName,group:r})):A.createElement("p",{"data-testid":"model-selector-empty",className:"text-xs opacity-50 px-2 py-1"},"No models available"),g&&A.createElement(SXb,{customModels:c,onAddCustomModel:e,onEditCustomModel:f}))'
    wxb_replacement = '"Model"),q?a.map(r=>r.options.length>0&&A.createElement(VXb,{key:r.groupName,group:r})):A.createElement("p",{"data-testid":"model-selector-empty",className:"text-xs opacity-50 px-2 py-1"},"No models available"),A.createElement(SXb,{customModels:c,onAddCustomModel:e,onEditCustomModel:f}))'
    if wxb_target in content:
        content = content.replace(wxb_target, wxb_replacement, 1)
        print("[web-patch] WXb updated: built-in models first, Custom/OpenAI models below")

    # 6g. Fallback in LXb to first sort group if "recommended" is absent
    lxb_target = 'var b=a.find(c=>c.name.toLowerCase()==="recommended");'
    lxb_replacement = 'var b=a.find(c=>c.name.toLowerCase()==="recommended")||a[0];'
    if lxb_target in content:
        content = content.replace(lxb_target, lxb_replacement, 1)
        print("[web-patch] LXb fallback to first sort group enabled")

    with open(file_path, "w", encoding="utf-8") as f:
        f.write(content)

    print("[web-patch] Successfully patched main.js with OpenAI provider, model detection, themed modal, folder picker, and clean project list")
    return True

if __name__ == "__main__":
    path = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(__file__), "main.js")
    if not patch_main(path):
        sys.exit(1)
