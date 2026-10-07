/**
 * patches/theme_sync/resolve.js - theme resolution (classList > storage > host).
 */
function fromClassList() {
  try {
    var body = doc && doc.body;
    if (body && body.classList) {
      if (body.classList.contains('theme-light')) return 'light';
      if (body.classList.contains('theme-dark')) return 'dark';
    }
  } catch (e) { /* ignore */ }
  return null;
}

function fromStorage() {
  try {
    var store = window.localStorage;
    if (!store) return null;
    for (var i = 0; i < store.length; i++) {
      var key = store.key(i);
      if (!key || !/^theme-preset-/.test(key)) continue;
      if (/-light$/.test(key)) return 'light';
      if (/-dark$/.test(key)) return 'dark';
      var value = String(store.getItem(key) || '').toLowerCase();
      if (value.indexOf('light') >= 0) return 'light';
      if (value.indexOf('dark') >= 0) return 'dark';
    }
  } catch (e) { /* ignore */ }
  return null;
}

function resolveTheme() {
  return fromClassList() || fromStorage() || hostTheme;
}
