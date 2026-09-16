package com.operit.resourcehub;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
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
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

public class MainActivity extends Activity {
    private WebView webView;

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

        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient());
        webView.loadUrl("file:///android_asset/index.html");
    }

    public static class WebAppInterface {
        private final Context context;
        private File currentTempFile = null;
        private FileOutputStream currentFos = null;

        public WebAppInterface(Context context) {
            this.context = context;
        }

        // ==================== 256KB 分片流式写入核心接口 ====================
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
                        String outName = (filename != null && !filename.isEmpty()) ? filename : ("ResourceHub_Backup_" + System.currentTimeMillis() + ".zip");
                        saveFileToPublicDownload(currentTempFile, outName, "application/zip");
                        currentTempFile.delete();
                        currentTempFile = null;
                        postToast("🎉 备份已成功写入：Download/" + outName);
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

        @JavascriptInterface
        public void saveBase64File(String arg1, String arg2, String mimeType) {
            String base64Data = arg1.length() > arg2.length() ? arg1 : arg2;
            String filename = arg1.length() > arg2.length() ? arg2 : arg1;
            try {
                String clean = base64Data.contains(",") ? base64Data.substring(base64Data.indexOf(",") + 1) : base64Data;
                byte[] bytes = Base64.decode(clean, Base64.DEFAULT);
                saveDirectBytes(bytes, filename, mimeType != null ? mimeType : "application/octet-stream");
            } catch (Exception e) {
                postToast("❌ 保存失败: " + e.getMessage());
            }
        }

        @JavascriptInterface
        public void saveBase64File(String arg1, String arg2) {
            saveBase64File(arg1, arg2, "application/octet-stream");
        }

        @JavascriptInterface
        public void showToast(String msg) {
            postToast(msg);
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
            java.io.FileInputStream fis = new java.io.FileInputStream(sourceFile);

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
