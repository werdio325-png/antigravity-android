/**
 * patches/startup_ready/console-hook.js - signal on ConversationLoad completion.
 */
try {
  var origInfo = console.info ? console.info.bind(console) : null;
  if (origInfo) {
    console.info = function () {
      var msg = arguments[0];
      if (!state.signalled && typeof msg === 'string' && msg.indexOf('[ConversationLoad]') !== -1) {
        console.info = origInfo;
        signal('conversation-load-completed');
      } else if (state.signalled) {
        console.info = origInfo;
      }
      return origInfo.apply(console, arguments);
    };
  }
} catch (e) {}
