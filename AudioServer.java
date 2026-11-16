package org.kindradio.app;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/** Serves the selected SAF document to a receiver on the same reachable network. */
class AudioServer {
    private final Context context;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private ServerSocket server;
    private volatile Uri song;
    AudioServer(Context context) { this.context = context; }
    void setSong(Uri uri) { song = uri; }
    void start() throws IOException {
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(8765));
        pool.execute(() -> {
            while (!server.isClosed()) {
                try { Socket socket = server.accept(); pool.execute(() -> serve(socket)); }
                catch (IOException e) { break; }
            }
        });
    }
    void stop() {
        try { if (server != null) server.close(); } catch (IOException ignored) { }
        pool.shutdownNow();
    }
    private static void write(OutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.US_ASCII));
    }
    private long size(Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri,
                new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getLong(0);
        } catch (Exception ignored) { }
        return -1;
    }
    private static long parseStart(String range) {
        if (range == null || !range.startsWith("bytes=")) return 0;
        try { return Long.parseLong(range.substring(6).split("-", 2)[0]); }
        catch (Exception ignored) { return -1; }
    }
    private void serve(Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(20000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
            String request = reader.readLine();
            if (request == null) return;
            String range = null, line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase(java.util.Locale.ROOT).startsWith("range:")) range = line.substring(6).trim();
            }
            OutputStream out = s.getOutputStream();
            Uri uri = song;
            boolean head = request.startsWith("HEAD /audio");
            if (uri == null || !(head || request.startsWith("GET /audio"))) {
                write(out, "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
                return;
            }
            String mime = context.getContentResolver().getType(uri);
            if (mime == null || !mime.startsWith("audio/")) mime = "audio/mpeg";
            long length = size(uri);
            long start = parseStart(range);
            if (start < 0 || (length >= 0 && start >= length)) {
                write(out, "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Length: 0\r\n" +
                        (length >= 0 ? "Content-Range: bytes */" + length + "\r\n" : "") +
                        "Connection: close\r\n\r\n");
                return;
            }
            boolean partial = range != null && length >= 0;
            write(out, "HTTP/1.1 " + (partial ? "206 Partial Content" : "200 OK") + "\r\n");
            write(out, "Content-Type: " + mime + "\r\nAccess-Control-Allow-Origin: *\r\nAccept-Ranges: bytes\r\n");
            if (length >= 0) {
                write(out, "Content-Length: " + (length - start) + "\r\n");
                if (partial) write(out, "Content-Range: bytes " + start + "-" + (length - 1) + "/" + length + "\r\n");
            }
            write(out, "Connection: close\r\n\r\n");
            if (head) return;
            try (InputStream stream = context.getContentResolver().openInputStream(uri)) {
                if (stream == null) return;
                long skip = start;
                while (skip > 0) {
                    long n = stream.skip(skip);
                    if (n <= 0) { if (stream.read() < 0) return; n = 1; }
                    skip -= n;
                }
                byte[] buffer = new byte[32768];
                int read;
                long remaining = length >= 0 ? length - start : Long.MAX_VALUE;
                while (remaining > 0 && (read = stream.read(buffer, 0, (int)Math.min(buffer.length, remaining))) > 0) {
                    out.write(buffer, 0, read);
                    remaining -= read;
                }
            }
        } catch (Exception ignored) { /* A receiver may disconnect at any moment. */ }
    }
}
