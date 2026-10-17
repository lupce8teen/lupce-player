package org.lupce.player;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * LUPCE Player for Android.
 * Shows the same player page as the computer version (assets/www/index.html).
 * All recordings live inside the app's own private storage (files/rec/).
 * The same code makes two apps: "LUPCE Part A" (org.lupce.player) and
 * "LUPCE Part B" (org.lupce.player.partb) — each keeps its own lessons.
 */
public class MainActivity extends Activity {

    private static final int PICK_TREE = 4201;
    private static final int PICK_AUDIO = 4202;
    private static final int PICK_DOCX = 4203;
    private static final int PICK_BUNDLE = 4204;
    private static final int SAVE_BUNDLE = 4205;

    private static final String HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + HOST + "/app/index.html";
    private static final String META = "recmeta";

    private WebView web;
    private final Handler main = new Handler(Looper.getMainLooper());

    // waiting picker results
    private String pendingAudioKey = null;
    private String pendingManifest = null;
    private String pendingFileName = null;
    private Uri pendingBundle = null;
    private Uri externalFile = null;      // a lessons file opened from WhatsApp / Files ("Open with LUPCE")
    private boolean pageReady = false;
    private final List<String> docxNames = new ArrayList<>();
    private final List<String> docxXml = new ArrayList<>();

