package com.operit.resourcehub;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.widget.Toast;
import android.content.SharedPreferences;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private WebView webView;
    private ValueCallback<Uri[]> uploadMessage;
    private final static int FILE_CHOOSER_RESULT_CODE = 10000;

    // ================= 资源热更新 =================
    // 网页资源（ui.js / tools / vendor 等）可通过远端 web_bundle.zip 热更新，
    // 无需重装 APK。热更新包解压到 files/web/，WebView 优先加载它；
    // 若不存在或损坏则回退到 APK 内置 assets/（保证离线可用）。
    private static final String REMOTE_BASE = "https://tavern-card-hub.vercel.app/hotupdate";
    private static final String VERSION_URL = REMOTE_BASE + "/web_version.json";
    private static final String BUNDLE_URL = REMOTE_BASE + "/web_bundle.zip";

    // ================= 下载劫持脚本 =================
    // WebView 不处理 a.download / blob: 下载，导致点击「导出/下载」静默失败。
    // 注入脚本：接管 <a download> 与 Blob/data: 下载，转 base64 分片交原生写盘。
    private static final String DOWNLOAD_HOOK_JS =
        "(function(){" +
        "if(window.__dlHooked)return;window.__dlHooked=true;" +
        "function toB64(buf){var b=new Uint8Array(buf),s='',C=0x8000;for(var i=0;i<b.length;i+=C){s+=String.fromCharCode.apply(null,b.subarray(i,i+C));}return btoa(s);}" +
        "function save(blob,name){" +
        "if(!window.AndroidApp||!window.AndroidApp.saveBlobChunk)return false;" +
        "var r=new FileReader();" +
        "r.onload=function(){try{var b64=toB64(r.result);var CH=512*1024;var i=0;var first=true;" +
        "function step(){var c=b64.slice(i,i+CH);i+=CH;var last=(i>=b64.length);" +
        "window.AndroidApp.saveBlobChunk(c,first,last,name);first=false;if(!last)setTimeout(step,0);}" +
        "step();}catch(e){try{window.AndroidApp.showToast('下载失败:'+e.message);}catch(_){}}};" +
        "r.onerror=function(){try{window.AndroidApp.showToast('读取失败');}catch(_){}};" +
        "r.readAsArrayBuffer(blob);return true;}" +
        "window.__blobMap=window.__blobMap||{};" +
        "var oc=URL.createObjectURL;" +
        "URL.createObjectURL=function(o){var u=oc.call(URL,o);try{if(o instanceof Blob){window.__blobMap[u]=o;}}catch(e){}return u;};" +
        "var oc2=HTMLAnchorElement.prototype.click;" +
        "HTMLAnchorElement.prototype.click=function(){" +
        "try{var href=this.getAttribute('href')||'';var name=this.getAttribute('download')||('download_'+Date.now());" +
        "if(href.indexOf('blob:')===0){var b=window.__blobMap[href];if(b&&save(b,name)){return;}}" +
        "else if(href.indexOf('data:')===0){var idx=href.indexOf(',');var b64=href.slice(idx+1);" +
        "var CH=512*1024;var i=0;var first=true;" +
        "function st(){var c=b64.slice(i,i+CH);i+=CH;var last=(i>=b64.length);" +
        "window.AndroidApp.saveBlobChunk(c,first,last,name);first=false;if(!last)setTimeout(st,0);}" +
        "st();return;}}catch(e){}" +
        "return oc2.apply(this,arguments);};" +
        "document.addEventListener('click',function(ev){" +
        "var a=ev.target&&ev.target.closest?ev.target.closest('a[download]'):null;if(!a)return;" +
        "var href=a.getAttribute('href')||'';var name=a.getAttribute('download')||('download_'+Date.now());" +
        "try{if(href.indexOf('blob:')===0){var b=window.__blobMap[href];" +
        "if(b&&save(b,name)){ev.preventDefault();ev.stopPropagation();return;}}" +
        "else if(href.indexOf('data:')===0){var idx=href.indexOf(',');var b64=href.slice(idx+1);" +
        "var CH=512*1024;var i=0;var first=true;" +
        "function st(){var c=b64.slice(i,i+CH);i+=CH;var last=(i>=b64.length);" +
        "window.AndroidApp.saveBlobChunk(c,first,last,name);first=false;if(!last)setTimeout(st,0);}" +
        "st();ev.preventDefault();ev.stopPropagation();return;}}catch(e){}" +
        "},true);})();";

    @Override
    @SuppressLint("SetJavaScriptEnabled")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 全屏沉浸式无黑条
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.setStatusBarColor(0x00000000);
            window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            );
        }

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);

        WebAppInterface bridge = new WebAppInterface(this);
        webView.addJavascriptInterface(bridge, "AndroidApp");
        webView.addJavascriptInterface(bridge, "AndroidDownload");

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (uploadMessage != null) {
                    uploadMessage.onReceiveValue(null);
                    uploadMessage = null;
                }
                uploadMessage = filePathCallback;
                // WebView's createIntent() is not reliable for multiple files on
                // some Android picker implementations. Build an explicit
                // ACTION_OPEN_DOCUMENT intent and force multi-select.
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try {
                    startActivityForResult(intent, FILE_CHOOSER_RESULT_CODE);
                } catch (Exception e) {
                    uploadMessage = null;
                    Toast.makeText(MainActivity.appContext != null ? MainActivity.appContext : MainActivity.this, "无法打开文件选择器: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    return false;
                }
                return true;
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return interceptAsset(request.getUrl().toString());
            }

            @Override
            @SuppressWarnings("deprecation")
            public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                return interceptAsset(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // 注入下载劫持脚本（脚本内部会持续扫描并覆盖所有同源 iframe）
                String js = getHookJs();
                android.util.Log.i("RH_DL", "onPageFinished url=" + url + " hookLen=" + js.length());
                view.evaluateJavascript(js, null);
            }
        });

        // WebView 默认不处理 blob: 下载，导致「点了没反应」。此监听兜底 http(s) 直链。
        webView.setDownloadListener(new android.webkit.DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimeType, long contentLength) {
                if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                    try {
                        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                    } catch (Exception e) {
                        Toast.makeText(MainActivity.this, "无法打开下载: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                }
            }
        });
        webView.loadUrl("file:///android_asset/index.html");

        // 启动后异步检查资源热更新（不阻塞界面，失败静默）
        new Thread(new Runnable() {
            @Override
            public void run() {
                try { checkAndApplyUpdate(); } catch (Exception ignored) {}
            }
        }).start();
    }

    // 从 assets 读取下载劫持脚本（比 Java 内联字符串更易维护）
    private String getHookJs() {
        try {
            java.io.InputStream is = getAssets().open("download_hook.js");
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
            is.close();
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return DOWNLOAD_HOOK_JS;
        }
    }

    // ================= 资源热更新实现 =================

    /** 热更新资源的本地根目录：files/web/ */
    private File webRoot() {
        File dir = new File(getFilesDir(), "web");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /**
     * 拦截 file:///android_asset/xxx 请求：
     * 若 files/web/xxx 存在（热更新过），优先返回它；否则返回 null 走 APK 内置资源。
     */
    private WebResourceResponse interceptAsset(String url) {
        try {
            final String prefix = "file:///android_asset/";
            if (url == null || !url.startsWith(prefix)) return null;
            String rel = url.substring(prefix.length());
            int q = rel.indexOf('?');
            if (q >= 0) rel = rel.substring(0, q);
            File f = new File(webRoot(), rel);
            if (f.exists() && f.isFile()) {
                InputStream is = new BufferedInputStream(new FileInputStream(f));
                return new WebResourceResponse(guessMime(rel), "UTF-8", is);
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String guessMime(String path) {
        String p = path.toLowerCase();
        if (p.endsWith(".html") || p.endsWith(".htm")) return "text/html";
        if (p.endsWith(".js") || p.endsWith(".mjs")) return "application/javascript";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".json")) return "application/json";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        if (p.endsWith(".gif")) return "image/gif";
        if (p.endsWith(".webp")) return "image/webp";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".woff")) return "font/woff";
        if (p.endsWith(".woff2")) return "font/woff2";
        if (p.endsWith(".ttf")) return "font/ttf";
        if (p.endsWith(".mp3")) return "audio/mpeg";
        if (p.endsWith(".mp4")) return "video/mp4";
        return "application/octet-stream";
    }

    /** 读取远端 web_version.json，若 version 高于本地则下载并应用 */
    private void checkAndApplyUpdate() {
        HttpURLConnection conn = null;
        try {
            SharedPreferences sp = getSharedPreferences("hotupdate", MODE_PRIVATE);
            long localVer = sp.getLong("web_version", 0);

            URL u = new URL(VERSION_URL + "?t=" + System.currentTimeMillis());
            conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("Cache-Control", "no-cache");
            int code = conn.getResponseCode();
            if (code != 200) return;
            String body = readAll(conn.getInputStream());
            JSONObject obj = new JSONObject(body);
            long remoteVer = obj.optLong("version", 0);
            String remoteMd5 = obj.optString("md5", "").toLowerCase();
            if (remoteVer <= localVer) return; // 已是最新

            // 下载 bundle
            File tmpZip = new File(getCacheDir(), "web_bundle_" + remoteVer + ".zip");
            if (!downloadTo(BUNDLE_URL + "?t=" + System.currentTimeMillis(), tmpZip)) return;

            // 校验 md5
            if (!remoteMd5.isEmpty()) {
                String got = md5(tmpZip);
                if (!got.equalsIgnoreCase(remoteMd5)) {
                    tmpZip.delete();
                    return; // 校验失败，丢弃
                }
            }

            // 解压到临时目录，成功后原子替换
            File staging = new File(getCacheDir(), "web_staging_" + remoteVer);
            deleteRecursive(staging);
            staging.mkdirs();
            if (!unzip(tmpZip, staging)) { tmpZip.delete(); deleteRecursive(staging); return; }

            // 校验必须含 index.html，否则视为坏包
            if (!new File(staging, "index.html").exists()) {
                tmpZip.delete(); deleteRecursive(staging); return;
            }

            // 替换 files/web/
            File target = webRoot();
            File backup = new File(getCacheDir(), "web_backup");
            deleteRecursive(backup);
            if (target.exists()) target.renameTo(backup);
            if (staging.renameTo(target)) {
                sp.edit().putLong("web_version", remoteVer).apply();
                deleteRecursive(backup);
            } else {
                // 替换失败，回滚
                if (backup.exists()) backup.renameTo(target);
            }
            tmpZip.delete();
            deleteRecursive(staging);
        } catch (Exception ignored) {
            // 静默失败，保持旧版
        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Exception ignored) {}
        }
    }

    private static String readAll(InputStream is) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
        is.close();
        return new String(bos.toByteArray(), "UTF-8");
    }

    private static boolean downloadTo(String urlStr, File dest) {
        HttpURLConnection c = null;
        try {
            URL u = new URL(urlStr);
            c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("Cache-Control", "no-cache");
            if (c.getResponseCode() != 200) return false;
            InputStream is = new BufferedInputStream(c.getInputStream());
            FileOutputStream fos = new FileOutputStream(dest);
            byte[] buf = new byte[65536];
            int n;
            while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
            fos.flush();
            fos.close();
            is.close();
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) try { c.disconnect(); } catch (Exception ignored) {}
        }
    }

    private static String md5(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        FileInputStream fis = new FileInputStream(f);
        byte[] buf = new byte[65536];
        int n;
        while ((n = fis.read(buf)) != -1) md.update(buf, 0, n);
        fis.close();
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static boolean unzip(File zip, File outDir) {
        ZipFile zf = null;
        try {
            zf = new ZipFile(zip);
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String name = e.getName();
                if (name.contains("..")) continue; // 防路径穿越
                File out = new File(outDir, name);
                if (e.isDirectory()) {
                    out.mkdirs();
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                InputStream is = zf.getInputStream(e);
                FileOutputStream fos = new FileOutputStream(out);
                byte[] buf = new byte[65536];
                int n;
                while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
                fos.flush();
                fos.close();
                is.close();
            }
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (zf != null) try { zf.close(); } catch (Exception ignored) {}
        }
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        f.delete();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);
        if (requestCode == FILE_CHOOSER_RESULT_CODE) {
            if (uploadMessage == null) return;
            Uri[] results = null;
            if (resultCode == RESULT_OK && intent != null) {
                // 先取 ClipData：部分 OEM（如 vivo）多选时仍会填充 intent.data，
                // 若先判断 dataString 会导致多选被截断成单个文件。
                ClipData clip = intent.getClipData();
                if (clip != null && clip.getItemCount() > 0) {
                    int count = clip.getItemCount();
                    results = new Uri[count];
                    for (int i = 0; i < count; i++) {
                        results[i] = clip.getItemAt(i).getUri();
                    }
                } else {
                    String dataString = intent.getDataString();
                    if (dataString != null) {
                        results = new Uri[]{Uri.parse(dataString)};
                    }
                }
            }
            uploadMessage.onReceiveValue(results);
            uploadMessage = null;
        }
    }

    public static Context appContext;

    {
        appContext = this;
    }

    public static class WebAppInterface {
        private final Context context;
        private File currentTempFile = null;
        private FileOutputStream currentFos = null;

        public WebAppInterface(Context context) {
            this.context = context;
        }

        // ================= 原生 ZIP 流式导出（大文件专用） =================
        // JSZip 必须把整个压缩包堆在内存，数百 MB 时必然 OOM。
        // 改为 JS 逐条调用 zipStart / zipAddFile / zipFinish，Java 边收边写盘，
        // 内存占用恒定（仅当前条目），可支持数百 MB 乃至 GB 级备份。
        private ZipOutputStream zipOutStream = null;
        private File zipTempFile = null;
        private String zipOutName = null;
        private int zipEntryCount = 0;

        @JavascriptInterface
        public synchronized boolean zipStart(String filename) {
            try {
                zipClose();
                File cacheDir = context.getCacheDir();
                zipTempFile = new File(cacheDir, "export_zip_" + System.currentTimeMillis() + ".tmp");
                if (zipTempFile.exists()) zipTempFile.delete();
                zipOutStream = new ZipOutputStream(new java.io.BufferedOutputStream(new FileOutputStream(zipTempFile), 65536));
                zipOutName = (filename != null && !filename.isEmpty()) ? filename : ("ResourceHub_Backup_" + System.currentTimeMillis() + ".zip");
                zipEntryCount = 0;
                return true;
            } catch (Exception e) {
                postToast("zipStart failed: " + e.getMessage());
                zipClose();
                return false;
            }
        }

        // 以 Base64 传入一条文件的完整内容，写入当前 ZIP（STORED 模式，需自算 CRC32）
        @JavascriptInterface
        public synchronized boolean zipAddFile(String entryName, String contentBase64) {
            if (zipOutStream == null) return false;
            try {
                if (entryName == null || entryName.isEmpty()) return false;
                String clean = (contentBase64 != null && contentBase64.contains(","))
                        ? contentBase64.substring(contentBase64.indexOf(",") + 1) : contentBase64;
                byte[] data = (clean == null || clean.isEmpty()) ? new byte[0] : Base64.decode(clean, Base64.DEFAULT);
                ZipEntry entry = new ZipEntry(entryName);
                entry.setMethod(ZipEntry.STORED);
                entry.setSize(data.length);
                entry.setCompressedSize(data.length);
                java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                crc.update(data, 0, data.length);
                entry.setCrc(crc.getValue());
                zipOutStream.putNextEntry(entry);
                zipOutStream.write(data);
                zipOutStream.closeEntry();
                zipEntryCount++;
                return true;
            } catch (Exception e) {
                postToast("zipAddFile failed[" + entryName + "]: " + e.getMessage());
                return false;
            }
        }

        @JavascriptInterface
        public synchronized boolean zipFinish() {
            try {
                if (zipOutStream == null) return false;
                zipOutStream.finish();
                zipOutStream.flush();
                zipOutStream.close();
                zipOutStream = null;
                if (zipTempFile == null || !zipTempFile.exists()) return false;
                final File tempToSave = zipTempFile;
                final String outName = zipOutName;
                final int total = zipEntryCount;
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            saveFileToPublicDownload(tempToSave, outName, "application/zip");
                            tempToSave.delete();
                            postToast("备份已写入 Download/" + outName + " (" + total + " entries)");
                        } catch (Exception e) {
                            postToast("save failed: " + e.getMessage());
                        }
                    }
                }).start();
                zipTempFile = null;
                zipOutName = null;
                zipEntryCount = 0;
                return true;
            } catch (Exception e) {
                postToast("zipFinish failed: " + e.getMessage());
                zipClose();
                return false;
            }
        }

        @JavascriptInterface
        public synchronized void zipClose() {
            try { if (zipOutStream != null) zipOutStream.close(); } catch (Exception ignored) {}
            zipOutStream = null;
            try { if (zipTempFile != null && zipTempFile.exists()) zipTempFile.delete(); } catch (Exception ignored) {}
            zipTempFile = null;
            zipOutName = null;
            zipEntryCount = 0;
        }

        @JavascriptInterface
        public synchronized boolean writeChunk(String chunkBase64, boolean isFirstChunk, boolean isLastChunk, String filename) {
            try {
                if (isFirstChunk) {
                    if (currentFos != null) {
                        try { currentFos.close(); } catch (Exception ignored) {}
                    }
                    File cacheDir = context.getCacheDir();
                    currentTempFile = new File(cacheDir, "export_temp_" + System.currentTimeMillis() + ".tmp");
                    if (currentTempFile.exists()) currentTempFile.delete();
                    currentFos = new FileOutputStream(currentTempFile, true);
                }

                if (chunkBase64 != null && !chunkBase64.isEmpty()) {
                    String clean = chunkBase64.contains(",") ? chunkBase64.substring(chunkBase64.indexOf(",") + 1) : chunkBase64;
                    byte[] bytes = Base64.decode(clean, Base64.DEFAULT);
                    if (currentFos != null) {
                        currentFos.write(bytes);
                        currentFos.flush();
                    }
                }

                if (isLastChunk) {
                    if (currentFos != null) {
                        currentFos.close();
                        currentFos = null;
                    }
                    if (currentTempFile != null && currentTempFile.exists()) {
                        final File tempToSave = currentTempFile;
                        final String outName = (filename != null && !filename.isEmpty()) ? filename : ("ResourceHub_Backup_" + System.currentTimeMillis() + ".zip");
                        
                        // 异步线程落盘，绝不卡死主线程
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    saveFileToPublicDownload(tempToSave, outName, "application/zip");
                                    tempToSave.delete();
                                    postToast("🎉 备份已成功写入：Download/" + outName);
                                } catch (Exception e) {
                                    postToast("❌ 落盘失败: " + e.getMessage());
                                }
                            }
                        }).start();

                        currentTempFile = null;
                    }
                }
                return true;
            } catch (Exception e) {
                postToast("❌ 写入分片失败: " + e.getMessage());
                if (currentFos != null) {
                    try { currentFos.close(); } catch (Exception ignored) {}
                    currentFos = null;
                }
                return false;
            }
        }
        // ================= Blob 分片下载（供页面下载劫持脚本调用） =================
        private File blobTempFile = null;
        private FileOutputStream blobFos = null;
        private String blobName = null;

        @JavascriptInterface
        public synchronized boolean saveBlobChunk(String chunkBase64, boolean isFirst, boolean isLast, String filename) {
            try {
                if (isFirst) {
                    if (blobFos != null) { try { blobFos.close(); } catch (Exception ignored) {} }
                    File cacheDir = context.getCacheDir();
                    blobTempFile = new File(cacheDir, "dl_" + System.currentTimeMillis() + ".tmp");
                    if (blobTempFile.exists()) blobTempFile.delete();
                    blobFos = new FileOutputStream(blobTempFile, true);
                    blobName = (filename != null && !filename.isEmpty()) ? filename : ("download_" + System.currentTimeMillis());
                }
                if (chunkBase64 != null && !chunkBase64.isEmpty()) {
                    String clean = chunkBase64.contains(",") ? chunkBase64.substring(chunkBase64.indexOf(",") + 1) : chunkBase64;
                    byte[] bytes = Base64.decode(clean, Base64.DEFAULT);
                    if (blobFos != null) { blobFos.write(bytes); blobFos.flush(); }
                }
                if (isLast) {
                    if (blobFos != null) { blobFos.close(); blobFos = null; }
                    if (blobTempFile != null && blobTempFile.exists()) {
                        final File src = blobTempFile;
                        final String outName = blobName;
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    saveFileToPublicDownload(src, outName, guessMime(outName));
                                    src.delete();
                                    postToast("✅ 已保存到 Download/" + outName);
                                } catch (Exception e) {
                                    postToast("❌ 保存失败: " + e.getMessage());
                                }
                            }
                        }).start();
                        blobTempFile = null;
                    }
                }
                return true;
            } catch (Exception e) {
                postToast("❌ 下载分片失败: " + e.getMessage());
                if (blobFos != null) { try { blobFos.close(); } catch (Exception ignored) {} blobFos = null; }
                return false;
            }
        }

        @JavascriptInterface
        public void saveBase64File(String arg1, String arg2, String mimeType) {
            final String base64Data = arg1.length() > arg2.length() ? arg1 : arg2;
            final String filename = arg1.length() > arg2.length() ? arg2 : arg1;
            final String mType = mimeType != null ? mimeType : "application/octet-stream";

            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        String clean = base64Data.contains(",") ? base64Data.substring(base64Data.indexOf(",") + 1) : base64Data;
                        byte[] bytes = Base64.decode(clean, Base64.DEFAULT);
                        saveDirectBytes(bytes, filename, mType);
                    } catch (Exception e) {
                        postToast("❌ 保存失败: " + e.getMessage());
                    }
                }
            }).start();
        }

        @JavascriptInterface
        public void saveBase64File(String arg1, String arg2) {
            saveBase64File(arg1, arg2, "application/octet-stream");
        }

        @JavascriptInterface
        public void showToast(String msg) {
            postToast(msg);
        }

        @JavascriptInterface
        public void copyText(final String text) {
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    try {
                        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                        if (clipboard == null) throw new IllegalStateException("剪贴板不可用");
                        clipboard.setPrimaryClip(ClipData.newPlainText("ResourceHub", text != null ? text : ""));
                        postToast("✅ 已复制到剪贴板");
                    } catch (Exception e) {
                        postToast("❌ 复制失败: " + e.getMessage());
                    }
                }
            });
        }

        private void saveDirectBytes(byte[] bytes, String filename, String mimeType) throws Exception {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, filename);
                values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
                values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);

                Uri uri = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    OutputStream os = context.getContentResolver().openOutputStream(uri);
                    if (os != null) {
                        os.write(bytes);
                        os.flush();
                        os.close();
                    }
                }
            } else {
                File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!downloadsDir.exists()) downloadsDir.mkdirs();
                File dest = new File(downloadsDir, filename);
                FileOutputStream fos = new FileOutputStream(dest);
                fos.write(bytes);
                fos.flush();
                fos.close();
                MediaScannerConnection.scanFile(context, new String[]{dest.getAbsolutePath()}, null, null);
            }
            postToast("✅ 已保存至：Download/" + filename);
        }

        private void saveFileToPublicDownload(File sourceFile, String filename, String mimeType) throws Exception {
            byte[] buffer = new byte[16384];
            FileInputStream fis = new FileInputStream(sourceFile);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, filename);
                values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
                values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);

                Uri uri = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    OutputStream os = context.getContentResolver().openOutputStream(uri);
                    if (os != null) {
                        int len;
                        while ((len = fis.read(buffer)) != -1) {
                            os.write(buffer, 0, len);
                        }
                        os.flush();
                        os.close();
                    }
                }
            } else {
                File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!downloadsDir.exists()) downloadsDir.mkdirs();
                File dest = new File(downloadsDir, filename);
                FileOutputStream fos = new FileOutputStream(dest);
                int len;
                while ((len = fis.read(buffer)) != -1) {
                    fos.write(buffer, 0, len);
                }
                fos.flush();
                fos.close();
                MediaScannerConnection.scanFile(context, new String[]{dest.getAbsolutePath()}, null, null);
            }
            fis.close();
        }

        private void postToast(final String text) {
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
