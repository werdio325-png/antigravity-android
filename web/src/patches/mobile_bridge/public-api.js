/**
 * patches/mobile_bridge/public-api.js - wire focus tracking, globals & UI mount.
 */
function installFocusTracking() {
  if (!doc) return;
  doc.addEventListener('focusin', function (ev) {
    var el = ev.target;
    if (!el || isOwnBridgeElement(el)) return;
    if (isTerminalElement(el)) {
      if ((el.tagName || '').toUpperCase() === 'TEXTAREA') {
        state.lastTerminal = el;
      } else if (el.querySelector) {
        var helper = el.querySelector('.xterm-helper-textarea, textarea');
        if (helper) state.lastTerminal = helper;
      }
      return;
    }
    if (isEditableElement(el)) {
      state.lastEditable = el;
    }
  }, true);
}

function onExtraKeysVisibilityChanged(visible) {
  writeStorage(STORAGE_KEYS.extraKeys, !!visible);
  syncDrawerState();
}

function installMobileBridge() {
  if (state.installed) return;
  state.installed = true;
  state.voiceServiceActive = isVoiceServiceActiveNative();
  state.voiceServiceMuted = isVoiceMutedNative();
  state.dictating = isVoiceDictatingNative();
  installVoiceWebSocketHook();
  installMicTrackObserver();
  installClipboardImageInterceptor();
  installFocusTracking();
  mountQuickControls();
  // Drain any payload stashed by native before mobile_bridge.js executed.
  if (global.__AG_PENDING_SHARED_PAYLOAD__) {
    var stashed = global.__AG_PENDING_SHARED_PAYLOAD__;
    global.__AG_PENDING_SHARED_PAYLOAD__ = null;
    deliverSharedPayload(stashed);
  }
}

// Install WebSocket/mic hooks immediately so early voice connections are observed.
installVoiceWebSocketHook();
installMicTrackObserver();

// Expose top-level native callback hooks immediately (before main.js / onUiReady).
global.__agDeliverSharedPayload = deliverSharedPayload;
global.__agExtraKey = dispatchExtraKey;
global.__agExtraKeysVisibilityChanged = onExtraKeysVisibilityChanged;
global.__agDeliverVoiceText = deliverVoiceText;
global.__agVoiceDictationStateChanged = onVoiceDictationStateChanged;
global.__agVoiceServiceStateChanged = onVoiceServiceStateChanged;
global.__agDeliverPastedImage = deliverPastedImage;

global.__agMobileBridge = {
  __installed: true,
  deliverSharedPayload: deliverSharedPayload,
  dispatchExtraKey: dispatchExtraKey,
  deliverVoiceText: deliverVoiceText,
  deliverPastedImage: deliverPastedImage,
  pasteImageFromClipboard: pasteImageFromNativeClipboard,
  onVoiceDictationStateChanged: onVoiceDictationStateChanged,
  onVoiceServiceStateChanged: onVoiceServiceStateChanged,
  applyBufferedShare: applyBufferedShare,
  openDrawer: function () { setDrawerOpen(true); },
  closeDrawer: function () { setDrawerOpen(false); },
  toggleDrawer: function () { setDrawerOpen(!state.drawerOpen); },
  injectSettingsControls: tryInjectSettingsSection,
  isDesktopMode: getDesktopModeState,
  setDesktopMode: setDesktopModeState,
  isExtraKeysVisible: getExtraKeysState,
  setExtraKeysVisible: setExtraKeysState,
  openLocalPreview: triggerLocalPreview,
  openRemotePortal: triggerRemotePortal,
  startVoiceDictation: startNativeVoiceDictation,
  stopVoiceDictation: stopNativeVoiceDictation,
  startVoiceService: startNativeVoiceService,
  stopVoiceService: stopNativeVoiceService,
  setVoiceMuted: setNativeVoiceMuted,
  isVoiceServiceActive: isVoiceServiceActiveNative,
  probe: function () {
    return {
      installed: state.installed,
      uiMounted: state.uiMounted,
      drawerOpen: state.drawerOpen,
      hasPendingShare: !!(state.pendingShare && state.pendingShare.formatted),
      shareDeliveries: state.shareDeliveries,
      extraKeyDispatches: state.extraKeyDispatches,
      voiceDeliveries: state.voiceDeliveries,
      imagePastes: state.imagePastes,
      dictating: state.dictating,
      voiceServiceActive: state.voiceServiceActive || isVoiceServiceActiveNative(),
      voiceServiceMuted: state.voiceServiceMuted,
      activeVoiceSockets: state.activeVoiceSockets.length,
      desktopMode: getDesktopModeState(),
      extraKeysVisible: getExtraKeysState(),
      hasComposer: !!findComposer(),
      hasAndroid: !!getAndroid()
    };
  }
};

if (doc && doc.readyState === 'loading') {
  doc.addEventListener('DOMContentLoaded', installMobileBridge);
} else {
  installMobileBridge();
}
