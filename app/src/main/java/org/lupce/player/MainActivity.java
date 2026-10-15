package org.lupce.player;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.database.Cursor;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.view.View;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.Locale;

/**
 * LUPCE Player for Android.
 * Shows the same player page as the computer version (assets/www/index.html)
 * and gives it two native helpers: choosing the audio folder on the memory card,
 * and playing the audio files.
 */
public class MainActivity extends Activity {

    private static final int PICK_FOLDER = 4201;
    private static final String HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + HOST + "/app/index.html";
    private static final String PREFS = "lupce";
    private static final String KEY_FOLDER = "folderUri";

    private WebView web;
    private final Handler main = new Handler(Looper.getMainLooper());

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
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                return !HOST.equals(u.getHost()); // never leave the app
            }
        });
        web.addJavascriptInterface(new Bridge(), "LupceAndroid");
        web.loadUrl(START_URL);
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

    /* ---------- calls from the page ---------- */
    private class Bridge {
        @JavascriptInterface
        public boolean hasSavedFolder() {
            return savedFolder() != null;
        }

        @JavascriptInterface
        public void pickFolder() {
            main.post(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                try {
                    startActivityForResult(i, PICK_FOLDER);
                } catch (Exception e) {
                    sendError("This phone could not open the folder chooser.");
                }
            });
        }

        @JavascriptInterface
        public void openSaved() {
            Uri u = savedFolder();
            if (u == null) {
                sendError("The saved folder could not be opened (maybe the memory card is not in the phone). Please choose the LUPCE folder again.");
                return;
            }
            scanFolder(u);
        }

        @JavascriptInterface public void load(String uri) { main.post(() -> loadAudio(uri)); }
        @JavascriptInterface public void play() { main.post(MainActivity.this::playAudio); }
        @JavascriptInterface public void pause() { main.post(MainActivity.this::pauseAudio); }
        @JavascriptInterface public void stop() { main.post(MainActivity.this::releaseAudio); }
        @JavascriptInterface public void seek(int ms) { main.post(() -> seekAudio(ms)); }
    }

    /* ---------- folder ---------- */
    private Uri savedFolder() {
        String s = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_FOLDER, null);
        if (s == null) return null;
        Uri u = Uri.parse(s);
        for (UriPermission p : getContentResolver().getPersistedUriPermissions()) {
            if (p.getUri().equals(u) && p.isReadPermission()) return u;
        }
        return null;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_FOLDER) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return; // user went back
        Uri tree = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) { }
        SharedPreferences.Editor ed = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
        ed.putString(KEY_FOLDER, tree.toString());
        ed.apply();
        scanFolder(tree);
    }

    private void scanFolder(final Uri tree) {
        runJs("window.__lupceNativeBusy && window.__lupceNativeBusy()");
        new Thread(() -> {
            try {
                JSONArray out = new JSONArray();
                String rootId = DocumentsContract.getTreeDocumentId(tree);
                String rootName = displayName(tree, rootId);
                String root = rootName == null ? "LUPCE" : rootName;
                walk(tree, rootId, root + "/", out, 0);
                String json = out.toString().replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
                runJs("window.__lupceNativeFiles(" + json + "," + JSONObject.quote(root) + ")");
            } catch (Exception e) {
                sendError("The folder could not be read. Please choose the LUPCE folder again.");
            }
        }).start();
    }

    private String displayName(Uri tree, String docId) {
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
                || n.endsWith(".webm") || n.endsWith(".flac") || n.endsWith(".wma") || n.endsWith(".amr")
                || n.endsWith(".3gp") || n.endsWith(".3ga");
    }

    /* ---------- audio ---------- */
    private void loadAudio(String uri) {
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
            mp.setDataSource(this, Uri.parse(uri));
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
    private void sendAudio(String ev, int a, int b) {
        runJs("window.__lupceAudio && window.__lupceAudio('" + ev + "'," + a + "," + b + ")");
    }

    private void sendError(String msg) {
        runJs("window.__lupceNativeError && window.__lupceNativeError(" + JSONObject.quote(msg) + ")");
    }

    private void runJs(final String js) {
        main.post(() -> {
            if (web != null) web.evaluateJavascript(js, null);
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
}
