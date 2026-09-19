(function () {
  try{console.log("[RH_DL] hook script executed, proto="+location.protocol+" href="+location.href.slice(0,60));}catch(e){}
  // 下载劫持：接管 a.download / Blob / data: 下载，转交原生写盘。
  // 自动覆盖主页面及所有同源 iframe（含 v2 工坊）。
  function findBridge(win) {
    try {
      if (win.AndroidApp && win.AndroidApp.saveBlobChunk) return win.AndroidApp;
    } catch (e) {}
    try {
      if (window.AndroidApp && window.AndroidApp.saveBlobChunk) return window.AndroidApp;
    } catch (e) {}
    try {
      if (window.top && window.top.AndroidApp && window.top.AndroidApp.saveBlobChunk) return window.top.AndroidApp;
    } catch (e) {}
    return null;
  }

  function toB64(buf) {
    var b = new Uint8Array(buf), s = '', C = 0x8000;
    for (var i = 0; i < b.length; i += C) s += String.fromCharCode.apply(null, b.subarray(i, i + C));
    return btoa(s);
  }

  function save(blob, name) {
    var AA = findBridge(window);
    if (!AA) { return false; }
    var r = new FileReader();
    r.onload = function () {
      try {
        var b64 = toB64(r.result), CH = 512 * 1024, i = 0, first = true;
        (function step() {
          var c = b64.slice(i, i + CH); i += CH;
          var last = (i >= b64.length);
          AA.saveBlobChunk(c, first, last, name);
          first = false;
          if (!last) setTimeout(step, 0);
        })();
      } catch (e) { try { AA.showToast('下载失败:' + e.message); } catch (_) {} }
    };
    r.onerror = function () { try { AA.showToast('读取失败'); } catch (_) {} };
    r.readAsArrayBuffer(blob);
    return true;
  }

  function saveB64(b64, name) {
    var AA = findBridge(window);
    if (!AA) return false;
    var CH = 512 * 1024, i = 0, first = true;
    (function step() {
      var c = b64.slice(i, i + CH); i += CH;
      var last = (i >= b64.length);
      AA.saveBlobChunk(c, first, last, name);
      first = false;
      if (!last) setTimeout(step, 0);
    })();
    return true;
  }

  function hookWin(win) {
    try {
      if (!win || win.__dlHooked) return;
      win.__dlHooked = true;
      win.__blobMap = win.__blobMap || {};

      var oc = win.URL.createObjectURL;
      win.URL.createObjectURL = function (o) {
        var u = oc.call(win.URL, o);
        try { if (o instanceof win.Blob) win.__blobMap[u] = o; } catch (e) {}
        return u;
      };

      // 劫持程序化 click（v2 用 document.createElement('a').click()）
      try {
        var AC = win.HTMLAnchorElement.prototype.click;
        win.HTMLAnchorElement.prototype.click = function () {
          try {
            var href = this.getAttribute('href') || '';
            var name = this.getAttribute('download') || ('download_' + Date.now());
            if (href.indexOf('blob:') === 0) {
              var b = win.__blobMap[href];
              if (b && save(b, name)) return;
            } else if (href.indexOf('data:') === 0) {
              var idx = href.indexOf(',');
              if (saveB64(href.slice(idx + 1), name)) return;
            }
          } catch (e) {}
          return AC.apply(this, arguments);
        };
      } catch (e) {}

      // 兜底：捕获真实点击事件
      win.document.addEventListener('click', function (ev) {
        var a = ev.target && ev.target.closest ? ev.target.closest('a[download]') : null;
        if (!a) return;
        var href = a.getAttribute('href') || '';
        var name = a.getAttribute('download') || ('download_' + Date.now());
        try {
          if (href.indexOf('blob:') === 0) {
            var b = win.__blobMap[href];
            if (b && save(b, name)) { ev.preventDefault(); ev.stopPropagation(); return; }
          } else if (href.indexOf('data:') === 0) {
            var idx = href.indexOf(',');
            if (saveB64(href.slice(idx + 1), name)) { ev.preventDefault(); ev.stopPropagation(); return; }
          }
        } catch (e) {}
      }, true);
    } catch (e) {}
  }

  // 注入自身
  hookWin(window);

  // 持续扫描同源 iframe 并注入（v2 工坊 iframe 是后加载的）
  function scan() {
    try {
      var frames = document.querySelectorAll('iframe');
      if(frames.length){try{console.log("[RH_DL] scan found "+frames.length+" iframes");}catch(e){}}
      for (var i = 0; i < frames.length; i++) {
        try { hookWin(frames[i].contentWindow); console.log("[RH_DL] hooked iframe "+i); } catch (e) { try{console.log("[RH_DL] iframe "+i+" err:"+e.message);}catch(_){} }
      }
    } catch (e) {}
  }
  scan();
  setInterval(scan, 1500);
})();
