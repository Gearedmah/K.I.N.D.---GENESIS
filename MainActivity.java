package org.kindradio.app;

import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.mediarouter.app.MediaRouteButton;
import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaLoadRequestData;
import com.google.android.gms.cast.framework.CastButtonFactory;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.CastSession;
import com.google.android.gms.cast.framework.media.RemoteMediaClient;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;

/** One queue, one controller: local MediaPlayer OR the existing Google Cast transport. */
public class MainActivity extends AppCompatActivity {
    private static final int PICK_AUDIO = 7;
    private final ArrayList<Uri> tracks = new ArrayList<>();
    private final ArrayList<String> names = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private AudioServer server;
    private CastContext cast;
    private MediaPlayer player;
    private WebView web;
    private TextView status;
    private int index = -1;
    private boolean prepared = false;
    private boolean autoPlayWhenPrepared = false;
    private boolean webReady = false;
    private boolean wasCasting = false;
    private String message = "Choose your music to begin.";

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF101923);
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(14), dp(8), dp(14), dp(8));
        TextView title = new TextView(this);
        title.setText("K.I.N.D.   RADIO / HOME");
        title.setTextColor(Color.WHITE);
        title.setTextSize(19);
        bar.addView(title, new LinearLayout.LayoutParams(0, dp(55), 1));
        MediaRouteButton route = new MediaRouteButton(this);
        bar.addView(route, new LinearLayout.LayoutParams(dp(64), dp(55)));
        root.addView(bar);
        status = new TextView(this);
        status.setTextColor(0xFFB6D4C0);
        status.setTextSize(13);
        status.setPadding(dp(16), dp(3), dp(16), dp(8));
        status.setText("Output: This device · Choose audio or connect Cast");
        root.addView(status);
        web = new WebView(this);
        web.setBackgroundColor(0xFF101923);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setAllowFileAccess(false);
        web.addJavascriptInterface(new Controls(), "KindAndroid");
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                webReady = true;
                pushState();
            }
        });
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        try {
            server = new AudioServer(this);
            server.start();
            cast = CastContext.getSharedInstance(this);
            CastButtonFactory.setUpMediaRouteButton(getApplicationContext(), route);
        } catch (Exception ex) { message = "Cast initialization: " + ex.getMessage(); }
        web.loadUrl("file:///android_asset/kind_radio.html");
        handler.postDelayed(tick, 600);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            boolean connected = remote() != null;
            if (connected && !wasCasting && player != null) releaseLocal();
            if (!connected && wasCasting && index >= 0) choose(index, false);
            wasCasting = connected;
            pushState();
            handler.postDelayed(this, 600);
        }
    };
    public class Controls {
        @JavascriptInterface public void pick() { runOnUiThread(() -> pickAudio()); }
        @JavascriptInterface public void command(String name) { runOnUiThread(() -> dispatch(name)); }
        @JavascriptInterface public void select(int i) { runOnUiThread(() -> choose(i, true)); }
        @JavascriptInterface public void seek(int seconds) { runOnUiThread(() -> seekTo(seconds)); }
    }
    private void pickAudio() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("audio/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, PICK_AUDIO);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_AUDIO || result != RESULT_OK || data == null) return;
        if (data.getClipData() != null) {
            for (int j = 0; j < data.getClipData().getItemCount(); j++) add(data.getClipData().getItemAt(j).getUri());
        } else if (data.getData() != null) add(data.getData());
        if (index < 0 && !tracks.isEmpty()) choose(0, false);
        pushState();
    }
    private void add(Uri uri) {
        try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
        catch (Exception ignored) { /* Current document selection still grants temporary access. */ }
        String name = uri.getLastPathSegment();
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) name = c.getString(0);
        } catch (Exception ignored) { }
        tracks.add(uri);
        names.add(name == null ? "Audio " + tracks.size() : name);
    }
    private RemoteMediaClient remote() {
        if (cast == null) return null;
        CastSession s = cast.getSessionManager().getCurrentCastSession();
        return s != null && s.isConnected() ? s.getRemoteMediaClient() : null;
    }
    private void releaseLocal() {
        if (player != null) { try { player.reset(); player.release(); } catch (Exception ignored) {} player = null; }
        prepared = false;
    }
    private void choose(int i, boolean play) {
        if (i < 0 || i >= tracks.size()) return;
        index = i;
        releaseLocal();
        if (server != null) server.setSong(tracks.get(index));
        if (remote() != null) {
            if (play) castSelected();
            else { RemoteMediaClient client = remote(); if (client != null) client.stop(); }
            message = play ? "Casting " + names.get(index) : "Selected " + names.get(index);
        } else {
            try {
                MediaPlayer p = new MediaPlayer();
                player = p;
                autoPlayWhenPrepared = play;
                p.setDataSource(this, tracks.get(index));
                p.setOnPreparedListener(mp -> {
                    if (player != mp) return;
                    prepared = true;
                    if (autoPlayWhenPrepared) mp.start();
                    pushState();
                });
                p.setOnCompletionListener(mp -> {
                    if (index + 1 < tracks.size()) choose(index + 1, true); else stop();
                });
                p.setOnErrorListener((mp, what, extra) -> { message = "Unable to play this audio file."; pushState(); return true; });
                p.prepareAsync();
                message = "Loading " + names.get(index);
            } catch (Exception ex) { message = "Cannot open audio: " + ex.getMessage(); releaseLocal(); }
        }
        pushState();
    }
    private void castSelected() {
        if (index < 0 || server == null) return;
        RemoteMediaClient r = remote();
        if (r == null) { message = "Choose a Cast receiver first."; return; }
        String ip = localIp();
        if (ip == null) { message = "No reachable local Wi-Fi address."; return; }
        releaseLocal();
        server.setSong(tracks.get(index));
        String mime = getContentResolver().getType(tracks.get(index));
        if (mime == null || !mime.startsWith("audio/")) mime = "audio/mpeg";
        MediaInfo media = new MediaInfo.Builder("http://" + ip + ":8765/audio")
                .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED).setContentType(mime).build();
        r.load(new MediaLoadRequestData.Builder().setMediaInfo(media).setAutoplay(true).build());
        message = "Casting " + names.get(index);
    }
    private String localIp() {
        try {
            Enumeration<NetworkInterface> all = NetworkInterface.getNetworkInterfaces();
            while (all.hasMoreElements()) {
                NetworkInterface net = all.nextElement();
                if (!net.isUp() || net.isLoopback()) continue;
                Enumeration<InetAddress> ips = net.getInetAddresses();
                while (ips.hasMoreElements()) {
                    InetAddress ip = ips.nextElement();
                    if (ip instanceof Inet4Address && ip.isSiteLocalAddress() && !ip.isLoopbackAddress())
                        return ip.getHostAddress();
                }
            }
        } catch (Exception ignored) { }
        return null;
    }
    private void dispatch(String cmd) {
        switch (cmd) {
            case "play":
                if (index < 0) { message = "Add your music first."; break; }
                if (remote() != null) {
                    RemoteMediaClient r = remote();
                    if (r.isPaused()) r.play(); else castSelected();
                } else if (player == null) choose(index, true);
                else if (prepared) player.start();
                else autoPlayWhenPrepared = true;
                break;
            case "pause":
                if (remote() != null) remote().pause();
                else if (prepared && player != null && player.isPlaying()) player.pause();
                break;
            case "next": if (!tracks.isEmpty()) choose((index + 1) % tracks.size(), true); break;
            case "prev": if (!tracks.isEmpty()) choose((index - 1 + tracks.size()) % tracks.size(), true); break;
            case "stop": stop(); break;
            case "clear":
                stop(); releaseLocal(); tracks.clear(); names.clear(); index = -1;
                message = "Queue cleared."; break;
        }
        pushState();
    }
    private void stop() {
        RemoteMediaClient r = remote();
        if (r != null) r.stop();
        if (player != null && prepared) { player.pause(); player.seekTo(0); }
        autoPlayWhenPrepared = false;
        message = "Stopped";
    }
    private void seekTo(int seconds) {
        RemoteMediaClient r = remote();
        if (r != null) r.seek(new com.google.android.gms.cast.MediaSeekOptions.Builder().setPosition(seconds * 1000L).build());
        else if (prepared && player != null) player.seekTo(seconds * 1000);
    }
    private void pushState() {
        if (!webReady || web == null) return;
        try {
            JSONObject state = new JSONObject();
            JSONArray list = new JSONArray();
            for (String name : names) list.put(name);
            state.put("queue", list);
            state.put("index", index);
            RemoteMediaClient r = remote();
            boolean casting = r != null;
            boolean playing = casting ? r.isPlaying() : player != null && prepared && player.isPlaying();
            long position = casting ? r.getApproximateStreamPosition() : prepared && player != null ? player.getCurrentPosition() : 0;
            long duration = casting ? r.getStreamDuration() : prepared && player != null ? player.getDuration() : 0;
            state.put("playing", playing);
            state.put("position", Math.max(0, position / 1000));
            state.put("duration", Math.max(0, duration / 1000));
            state.put("output", casting ? "Cast receiver" : "This device");
            state.put("message", message);
            status.setText("Output: " + (casting ? "Cast receiver" : "This device") + "   ·   " + tracks.size() + " song(s)");
            web.evaluateJavascript("window.kindUpdate(" + state.toString() + ")", null);
        } catch (Exception ignored) { }
    }
    @Override protected void onDestroy() {
        handler.removeCallbacks(tick);
        releaseLocal();
        if (server != null) server.stop();
        if (web != null) { web.removeJavascriptInterface("KindAndroid"); web.destroy(); }
        super.onDestroy();
    }
}
