/**
 * patches/updater_ui/install.js - mount the panel and accept native events.
 */
function installUpdaterUi() {
  if (updaterState.installed) return;
  updaterState.installed = true;
  window.__agUpdaterUi = { __installed: true };

  window[UPDATER.eventName] = function (state) {
    if (!state) return;
    // A postponed version still shows in the panel, it just stops nagging.
    updaterRender(state);
    if (state.state === 'available' && state.postponedVersion === state.latestVersion) {
      var button = document.getElementById(UPDATER.buttonId);
      if (button) button.textContent = UPDATER.idleLabel;
    }
  };

  function mount() {
    try {
      updaterEnsureDom();
      updaterRefresh();
    } catch (error) {
      console.warn('[agUpdater] mount failed', error);
    }
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', mount);
  } else {
    mount();
  }
  console.log('[agUpdater] ready');
}
