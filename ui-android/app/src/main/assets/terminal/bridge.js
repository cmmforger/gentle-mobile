// The WebView side of the xterm.js bridge (T61).
// Kotlin drives this page through `window.gm.receive(json)` (init/write/theme/
// cursor/focus); the terminal reports back through `window.AndroidBridge`
// (onData/onResize/onReady).
(function () {
  'use strict';

  var term = null;
  var fitAddon = null;

  function ensureFont(css) {
    if (!css) return;
    var style = document.getElementById('gm-font');
    if (!style) {
      style = document.createElement('style');
      style.id = 'gm-font';
      document.head.appendChild(style);
    }
    style.textContent = css;
  }

  function createTerminal(config) {
    ensureFont(config.font);
    term = new Terminal({
      scrollback: 2000,
      cursorStyle: config.cursor || 'block',
      cursorBlink: !!config.blink,
      fontFamily: 'gentle-terminal, monospace',
      fontSize: 14,
      theme: config.theme || {}
    });
    fitAddon = new FitAddon.FitAddon();
    term.loadAddon(fitAddon);
    term.loadAddon(new WebLinksAddon.WebLinksAddon());
    term.open(document.getElementById('terminal'));

    term.onData(function (data) {
      var bytes = new TextEncoder().encode(data);
      var binary = '';
      for (var i = 0; i < bytes.length; i++) {
        binary += String.fromCharCode(bytes[i]);
      }
      try {
        window.AndroidBridge.onData(btoa(binary));
      } catch (e) {}
    });

    term.onResize(function (size) {
      try {
        window.AndroidBridge.onResize(size.cols, size.rows);
      } catch (e) {}
    });
  }

  function sizeContainer() {
    var el = document.getElementById('terminal');
    el.style.width = window.innerWidth + 'px';
    el.style.height = window.innerHeight + 'px';
  }

  function fitNow() {
    sizeContainer();
    if (fitAddon && term) {
      try { fitAddon.fit(); } catch (e) {}
    }
  }

  function observeResize() {
    window.addEventListener('resize', fitNow);
    if (window.visualViewport) {
      window.visualViewport.addEventListener('resize', fitNow);
    }
    if (typeof ResizeObserver !== 'undefined') {
      new ResizeObserver(function () { fitNow(); })
        .observe(document.getElementById('terminal'));
    }
  }

  function initTerminal(config) {
    if (term) {
      ensureFont(config.font);
      term.options.theme = config.theme || {};
      term.options.cursorStyle = config.cursor || 'block';
      term.options.cursorBlink = !!config.blink;
      term.reset();
      fitNow();
      return;
    }
    createTerminal(config);
    observeResize();
    fitNow();
    try {
      window.AndroidBridge.onReady();
    } catch (e) {}
  }

  function writeBytes(b64) {
    if (!term) return;
    var binary = atob(b64);
    var bytes = new Uint8Array(binary.length);
    for (var i = 0; i < binary.length; i++) {
      bytes[i] = binary.charCodeAt(i);
    }
    term.write(bytes);
  }

  window.gm = {
    receive: function (message) {
      if (!message) return;
      switch (message.type) {
        case 'init':
          initTerminal(message);
          break;
        case 'write':
          writeBytes(message.data);
          break;
        case 'theme':
          if (term) term.options.theme = message.theme;
          break;
        case 'cursor':
          if (term) {
            term.options.cursorStyle = message.cursor;
            term.options.cursorBlink = !!message.blink;
          }
          break;
        case 'focus':
          if (term) term.focus();
          break;
      }
    },

    inspect: function () {
      if (!term) return '{}';
      var buffer = term.buffer.active;
      var lines = [];
      for (var i = 0; i < buffer.length; i++) {
        var line = buffer.getLine(i);
        lines.push(line ? line.translateToString(true) : '');
      }
      return JSON.stringify({
        cols: term.cols,
        rows: term.rows,
        bufferType: buffer.type,
        buffer: lines,
        theme: term.options.theme,
        cursorStyle: term.options.cursorStyle,
        cursorBlink: term.options.cursorBlink
      });
    }
  };
})();
