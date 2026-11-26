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
        f"var [showOpenAiModal,setShowOpenAiModal]=(0,{react_id}.useState)(!1);"
        f"var [openAiUrl,setOpenAiUrl]=(0,{react_id}.useState)(()=>{{"
        "try{if(window.Android&&typeof window.Android.getOpenAiConfig==='function'){var c=JSON.parse(window.Android.getOpenAiConfig());return c.url||'https://api.openai.com/v1'}"
        "var s=localStorage.getItem('ag_openai_config');return s?JSON.parse(s).url||'https://api.openai.com/v1':'https://api.openai.com/v1'}catch(_){return 'https://api.openai.com/v1'}});"
        f"var [openAiKey,setOpenAiKey]=(0,{react_id}.useState)(()=>{{"
        "try{if(window.Android&&typeof window.Android.getOpenAiConfig==='function'){var c=JSON.parse(window.Android.getOpenAiConfig());return c.apiKey||''}"
        "var s=localStorage.getItem('ag_openai_config');return s?JSON.parse(s).apiKey||'':''}catch(_){return ''}});"
        f"var [openAiModel,setOpenAiModel]=(0,{react_id}.useState)(()=>{{"
        "try{if(window.Android&&typeof window.Android.getOpenAiConfig==='function'){var c=JSON.parse(window.Android.getOpenAiConfig());return c.model||'gpt-4o'}"
        "var s=localStorage.getItem('ag_openai_config');return s?JSON.parse(s).model||'gpt-4o':'gpt-4o'}catch(_){return 'gpt-4o'}});"
        f"var [showKeyText,setShowKeyText]=(0,{react_id}.useState)(!1);"
        f"var [detectedModels,setDetectedModels]=(0,{react_id}.useState)([]);"
        f"var [isDetecting,setIsDetecting]=(0,{react_id}.useState)(!1);"
        f"var [detectStatus,setDetectStatus]=(0,{react_id}.useState)('');"
        f"var detectModelsFn=(0,{react_id}.useCallback)(async(u,k)=>{{"
        "setIsDetecting(!0);setDetectStatus('Detecting models...');try{"
        "var raw='';"
        "if(window.Android&&typeof window.Android.fetchOpenAiModels==='function'){"
        "raw=window.Android.fetchOpenAiModels(u||'https://api.openai.com/v1',k);"
        "}else{"
        "var fu=(u||'https://api.openai.com/v1').replace(/\\/+$/,'')+'/models';"
        "var res=await fetch(fu,{headers:k?{'Authorization':'Bearer '+k}:{}});"
        "raw=await res.text();"
        "}"
        "var d=JSON.parse(raw);"
        "if(d.error){var em=typeof d.error==='string'?d.error:(d.error.message||JSON.stringify(d.error));setDetectStatus('Error: '+em);return;}"
        "var l=[];if(Array.isArray(d.data)){l=d.data.map(m=>m.id||m.name).filter(Boolean);}"
        "else if(Array.isArray(d.models)){l=d.models.map(m=>m.name||m.id||m.model).filter(Boolean);}"
        "if(l.length>0){l.sort();setDetectedModels(l);setDetectStatus('Found '+l.length+' models!');if(!l.includes(openAiModel))setOpenAiModel(l[0]);}"
        "else{setDetectStatus('No models found at endpoint.');}"
        "}catch(err){setDetectStatus('Failed: '+(err.message||String(err)));}"
        "finally{setIsDetecting(!1);}"
        "},[openAiModel]);"
        f"var saveOpenAiConfigFn=(0,{react_id}.useCallback)(()=>{{"
        "var u=openAiUrl||'https://api.openai.com/v1';var k=openAiKey||'';var m=openAiModel||'gpt-4o';"
        "var allMods=detectedModels&&detectedModels.length>0?detectedModels:[m];"
        "if(!allMods.includes(m))allMods.unshift(m);"
        "try{if(window.Android&&typeof window.Android.setOpenAiConfig==='function')window.Android.setOpenAiConfig(u,k,allMods.join(','));"
        "localStorage.setItem('ag_openai_config',JSON.stringify({url:u,apiKey:k,model:m,models:allMods}))}catch(_){}"
        "setShowOpenAiModal(!1);"
        "},[openAiUrl,openAiKey,openAiModel,detectedModels]);"
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
        'title:"OpenAI Compatible Model Source",'
        'className:"w-[90vw] max-w-[460px] rounded-xl",'
        'zClassName:"z-[8500]"'
        f'}},{react_id}.createElement("div",{{className:"flex flex-col gap-3 py-1 text-foreground"}},'
        f'{react_id}.createElement("p",{{className:"text-xs text-muted-foreground m-0 -mt-2 mb-1"}},"Configure endpoint, API key, and detect models"),'
        f'{react_id}.createElement("div",{{className:"flex flex-col gap-1.5"}},'
        f'{react_id}.createElement("label",{{className:"text-xs font-medium text-foreground"}},"Base URL (Endpoint)"),'
        f'{react_id}.createElement("input",{{type:"text",value:openAiUrl,onChange:e=>setOpenAiUrl(e.target.value),'
        'placeholder:"https://api.openai.com/v1",'
        'className:"w-full rounded-md border border-border bg-muted/40 px-3 py-2 text-xs text-foreground outline-none focus:border-primary focus:ring-1 focus:ring-primary placeholder:text-muted-foreground transition-colors"})'
        '),'
        f'{react_id}.createElement("div",{{className:"flex flex-col gap-1.5"}},'
        f'{react_id}.createElement("div",{{className:"flex items-center justify-between"}},'
        f'{react_id}.createElement("label",{{className:"text-xs font-medium text-foreground"}},"API Key"),'
        f'{react_id}.createElement("button",{{type:"button",onClick:()=>setShowKeyText(!showKeyText),'
        'className:"text-xs text-primary hover:underline cursor-pointer bg-transparent border-none p-0"},showKeyText?"Hide":"Show")'
        '),'
        f'{react_id}.createElement("input",{{type:showKeyText?"text":"password",value:openAiKey,onChange:e=>setOpenAiKey(e.target.value),'
        'placeholder:"sk-...",'
        'className:"w-full rounded-md border border-border bg-muted/40 px-3 py-2 text-xs text-foreground outline-none focus:border-primary focus:ring-1 focus:ring-primary placeholder:text-muted-foreground transition-colors"})'
        '),'
        f'{react_id}.createElement("div",{{className:"flex flex-wrap items-center gap-2 pt-1"}},'
        f'{react_id}.createElement("button",{{type:"button",disabled:isDetecting||!openAiUrl,onClick:()=>detectModelsFn(openAiUrl,openAiKey),'
        'className:"px-3 py-1.5 text-xs rounded-md bg-secondary text-secondary-foreground font-medium hover:bg-muted border border-border cursor-pointer flex items-center gap-1.5 transition-colors disabled:opacity-50"},isDetecting?"Detecting...":"🔍 Detect Models"),'
        f'detectStatus?{react_id}.createElement("span",{{className:"text-xs "+(detectStatus.startsWith("Error")?"text-destructive font-medium":(detectStatus.startsWith("Found")?"text-emerald-500 font-medium":"text-muted-foreground"))}},detectStatus):null'
        '),'
        f'{react_id}.createElement("div",{{className:"flex flex-col gap-1.5 mt-1"}},'
        f'{react_id}.createElement("div",{{className:"flex items-center justify-between"}},'
        f'{react_id}.createElement("label",{{className:"text-xs font-medium text-foreground"}},"Model Name"),'
        f'detectedModels.length>0?{react_id}.createElement("span",{{className:"text-[11px] text-muted-foreground"}},detectedModels.length+" detected"):null'
        '),'
        f'detectedModels.length>0?{react_id}.createElement("select",{{value:openAiModel,onChange:e=>setOpenAiModel(e.target.value),'
        'className:"w-full rounded-md border border-border bg-background px-3 py-2 text-xs text-foreground outline-none focus:border-primary focus:ring-1 focus:ring-primary cursor-pointer transition-colors"},'
        f'detectedModels.map(function(m){{return {react_id}.createElement("option",{{key:m,value:m}},m);}})):'
        f'{react_id}.createElement("input",{{type:"text",value:openAiModel,onChange:e=>setOpenAiModel(e.target.value),'
        'placeholder:"e.g. gpt-4o, deepseek-chat, claude-3-5-sonnet",'
        'className:"w-full rounded-md border border-border bg-muted/40 px-3 py-2 text-xs text-foreground outline-none focus:border-primary focus:ring-1 focus:ring-primary placeholder:text-muted-foreground transition-colors"})'
        '),'
        f'{react_id}.createElement("div",{{className:"flex items-center justify-end gap-2 border-t border-border pt-3 mt-1"}},'
        f'{react_id}.createElement("button",{{type:"button",onClick:()=>setShowOpenAiModal(!1),'
        'className:"px-3.5 py-1.5 text-xs rounded-md bg-secondary text-secondary-foreground font-medium hover:bg-muted border border-border cursor-pointer transition-colors"},"Cancel"),'
        f'{react_id}.createElement("button",{{type:"button",onClick:saveOpenAiConfigFn,'
        'className:"px-4 py-1.5 text-xs rounded-md bg-primary text-primary-foreground font-medium hover:opacity-90 cursor-pointer border-none shadow-xs transition-opacity"},"Save Settings")'
        ')'
        ')):null'
    )

    orig_theme = theme_match.group(0)
    injected_theme = (
        orig_theme
        + f',{react_id}.createElement({item_comp},{{label:"Desktop Mode",description:"Use desktop viewport and user agent for the workspace",'
        + f'rightElement:{react_id}.createElement({switch_comp},{{checked:isDesktop,onCheckedChange:onDesktopToggle}})}})'
        + f',{react_id}.createElement({item_comp},{{label:"OpenAI Compatible Model Source",description:"Configure OpenAI-compatible API key, endpoint, and auto-detect models",'
        + f'rightElement:{react_id}.createElement("button",{{type:"button",onClick:()=>setShowOpenAiModal(!0),'
        + 'className:"px-3 py-1.5 text-xs rounded bg-primary text-primary-foreground font-medium cursor-pointer hover:opacity-90 border-none"},"Configure")'
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
        'var raw="";if(window.Android&&typeof window.Android.fetchOpenAiModels==="function"){raw=window.Android.fetchOpenAiModels(m||"https://api.openai.com/v1",apiKey);}\n'
        'else{var fu=(m||"https://api.openai.com/v1").replace(/\\/+$/,"")+"/models";var res=await fetch(fu,{headers:apiKey?{"Authorization":"Bearer "+apiKey}:{}});raw=await res.text();}\n'
        'var d=JSON.parse(raw);if(d.error){setGzbDetectStatus("Error: "+(typeof d.error==="string"?d.error:(d.error.message||JSON.stringify(d.error))));return;}\n'
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
    gzb_save_replacement = 'if(provider==="openai"){try{if(window.Android&&typeof window.Android.setOpenAiConfig==="function")window.Android.setOpenAiConfig(m||"https://api.openai.com/v1",apiKey,k);localStorage.setItem("ag_openai_config",JSON.stringify({url:m||"https://api.openai.com/v1",apiKey:apiKey,model:k}))}catch(_){}}L=p(I.ModelInfoSchema,{modelId:I.Model.GOOGLE_GEMINI_INTERNAL_BYOM,apiProvider:provider==="openai"?I.APIProvider.API_PROVIDER_OPENAI_VERTEX:I.APIProvider.API_PROVIDER_GOOGLE_EVERGREEN,chatModelName:k,modelName:m||(provider==="openai"?"https://api.openai.com/v1":""),maxTokens:Math.max(0,Math.min(Number(q)||0,Number.MAX_SAFE_INTEGER)),modelFeatures:L});b==="edit"&&e?g({type:"editCustomModel",oldLabel:e,label:k,modelInfo:L}):g({type:"addCustomModel",label:k,modelInfo:L});h({label:k,value:I.Model.GOOGLE_GEMINI_INTERNAL_BYOM,isCustom:!0,customModelUrl:L.modelName,disabled:!1});'
    if gzb_save_target in content:
        content = content.replace(gzb_save_target, gzb_save_replacement, 1)
        print("[web-patch] Gzb save callback updated with OpenAI persistence")

    gzb_dis_target = "onClick:J,disabled:!k||!m||!q"
    gzb_dis_replacement = 'onClick:J,disabled:!k||!q||(provider==="openai"?!apiKey:!m)'
    if gzb_dis_target in content:
        content = content.replace(gzb_dis_target, gzb_dis_replacement, 1)
        print("[web-patch] Gzb validation logic updated")

    tab_target = 'A.createElement("div",{className:"grid w-full grid-cols-1 gap-2"},A.createElement("div",{className:"flex flex-col gap-1"},\nA.createElement("label",{htmlFor:C,className:"text-sm"},"Model Label")'
    tab_replacement = (
        'A.createElement("div",{className:"grid w-full grid-cols-1 gap-2"},'
        'A.createElement("div",{className:"flex items-center gap-2 mb-1 p-1 bg-muted rounded-md w-full"},'
        'A.createElement("button",{type:"button",onClick:()=>{setProvider("openai");if(!m||m.startsWith("gemini-api:"))n("https://api.openai.com/v1");if(!k)l("gpt-4o");if(!q)r("128000")},'
        'className:"flex-1 py-1.5 text-xs rounded-md font-medium cursor-pointer border-none transition-colors "+(provider==="openai"?"bg-background text-foreground shadow-xs":"bg-transparent text-muted-foreground hover:text-foreground")},"OpenAI Compatible"),'
        'A.createElement("button",{type:"button",onClick:()=>{setProvider("gemini");if(m==="https://api.openai.com/v1")n("")},'
        'className:"flex-1 py-1.5 text-xs rounded-md font-medium cursor-pointer border-none transition-colors "+(provider==="gemini"?"bg-background text-foreground shadow-xs":"bg-transparent text-muted-foreground hover:text-foreground")},"Google Gemini")'
        '),'
        'A.createElement("div",{className:"flex flex-col gap-1"},\nA.createElement("label",{htmlFor:C,className:"text-sm"},"Model Label")'
    )
    if tab_target in content:
        content = content.replace(tab_target, tab_replacement, 1)
        print("[web-patch] Gzb provider tabs injected")

    apikey_field_target = "placeholder:'e.g. \"gemini-api:/models/gemini-v3-byom\"'}))),"
    apikey_field_replacement = (
        'placeholder:provider==="openai"?"https://api.openai.com/v1":\'e.g. "gemini-api:/models/gemini-v3-byom"\'}))),'
        '(provider==="openai"?'
        'A.createElement("div",{className:"flex flex-col gap-2"},'
        'A.createElement("div",{className:"flex flex-col gap-1"},'
        'A.createElement("div",{className:"flex items-center justify-between"},'
        'A.createElement("label",{className:"text-sm"},"API Key"),'
        'A.createElement("button",{type:"button",onClick:()=>setShowKey(s=>!s),'
        'className:"text-xs text-muted-foreground hover:text-foreground cursor-pointer bg-transparent border-none"},showKey?"Hide":"Show")'
        '),'
        'A.createElement("div",{className:"bg-muted rounded-md w-full"},'
        'A.createElement("input",{type:showKey?"text":"password",value:apiKey,onChange:L=>setApiKey(L.target.value),onKeyDown:K,'
        'placeholder:"sk-...",className:"w-full rounded-md border-none bg-background p-2 text-foreground outline-none focus:outline-none focus-visible:outline-none focus:ring-0 focus:ring-transparent placeholder:text-muted-foreground"})'
        ')'
        '),'
        'A.createElement("div",{className:"flex flex-wrap items-center gap-2 pt-1"},'
        'A.createElement("button",{type:"button",disabled:gzbIsDetecting||!m,onClick:detectModelsInGzb,'
        'className:"px-3 py-1.5 text-xs rounded-md bg-secondary text-secondary-foreground font-medium hover:bg-muted border border-border cursor-pointer flex items-center gap-1.5 transition-colors disabled:opacity-50"},gzbIsDetecting?"Detecting...":"🔍 Detect Models"),'
        'gzbDetectStatus?A.createElement("span",{className:"text-xs "+(gzbDetectStatus.startsWith("Error")?"text-destructive font-medium":(gzbDetectStatus.startsWith("Found")?"text-emerald-500 font-medium":"text-muted-foreground"))},gzbDetectStatus):null'
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
        'var _getAgOpenAiModels=()=>{var res=[];try{var s=localStorage.getItem("ag_openai_config"),raw="";'
        'if(window.Android&&typeof window.Android.getOpenAiConfig==="function")raw=window.Android.getOpenAiConfig();'
        'var cfg=null;if(s){try{cfg=JSON.parse(s)}catch(_){}}else if(raw){try{var rc=JSON.parse(raw);if(rc.apiKey&&rc.apiKey.trim())cfg=rc}catch(_){}}'
        'if(cfg){var url=cfg.url||"https://api.openai.com/v1",mods=[];if(cfg.model&&typeof cfg.model==="string"&&cfg.model.trim())mods.push(cfg.model.trim());'
        'if(Array.isArray(cfg.models))cfg.models.forEach(m=>{if(m&&typeof m==="string"&&m.trim()&&!mods.includes(m.trim()))mods.push(m.trim())});'
        'else if(typeof cfg.models==="string")cfg.models.split(",").forEach(m=>{m=m.trim();if(m&&!mods.includes(m))mods.push(m)});'
        'if(mods.length===0)mods.push("gpt-4o");'
        'for(var mod of mods)res.push({label:mod,value:I.Model.GOOGLE_GEMINI_INTERNAL_BYOM,isCustom:!0,customModelUrl:url,disabled:!1})}}catch(_){}'
        'return res};'
        'var cKa=a=>{var base=a?.customModels?Object.entries(a.customModels).map(([b,c])=>({label:b,value:c.modelId,isCustom:!0,customModelUrl:c.modelName,disabled:!1})):[],ag=_getAgOpenAiModels();'
        'for(var m of ag)if(!base.some(x=>x.label===m.label))base.push(m);return base},'
        'Py=(a,b)=>{if(b?.customModels&&b.customModels[a])return b.customModels[a];try{var s=localStorage.getItem("ag_openai_config"),raw="";'
        'if(window.Android&&typeof window.Android.getOpenAiConfig==="function")raw=window.Android.getOpenAiConfig();'
        'var cfg=null;if(s){try{cfg=JSON.parse(s)}catch(_){}}else if(raw){try{var rc=JSON.parse(raw);if(rc.apiKey&&rc.apiKey.trim())cfg=rc}catch(_){}}'
        'if(cfg){var mUrl=cfg.url||"https://api.openai.com/v1";return p(I.ModelInfoSchema,{modelId:I.Model.GOOGLE_GEMINI_INTERNAL_BYOM,apiProvider:I.APIProvider.API_PROVIDER_OPENAI_VERTEX,chatModelName:a,modelName:mUrl,maxTokens:128000})}}catch(_){}};'
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