    // audio
    private MediaPlayer player;
    private boolean prepared = false;
    private boolean playWhenReady = false;
    private int pendingSeek = -1;
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (player != null && prepared) {
                try {
                    sendAudio("time", player.getCurrentPosition(), player.getDuration());
                } catch (Exception ignored) { }
            }
            main.postDelayed(this, 250);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= 21) w.setStatusBarColor(Color.parseColor("#0f6e6a"));

        cleanShareDir();

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#fbf6ec"));
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setTextZoom(100);

        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return serveAsset(request.getUrl());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                openExternalFile();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                return !HOST.equals(u.getHost()); // never leave the app
            }
        });
        web.addJavascriptInterface(new Bridge(), "LupceAndroid");
        web.loadUrl(START_URL);
        takeFileFrom(getIntent());
    }

    /* ---------- a lessons file opened from another app (WhatsApp, Files …) ---------- */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        takeFileFrom(intent);
        openExternalFile();
    }

    private void takeFileFrom(Intent intent) {
        if (intent == null) return;
        Uri u = null;
        if (Intent.ACTION_VIEW.equals(intent.getAction())) u = intent.getData();
        else if (Intent.ACTION_SEND.equals(intent.getAction())) {
            Object o = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (o instanceof Uri) u = (Uri) o;
        }
        if (u != null) {
            externalFile = u;
            setIntent(new Intent(Intent.ACTION_MAIN)); // do not open it twice
        }
    }

    private void openExternalFile() {
        if (!pageReady || externalFile == null) return;
        final Uri u = externalFile;
        externalFile = null;
        pendingBundle = u;
        busy("Getting the lessons ready…");
        new Thread(() -> readBundleInfo(u, true)).start();
    }

    /* ---------- the page is served from the app's own files ---------- */
    private WebResourceResponse serveAsset(Uri url) {
        if (url == null || !HOST.equals(url.getHost())) return null;
        String path = url.getPath();
        if (path == null || !path.startsWith("/app/")) return null;
        String file = path.substring(5);
        if (file.isEmpty() || file.contains("..")) file = "index.html";
        try {
            InputStream in = getAssets().open("www/" + file);
            String mime = file.endsWith(".html") ? "text/html"
                    : file.endsWith(".js") ? "application/javascript"
                    : file.endsWith(".css") ? "text/css"
                    : file.endsWith(".png") ? "image/png" : "application/octet-stream";
            return new WebResourceResponse(mime, "utf-8", in);
        } catch (Exception e) {
            return null;
        }
    }

    /* ---------- which Part this app is (from its package name) ---------- */
    private String partLetter() {
        String p = getPackageName();
        int i = p.lastIndexOf(".part");
        if (i >= 0 && p.length() == i + 6) return p.substring(i + 5).toUpperCase(Locale.ROOT);
        return "A";
    }

    /* ---------- calls from the page ---------- */
    private class Bridge {
        @JavascriptInterface public String partId() { return partLetter(); }

        // small text files (the lesson list)
        @JavascriptInterface public String loadText(String name) {
            File f = new File(getFilesDir(), safe(name));
            if (!f.exists()) return "";
            try (InputStream in = new FileInputStream(f)) {
                return new String(readAll(in), StandardCharsets.UTF_8);
            } catch (Exception e) { return ""; }
        }
        @JavascriptInterface public boolean saveText(String name, String text) {
            File f = new File(getFilesDir(), safe(name));
            if (text == null || text.isEmpty()) { f.delete(); return true; }
            File tmp = new File(getFilesDir(), safe(name) + ".tmp");
            try (OutputStream out = new FileOutputStream(tmp)) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) { tmp.delete(); return false; }
            f.delete();
            return tmp.renameTo(f);
        }

        // recordings
        @JavascriptInterface public String listRecs() { return listRecordings(); }
        @JavascriptInterface public void removeRec(String key) { deleteRec(key); }
        @JavascriptInterface public void clearRecs() {
            File[] fs = recDir().listFiles();
            if (fs != null) for (File f : fs) f.delete();
            getSharedPreferences(META, MODE_PRIVATE).edit().clear().apply();
        }
        @JavascriptInterface public void pickAudio(String key) {
            main.post(() -> {
                pendingAudioKey = key;
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"audio/*", "video/mp4", "video/3gpp", "application/ogg", "application/octet-stream"});
                try { startActivityForResult(i, PICK_AUDIO); }
                catch (Exception e) { recDone(key, false, "This phone could not open the file chooser."); }
            });
        }

        // playing
        @JavascriptInterface public void load(String src) { main.post(() -> loadAudio(src)); }
        @JavascriptInterface public void play() { main.post(MainActivity.this::playAudio); }
        @JavascriptInterface public void pause() { main.post(MainActivity.this::pauseAudio); }
        @JavascriptInterface public void stop() { main.post(MainActivity.this::releaseAudio); }
        @JavascriptInterface public void seek(int ms) { main.post(() -> seekAudio(ms)); }

        // share / backup
        @JavascriptInterface public void exportBundle(String manifestJson, String fileName, boolean send) {
            main.post(() -> startExport(manifestJson, fileName, send));
        }
        @JavascriptInterface public void importBundle() {
            main.post(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try { startActivityForResult(i, PICK_BUNDLE); }
                catch (Exception e) { sendError("This phone could not open the file chooser."); }
            });
        }
        @JavascriptInterface public void importBundleGo(boolean go) {
            final Uri u = pendingBundle;
            if (!go || u == null) { pendingBundle = null; return; }
            new Thread(() -> runImport(u)).start();
        }

        // lesson list from Word files
        @JavascriptInterface public void pickDocx() {
            main.post(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        "application/msword", "application/octet-stream", "application/zip"});
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try { startActivityForResult(i, PICK_DOCX); }
                catch (Exception e) { sendError("This phone could not open the file chooser."); }
            });
        }
        @JavascriptInterface public String docxName(int i) { return i >= 0 && i < docxNames.size() ? docxNames.get(i) : ""; }
        @JavascriptInterface public String docxXml(int i) { return i >= 0 && i < docxXml.size() ? docxXml.get(i) : ""; }

        // bring in from the old LUPCE folder
        @JavascriptInterface public void pickOldFolder() {
            main.post(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try { startActivityForResult(i, PICK_TREE); }
                catch (Exception e) { sendError("This phone could not open the folder chooser."); }
            });
        }
        @JavascriptInterface public void copyMany(String json) {
            new Thread(() -> runCopyMany(json)).start();
        }
    }

    /* ---------- picker results ---------- */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        boolean ok = resultCode == RESULT_OK && data != null;
        switch (requestCode) {
            case PICK_AUDIO: {
                final String key = pendingAudioKey;
                pendingAudioKey = null;
                if (key == null) return;
                if (!ok || data.getData() == null) { recDone(key, false, ""); return; }
                final Uri u = data.getData();
                busy("Saving the recording…");
                new Thread(() -> saveRecording(key, u)).start();
                break;
            }
            case PICK_DOCX: {
                if (!ok) return;
                final List<Uri> uris = new ArrayList<>();
                ClipData cd = data.getClipData();
                if (cd != null) for (int i = 0; i < cd.getItemCount(); i++) uris.add(cd.getItemAt(i).getUri());
                else if (data.getData() != null) uris.add(data.getData());
                if (uris.isEmpty()) return;
                busy("Reading the Word files…");
                new Thread(() -> readDocx(uris)).start();
                break;
            }
            case PICK_BUNDLE: {
                if (!ok || data.getData() == null) return;
                pendingBundle = data.getData();
                busy("Opening the file…");
                final Uri u = pendingBundle;
                new Thread(() -> readBundleInfo(u, false)).start();
                break;
            }
            case SAVE_BUNDLE: {
                final String m = pendingManifest;
                pendingManifest = null;
                if (!ok || data.getData() == null || m == null) { exportDone(false, ""); return; }
                final Uri u = data.getData();
                new Thread(() -> {
                    try (OutputStream out = getContentResolver().openOutputStream(u)) {
                        writeBundle(m, out);
                        exportDone(true, "The lessons file is saved. Keep it safe — it is your backup.");
                    } catch (Exception e) {
                        exportDone(false, "The file could not be saved. The phone may be out of space.");
                    }
                }).start();
                break;
            }
            case PICK_TREE: {
                if (!ok || data.getData() == null) return;
                final Uri tree = data.getData();
                busy("Reading the folder…");
                new Thread(() -> scanFolder(tree)).start();
                break;
            }
        }
    }

    /* ---------- recordings on disk ---------- */
    private File recDir() {
        File d = new File(getFilesDir(), "rec");
        if (!d.exists()) d.mkdirs();
        return d;
    }
    private static String safe(String s) {
        return s == null ? "x" : s.replaceAll("[^A-Za-z0-9._-]", "_");
    }
    private File findRec(String key) {
        String k = safe(key);
        File[] fs = recDir().listFiles();
        if (fs != null) for (File f : fs) {
            String n = f.getName();
            if (n.startsWith(k + ".") && n.indexOf('.', k.length() + 1) < 0 && !n.startsWith(".")) return f;
        }
        return null;
    }
    private void deleteRec(String key) {
        String k = safe(key);
        File[] fs = recDir().listFiles();
        if (fs != null) for (File f : fs) {
            String n = f.getName();
            if (n.startsWith(k + ".") && n.indexOf('.', k.length() + 1) < 0) f.delete();
        }
        getSharedPreferences(META, MODE_PRIVATE).edit().remove(key).apply();
    }
    private String listRecordings() {
        JSONObject out = new JSONObject();
        SharedPreferences sp = getSharedPreferences(META, MODE_PRIVATE);
        File[] fs = recDir().listFiles();
        if (fs != null) for (File f : fs) {
            String n = f.getName();
            int dot = n.lastIndexOf('.');
            if (dot <= 0 || n.startsWith(".")) continue;
            String key = n.substring(0, dot), ext = n.substring(dot + 1);
            try {
                JSONObject o;
                String m = sp.getString(key, null);
                o = m != null ? new JSONObject(m) : new JSONObject();
                o.put("size", f.length());
                o.put("ext", ext);
                out.put(key, o);
            } catch (Exception ignored) { }
        }
        return out.toString();
    }
    /** Put a finished temporary file in place as the recording for this lesson. */
    private void installRec(String key, File tmp, String ext, String name, double seconds) {
        deleteRec(key);
        File dest = new File(recDir(), safe(key) + "." + safe(ext).toLowerCase(Locale.ROOT));
        if (!tmp.renameTo(dest)) { tmp.delete(); return; }
        try {
            JSONObject o = new JSONObject();
            o.put("name", name == null ? "" : name);
            o.put("d", seconds);
            o.put("date", System.currentTimeMillis());
            getSharedPreferences(META, MODE_PRIVATE).edit().putString(key, o.toString()).apply();
        } catch (Exception ignored) { }
    }
    private String displayNameOf(Uri u) {
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) { }
        return null;
    }
    private String extFor(String name, String mime) {
        if (name != null) {
            int dot = name.lastIndexOf('.');
            if (dot > 0 && name.length() - dot <= 6) return name.substring(dot + 1).toLowerCase(Locale.ROOT);
        }
        if (mime != null) {
            String e = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
            if (e != null) return e;
        }
        return "mp3";
    }
    /** Length of an audio file in seconds, or -1 if Android cannot read it. */
    private double durationOf(File f) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try (FileInputStream in = new FileInputStream(f)) {
            r.setDataSource(in.getFD());
            String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            String hasAudio = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO);
            if (d == null && hasAudio == null) return -1;
            return d == null ? 0 : Long.parseLong(d) / 1000.0;
        } catch (Exception e) {
            return -1;
        } finally {
            try { r.release(); } catch (Exception ignored) { }
        }
    }
    private void copyStream(InputStream in, File dest) throws Exception {
        try (InputStream bin = new BufferedInputStream(in); OutputStream out = new BufferedOutputStream(new FileOutputStream(dest))) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = bin.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    private void saveRecording(String key, Uri u) {
        File tmp = new File(recDir(), ".tmp_" + safe(key));
        try {
            String name = displayNameOf(u);
            String ext = extFor(name, getContentResolver().getType(u));
            InputStream in = getContentResolver().openInputStream(u);
            if (in == null) throw new FileNotFoundException();
            copyStream(in, tmp);
            double d = durationOf(tmp);
            if (d < 0) {
                tmp.delete();
                recDone(key, false, "\"" + (name == null ? "This file" : name) + "\" could not be played on this phone. Please choose an MP3 or M4A file.");
                return;
            }
            installRec(key, tmp, ext, name, d);
            recDone(key, true, "");
        } catch (Exception e) {
            tmp.delete();
            recDone(key, false, "The recording could not be saved. The phone may be out of space.");
        }
    }

    /* ---------- share / backup file (a zip: lupce.json + audio files) ---------- */
    private File shareDir() { File d = new File(getCacheDir(), "share"); if (!d.exists()) d.mkdirs(); return d; }
    private void cleanShareDir() {
        File[] fs = shareDir().listFiles();
        if (fs != null) for (File f : fs) f.delete();
    }
    private void startExport(String manifest, String fileName, boolean send) {
        if (!send) {
            pendingManifest = manifest;
            pendingFileName = fileName;
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/octet-stream");
            i.putExtra(Intent.EXTRA_TITLE, fileName);
            try { startActivityForResult(i, SAVE_BUNDLE); }
            catch (Exception e) { pendingManifest = null; exportDone(false, "This phone could not open the save window."); }
            return;
        }
        new Thread(() -> {
            cleanShareDir();
            String clean = fileName.replaceAll("[\\\\/:*?\"<>|]", " ");
            File f = new File(shareDir(), clean);
            try (OutputStream out = new FileOutputStream(f)) {
                writeBundle(manifest, out);
            } catch (Exception e) {
                f.delete();
                exportDone(false, "The file could not be made. The phone may be out of space.");
                return;
            }
            main.post(() -> {
                Uri u = new Uri.Builder().scheme("content").authority(getPackageName() + ".share").appendPath(clean).build();
                Intent s = new Intent(Intent.ACTION_SEND);
                s.setType("application/octet-stream");
                s.putExtra(Intent.EXTRA_STREAM, u);
                s.putExtra(Intent.EXTRA_SUBJECT, clean);
                s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {
                    startActivity(Intent.createChooser(s, "Send lessons file"));
                    exportDone(true, "Now choose WhatsApp (or another app) and send the file.");
                } catch (Exception e) {
                    exportDone(false, "No app was found to send the file.");
                }
            });
        }).start();
    }
    private void writeBundle(String manifest, OutputStream rawOut) throws Exception {
        JSONObject m = new JSONObject(manifest);
        JSONObject recs = m.optJSONObject("recordings");
        if (recs == null) recs = new JSONObject();
        List<String> keys = new ArrayList<>();
        Iterator<String> it = recs.keys();
        while (it.hasNext()) keys.add(it.next());
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(rawOut, 256 * 1024))) {
            zip.setMethod(ZipOutputStream.STORED);
            byte[] mj = manifest.getBytes(StandardCharsets.UTF_8);
            CRC32 c0 = new CRC32();
            c0.update(mj);
            ZipEntry e0 = new ZipEntry("lupce.json");
            e0.setMethod(ZipEntry.STORED);
            e0.setSize(mj.length);
            e0.setCompressedSize(mj.length);
            e0.setCrc(c0.getValue());
            zip.putNextEntry(e0);
            zip.write(mj);
            zip.closeEntry();
            byte[] buf = new byte[64 * 1024];
            int done = 0;
            for (String key : keys) {
                File f = findRec(key);
                String path = recs.getJSONObject(key).optString("file", "");
                if (f == null || path.isEmpty()) continue;
                CRC32 crc = new CRC32();
                try (InputStream in = new BufferedInputStream(new FileInputStream(f))) {
                    int n;
                    while ((n = in.read(buf)) > 0) crc.update(buf, 0, n);
                }
                ZipEntry e = new ZipEntry(path);
                e.setMethod(ZipEntry.STORED);
                e.setSize(f.length());
                e.setCompressedSize(f.length());
                e.setCrc(crc.getValue());
                zip.putNextEntry(e);
                try (InputStream in = new BufferedInputStream(new FileInputStream(f))) {
                    int n;
                    while ((n = in.read(buf)) > 0) zip.write(buf, 0, n);
                }
                zip.closeEntry();
                done++;
                runJs("window.__lupceExportProgress && window.__lupceExportProgress(" + done + "," + keys.size() + ")");
            }
        }
    }
    private void readBundleInfo(Uri u, boolean external) {
        try (InputStream raw = getContentResolver().openInputStream(u);
             ZipInputStream zip = new ZipInputStream(new BufferedInputStream(raw))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if ("lupce.json".equals(e.getName())) {
                    String json = new String(readAll(zip), StandardCharsets.UTF_8);
                    runJs("window.__lupceBundleInfo && window.__lupceBundleInfo(" + jsString(json) + "," + external + ")");
                    return;
                }
            }
            pendingBundle = null;
            sendError("This is not a LUPCE lessons file.");
        } catch (Exception ex) {
            pendingBundle = null;
            sendError("This file could not be opened. Is it a LUPCE lessons file?");
        }
    }
    private void runImport(Uri u) {
        pendingBundle = null;
        Map<String, String> byFile = new HashMap<>();
        Map<String, JSONObject> info = new HashMap<>();
        int count = 0;
        try {
            // 1) read the list again, to know which audio file is which lesson
            try (InputStream raw = getContentResolver().openInputStream(u);
                 ZipInputStream zip = new ZipInputStream(new BufferedInputStream(raw))) {
                ZipEntry e;
                while ((e = zip.getNextEntry()) != null) {
                    if ("lupce.json".equals(e.getName())) {
                        JSONObject m = new JSONObject(new String(readAll(zip), StandardCharsets.UTF_8));
                        JSONObject recs = m.optJSONObject("recordings");
                        if (recs != null) {
                            Iterator<String> it = recs.keys();
                            while (it.hasNext()) {
                                String k = it.next();
                                JSONObject r = recs.getJSONObject(k);
                                byFile.put(r.optString("file", ""), k);
                                info.put(k, r);
                            }
                        }
                        break;
                    }
                }
            }
            // 2) copy every audio file into its lesson
            int total = byFile.size();
            try (InputStream raw = getContentResolver().openInputStream(u);
                 ZipInputStream zip = new ZipInputStream(new BufferedInputStream(raw, 256 * 1024))) {
                ZipEntry e;
                byte[] buf = new byte[64 * 1024];
                while ((e = zip.getNextEntry()) != null) {
                    String key = byFile.get(e.getName());
                    if (key == null || e.isDirectory()) continue;
                    File tmp = new File(recDir(), ".tmp_" + safe(key));
                    try (OutputStream out = new BufferedOutputStream(new FileOutputStream(tmp))) {
                        int n;
                        while ((n = zip.read(buf)) > 0) out.write(buf, 0, n);
                    }
                    JSONObject r = info.get(key);
                    String name = r == null ? "" : r.optString("name", "");
                    double d = r == null ? 0 : r.optDouble("d", 0);
                    installRec(key, tmp, extFor(e.getName(), null), name, d);
                    count++;
                    runJs("window.__lupceImportProgress && window.__lupceImportProgress(" + count + "," + total + ")");
                }
            }
            runJs("window.__lupceImportDone && window.__lupceImportDone(true," + count + ",'')");
        } catch (Exception ex) {
            runJs("window.__lupceImportDone && window.__lupceImportDone(" + (count > 0) + "," + count + ","
                    + jsString("The file could not be read completely. The phone may be out of space.") + ")");
        }
    }

    /* ---------- Word files: read word/document.xml from each ---------- */
    private void readDocx(List<Uri> uris) {
        docxNames.clear();
        docxXml.clear();
        for (Uri u : uris) {
            String name = displayNameOf(u);
            String xml = "";
            try (InputStream raw = getContentResolver().openInputStream(u);
                 ZipInputStream zip = new ZipInputStream(new BufferedInputStream(raw))) {
                ZipEntry e;
                while ((e = zip.getNextEntry()) != null) {
                    if ("word/document.xml".equals(e.getName())) {
                        xml = new String(readAll(zip), StandardCharsets.UTF_8);
                        break;
                    }
                }
            } catch (Exception ignored) { }
            docxNames.add(name == null ? "file" : name);
            docxXml.add(xml);
        }
        runJs("window.__lupceDocxReady && window.__lupceDocxReady(" + docxNames.size() + ")");
    }

    /* ---------- old LUPCE folder ---------- */
    private void scanFolder(final Uri tree) {
        try {
            JSONArray out = new JSONArray();
            String rootId = DocumentsContract.getTreeDocumentId(tree);
            String rootName = treeName(tree, rootId);
            String root = rootName == null ? "LUPCE" : rootName;
            walk(tree, rootId, root + "/", out, 0);
            runJs("window.__lupceOldFiles && window.__lupceOldFiles(" + jsSafe(out.toString()) + ")");
        } catch (Exception e) {
            sendError("The folder could not be read. Please choose it again.");
        }
    }
    private String treeName(Uri tree, String docId) {
        Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, docId);
        try (Cursor c = getContentResolver().query(doc,
                new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) { }
        return null;
    }
    private void walk(Uri tree, String docId, String prefix, JSONArray out, int depth) throws Exception {
        if (depth > 6) return;
        ContentResolver cr = getContentResolver();
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        String[] cols = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
        };
        try (Cursor c = cr.query(children, cols, null, null, null)) {
            if (c == null) return;
            while (c.moveToNext()) {
                String id = c.getString(0);
                String name = c.getString(1);
                String mime = c.getString(2);
                if (name == null) continue;
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    walk(tree, id, prefix + name + "/", out, depth + 1);
                } else if (isAudio(name)) {
                    JSONObject o = new JSONObject();
                    o.put("p", prefix + name);
                    o.put("u", DocumentsContract.buildDocumentUriUsingTree(tree, id).toString());
                    out.put(o);
                }
            }
        }
    }
    private static boolean isAudio(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".mp3") || n.endsWith(".m4a") || n.endsWith(".aac") || n.endsWith(".wav")
                || n.endsWith(".ogg") || n.endsWith(".oga") || n.endsWith(".opus") || n.endsWith(".mp4")
                || n.endsWith(".webm") || n.endsWith(".flac");
    }
    private void runCopyMany(String json) {
        int ok = 0, bad = 0;
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                String key = o.getString("k");
                String name = o.optString("n", "");
                File tmp = new File(recDir(), ".tmp_" + safe(key));
                try {
                    InputStream in = getContentResolver().openInputStream(Uri.parse(o.getString("u")));
                    if (in == null) throw new FileNotFoundException();
                    copyStream(in, tmp);
                    double d = durationOf(tmp);
                    installRec(key, tmp, extFor(name, null), name, Math.max(0, d));
                    ok++;
                } catch (Exception e) {
                    tmp.delete();
                    bad++;
                }
                runJs("window.__lupceCopyProgress && window.__lupceCopyProgress(" + (i + 1) + "," + a.length() + ")");
            }
        } catch (Exception ignored) { }
        runJs("window.__lupceCopyDone && window.__lupceCopyDone(" + ok + "," + bad + ")");
    }

    /* ---------- audio ---------- */
    private void loadAudio(String src) {
        releaseAudio();
        prepared = false;
        playWhenReady = false;
        pendingSeek = -1;
        final MediaPlayer mp = new MediaPlayer();
        player = mp;
        try {
            if (Build.VERSION.SDK_INT >= 21) {
                mp.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build());
            }
            mp.setOnPreparedListener(m -> {
                if (player != m) return;
                prepared = true;
                sendAudio("meta", m.getDuration(), 0);
                if (pendingSeek >= 0) { m.seekTo(pendingSeek); pendingSeek = -1; }
                if (playWhenReady) { playWhenReady = false; startNow(); }
            });
            mp.setOnCompletionListener(m -> {
                if (player != m) return;
                main.removeCallbacks(ticker);
                int d = m.getDuration();
                sendAudio("time", d, d);
                sendAudio("ended", 0, 0);
            });
            mp.setOnErrorListener((m, what, extra) -> {
                if (player != m) return true;
                main.removeCallbacks(ticker);
                prepared = false;
                sendAudio("error", what, extra);
                return true;
            });
            if (src != null && src.startsWith("rec:")) {
                File f = findRec(src.substring(4));
                if (f == null) throw new FileNotFoundException();
                try (FileInputStream in = new FileInputStream(f)) {
                    mp.setDataSource(in.getFD());
                }
            } else {
                mp.setDataSource(this, Uri.parse(src));
            }
            mp.prepareAsync();
        } catch (Exception e) {
            sendAudio("error", 0, 0);
        }
    }

    private void startNow() {
        if (player == null) return;
        try {
            player.start();
            sendAudio("play", 0, 0);
            main.removeCallbacks(ticker);
            main.post(ticker);
        } catch (Exception e) {
            sendAudio("error", 0, 0);
        }
    }

    private void playAudio() {
        if (player == null) { sendAudio("error", 0, 0); return; }
        if (prepared) startNow(); else playWhenReady = true;
    }

    private void pauseAudio() {
        playWhenReady = false;
        main.removeCallbacks(ticker);
        if (player != null && prepared) {
            try { if (player.isPlaying()) player.pause(); } catch (Exception ignored) { }
        }
        sendAudio("pause", 0, 0);
    }

    private void seekAudio(int ms) {
        if (player == null) return;
        if (prepared) {
            try {
                player.seekTo(Math.max(0, ms));
                sendAudio("time", ms, player.getDuration());
            } catch (Exception ignored) { }
        } else {
            pendingSeek = Math.max(0, ms);
        }
    }

    private void releaseAudio() {
        main.removeCallbacks(ticker);
        if (player != null) {
            MediaPlayer old = player;
            player = null;
            try { old.reset(); } catch (Exception ignored) { }
            try { old.release(); } catch (Exception ignored) { }
        }
        prepared = false;
        playWhenReady = false;
    }

    /* ---------- talking back to the page ---------- */
    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[32 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toByteArray();
    }
    private static String jsSafe(String s) {
        return s.replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
    }
    private static String jsString(String s) {
        return jsSafe(JSONObject.quote(s));
    }
    private void sendAudio(String ev, int a, int b) {
        runJs("window.__lupceAudio && window.__lupceAudio('" + ev + "'," + a + "," + b + ")");
    }
    private void recDone(String key, boolean ok, String msg) {
        runJs("window.__lupceRecDone && window.__lupceRecDone(" + jsString(key) + "," + ok + "," + jsString(msg) + ")");
    }
    private void exportDone(boolean ok, String msg) {
        runJs("window.__lupceExportDone && window.__lupceExportDone(" + ok + "," + jsString(msg) + ")");
    }
    private void busy(String msg) {
        runJs("window.__lupceBusy && window.__lupceBusy(" + jsString(msg) + ")");
    }
    private void sendError(String msg) {
        runJs("window.__lupceNativeError && window.__lupceNativeError(" + jsString(msg) + ")");
    }
    private void runJs(final String js) {
        main.post(() -> {
            if (web != null) web.evaluateJavascript(js, null);
        });
    }

    @Override
    public void onBackPressed() {
        if (web == null) { super.onBackPressed(); return; }
        web.evaluateJavascript("(window.__lupceBack && window.__lupceBack()) ? 'yes' : 'no'", v -> {
            if (v == null || !v.contains("yes")) MainActivity.super.onBackPressed();
        });
    }

    @Override
    protected void onDestroy() {
        releaseAudio();
        if (web != null) {
            web.removeJavascriptInterface("LupceAndroid");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    /**
     * Lets WhatsApp (or any app) read the lessons file we made, for sending.
     * Only files in the app's cache/share folder can be read, and only when we hand them over.
     */
    public static class ShareProvider extends ContentProvider {
        private File fileFor(Uri uri) throws FileNotFoundException {
            String name = uri.getLastPathSegment();
            if (name == null || name.contains("/") || name.contains("..")) throw new FileNotFoundException();
            File f = new File(new File(getContext().getCacheDir(), "share"), name);
            if (!f.exists()) throw new FileNotFoundException();
            return f;
        }
        @Override public boolean onCreate() { return true; }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
            try {
                File f = fileFor(uri);
                String[] cols = projection != null ? projection : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
                MatrixCursor c = new MatrixCursor(cols);
                Object[] row = new Object[cols.length];
                for (int i = 0; i < cols.length; i++) {
                    if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = f.getName();
                    else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.length();
                }
                c.addRow(row);
                return c;
            } catch (Exception e) { return null; }
        }
        @Override public String getType(Uri uri) { return "application/octet-stream"; }
        @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
            return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY);
        }
        @Override public Uri insert(Uri uri, ContentValues values) { return null; }
        @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
    }
}
