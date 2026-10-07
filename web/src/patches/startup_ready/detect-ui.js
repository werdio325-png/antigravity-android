/**
 * patches/startup_ready/detect-ui.js - has React mounted real UI content?
 */
function isUiRendered() {
  try {
    var root = global.document && global.document.getElementById('root');
    if (!root) return false;

    // 1. Has real chat message turns or conversation content rendered?
    var hasMessages = !!root.querySelector('[data-role="user"], [data-role="assistant"], [data-turn-id], .message-row, .prose, [data-message-id]');
    if (hasMessages) {
      signal('messages-rendered');
      return true;
    }

    // 2. Has the input area appeared?
    var hasInput = !!root.querySelector('textarea, [contenteditable="true"], [role="textbox"], button[type="submit"]');
    if (hasInput) {
      if (!state.inputSeenAt) {
        state.inputSeenAt = Date.now();
      }
      // If input area has been visible for > 1000ms and no conversation load was triggered, it's an empty new chat!
      if (Date.now() - state.inputSeenAt > 1000) {
        signal('new-chat-input-settled');
        return true;
      }
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
