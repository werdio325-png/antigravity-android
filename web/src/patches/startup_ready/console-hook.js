/**
 * patches/startup_ready/console-hook.js - signal on ConversationLoad completion.
 */
try {
  var origInfo = console.info ? console.info.bind(console) : null;
  if (origInfo) {
    console.info = function () {
      var msg = arguments[0];
      if (typeof msg === 'string' && msg.indexOf('[ConversationLoad]') !== -1) {
        signal('conversation-load-completed');
      }
      return origInfo.apply(console, arguments);
    };
  }
} catch (e) {}
