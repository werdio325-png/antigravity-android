/**
 * patches/startup_ready/detect-ui.js - has React mounted real UI content?
 */
function isUiRendered() {
  try {
    var root = global.document && global.document.getElementById('root');
    if (!root) return false;

    // 1. Has real chat message turns or conversation content rendered?
    var hasMessages = !!root.querySelector(
      '[data-testid="conversation-view"], [data-testid="autoscroll-viewport"], [role="article"], .prose'
    );
    if (hasMessages) {
      signal('messages-rendered');
      return true;
    }

    // 2. Has the input area appeared?
    var hasInput = !!root.querySelector(
      '[data-lexical-editor="true"], textarea, [contenteditable="true"], [role="textbox"], button[type="submit"]'
    );
    if (hasInput) {
      signal('chat-input-ready');
      return true;
    }

    // 3. Has any auth, landing, onboarding or welcome content rendered?
    var hasContent = !!(root.firstElementChild || (root.children && root.children.length > 0));
    if (hasContent) {
      signal('root-has-content');
      return true;
    }
  } catch (e) {}
  return false;
}
