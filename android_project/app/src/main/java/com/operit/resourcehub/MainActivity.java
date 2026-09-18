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
import android.widget.Toast;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class MainActivity extends Activity {
    private WebView webView;
    private ValueCallback<Uri[]> uploadMessage;
    private final static int FILE_CHOOSER_RESULT_CODE = 10000;

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

        webView.setWebViewClient(new WebViewClient());
        webView.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);
        if (requestCode == FILE_CHOOSER_RESULT_CODE) {
            if (uploadMessage == null) return;
            Uri[] results = null;
            if (resultCode == RESULT_OK && intent != null) {
                String dataString = intent.getDataString();
                if (dataString != null) {
                    results = new Uri[]{Uri.parse(dataString)};
                } else if (intent.getClipData() != null) {
                    int count = intent.getClipData().getItemCount();
                    results = new Uri[count];
                    for (int i = 0; i < count; i++) {
                        results[i] = intent.getClipData().getItemAt(i).getUri();
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
