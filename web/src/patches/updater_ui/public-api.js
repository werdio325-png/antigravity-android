/**
 * patches/updater_ui/public-api.js - window.AgyUpdater diagnostics.
 */
window.AgyUpdater = window.AgyUpdater || {};

window.AgyUpdater.open = function () { updaterSetOpen(true); updaterRefresh(); };
window.AgyUpdater.close = function () { updaterSetOpen(false); };
window.AgyUpdater.state = function () { return updaterState.last; };
window.AgyUpdater.check = updaterCheck;

installUpdaterUi();
