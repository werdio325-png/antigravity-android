/**
 * patches/startup_ready/public-api.js - expose and arm on boot.
 */
if (global.document && global.document.readyState === 'loading') {
  global.document.addEventListener('DOMContentLoaded', arm, { once: true });
} else {
  arm();
}

global.__agStartupReady = {
  __installed: true,
  signal: signal
};
