package com.musicconnect.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ConnectServer {
    public interface Controller {
        List<Track> tracks();
        Track currentTrack();
        boolean isPlaying();
        int positionMs();
        int durationMs();
        int volumePercent();
        int bassLevel();
        int midLevel();
        int trebleLevel();
        int currentTrackIndex();
        long tracksVersion();
        boolean shuffleEnabled();
        long sleepRemainingMs();
        void play();
        void pause();
        void togglePlayPause();
        void stopPlayback();
        void next();
        void previous();
        void playAt(int index);
        boolean playTrackId(long trackId);
        void seekTo(int positionMs);
        void setVolumePercent(int volumePercent);
        void setEqualizer(int bass, int mid, int treble);
        void setShuffleEnabled(boolean enabled);
        void toggleShuffle();
        void moveTrack(int fromIndex, int toIndex);
        boolean renameTrack(long trackId, String title, String artist);
        boolean deleteTrack(long trackId);
        void reloadLibrary();
        void setSleepTimerMinutes(int minutes);
        void clearSleepTimer();
    }

    private static final String SERVICE_TYPE = "_music-connect._tcp.";
    private static final int FIXED_PORT = 33387;

    private final Context context;
    private final Controller controller;
    private final Object lock = new Object();
    private ServerSocket serverSocket;
    private Thread serverThread;
    private NsdManager nsdManager;
    private NsdManager.RegistrationListener registrationListener;
    private int port;

    public ConnectServer(Context context, Controller controller) {
        this.context = context.getApplicationContext();
        this.controller = controller;
    }

    public void start() {
        synchronized (lock) {
            if (serverThread != null) {
                return;
            }
            serverThread = new Thread(this::runServer, "music-connect-http");
            serverThread.start();
        }
    }

    public void stop() {
        unregisterService();
        synchronized (lock) {
            if (serverSocket != null) {
                try {
                    serverSocket.close();
                } catch (IOException ignored) {
                }
            }
            serverSocket = null;
            serverThread = null;
            port = 0;
        }
    }

    public int getPort() {
        return port;
    }

    private void runServer() {
        try (ServerSocket socket = new ServerSocket(FIXED_PORT)) {
            synchronized (lock) {
                serverSocket = socket;
                port = socket.getLocalPort();
            }
            registerService(port);
            while (!socket.isClosed()) {
                Socket client = socket.accept();
                new Thread(() -> handleClient(client), "music-connect-client").start();
            }
        } catch (IOException ignored) {
        } finally {
            unregisterService();
        }
    }

    private void handleClient(Socket client) {
        try (Socket socket = client;
             InputStream input = socket.getInputStream();
             OutputStream out = socket.getOutputStream()) {
            String request = readLine(input);
            if (request == null) {
                return;
            }
            String[] parts = request.split(" ");
            if (parts.length < 2) {
                send(out, 400, "{\"error\":\"bad_request\"}");
                return;
            }
            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = readLine(input)) != null) {
                if (line.isEmpty()) {
                    break;
                }
                int colon = line.indexOf(':');
                if (colon > 0) {
                    headers.put(line.substring(0, colon).trim().toLowerCase(Locale.US), line.substring(colon + 1).trim());
                }
            }
            int contentLength = parseInt(headers.get("content-length"), 0);
            byte[] body = readBody(input, contentLength);
            route(parts[0], parts[1], headers, body, out);
        } catch (Throwable ignored) {
        }
    }

    private void route(String method, String rawPath, Map<String, String> headers, byte[] body, OutputStream out) throws IOException {
        String path = rawPath;
        String query = "";
        int queryIndex = rawPath.indexOf('?');
        if (queryIndex >= 0) {
            path = rawPath.substring(0, queryIndex);
            query = rawPath.substring(queryIndex + 1);
        }
        Map<String, String> params = parseQuery(query);

        if ("GET".equals(method) && "/status".equals(path)) {
            send(out, 200, statusJson());
            return;
        }
        if ("GET".equals(method) && "/".equals(path)) {
            sendHtml(out, 200, remoteHtml2());
            return;
        }
        if ("GET".equals(method) && "/tracks".equals(path)) {
            send(out, 200, tracksJson());
            return;
        }
        if ("GET".equals(method) && "/playlists".equals(path)) {
            send(out, 200, playlistsJson());
            return;
        }
        if ("POST".equals(method) && "/rescan".equals(path)) {
            controller.reloadLibrary();
            send(out, 200, tracksJson());
            return;
        }
        if ("GET".equals(method) && "/artwork".equals(path)) {
            sendArtwork(out, parseLong(params.get("id"), -1L));
            return;
        }
        if ("GET".equals(method) && "/media".equals(path)) {
            sendMedia(out, parseLong(params.get("id"), -1L), headers.get("range"));
            return;
        }
        if ("GET".equals(method) && "/frame".equals(path)) {
            sendVideoFrame(out, parseLong(params.get("id"), -1L), parseInt(params.get("positionMs"), 0));
            return;
        }
        if ("POST".equals(method) && "/control".equals(path)) {
            handleControl(params.get("action"));
            send(out, 200, statusJson());
            return;
        }
        if ("POST".equals(method) && "/play".equals(path)) {
            handlePlay(params);
            send(out, 200, statusJson());
            return;
        }
        if ("POST".equals(method) && "/seek".equals(path)) {
            controller.seekTo(parseInt(params.get("positionMs"), 0));
            send(out, 200, statusJson());
            return;
        }
        if ("POST".equals(method) && "/volume".equals(path)) {
            controller.setVolumePercent(parseVolume(params.get("level")));
            send(out, 200, statusJson());
            return;
        }
        if ("POST".equals(method) && "/eq".equals(path)) {
            controller.setEqualizer(parseInt(params.get("bass"), 0), parseInt(params.get("mid"), 0), parseInt(params.get("treble"), 0));
            send(out, 200, statusJson());
            return;
        }
        if ("POST".equals(method) && "/rename".equals(path)) {
            boolean ok = controller.renameTrack(parseLong(params.get("id"), -1L), params.get("title"), params.get("artist"));
            send(out, ok ? 200 : 400, ok ? statusJson() : "{\"error\":\"rename_failed\"}");
            return;
        }
        if ("POST".equals(method) && "/delete".equals(path)) {
            boolean ok = controller.deleteTrack(parseLong(params.get("id"), -1L));
            send(out, ok ? 200 : 400, ok ? tracksJson() : "{\"error\":\"delete_failed\"}");
            return;
        }
        if ("POST".equals(method) && "/playlist".equals(path)) {
            send(out, 200, handlePlaylist(params));
            return;
        }
        if ("POST".equals(method) && "/upload".equals(path)) {
            send(out, 200, handleUpload(headers, body));
            return;
        }
        if ("POST".equals(method) && "/shuffle".equals(path)) {
            handleShuffle(params);
            send(out, 200, statusJson());
            return;
        }
        if ("POST".equals(method) && "/reorder".equals(path)) {
            controller.moveTrack(parseInt(params.get("from"), -1), parseInt(params.get("to"), -1));
            send(out, 200, tracksJson());
            return;
        }
        if ("POST".equals(method) && "/sleep".equals(path)) {
            if ("clear".equals(params.get("action"))) {
                controller.clearSleepTimer();
            } else {
                controller.setSleepTimerMinutes(parseInt(params.get("minutes"), 0));
            }
            send(out, 200, statusJson());
            return;
        }
        send(out, 404, "{\"error\":\"not_found\"}");
    }

    private void handleControl(String action) {
        if ("play".equals(action)) {
            controller.play();
        } else if ("pause".equals(action)) {
            controller.pause();
        } else if ("toggle".equals(action)) {
            controller.togglePlayPause();
        } else if ("stop".equals(action)) {
            controller.stopPlayback();
        } else if ("next".equals(action)) {
            controller.next();
        } else if ("previous".equals(action)) {
            controller.previous();
        }
    }

    private void handlePlay(Map<String, String> params) {
        if (params.containsKey("id")) {
            controller.playTrackId(parseLong(params.get("id"), -1L));
            return;
        }
        if (params.containsKey("index")) {
            controller.playAt(parseInt(params.get("index"), 0));
            return;
        }
        controller.play();
    }

    private void handleShuffle(Map<String, String> params) {
        String enabled = params.get("enabled");
        if (enabled == null) {
            controller.toggleShuffle();
            return;
        }
        controller.setShuffleEnabled("true".equals(enabled) || "1".equals(enabled));
    }

    private String handleUpload(Map<String, String> headers, byte[] body) {
        String rawName = headers.get("x-filename");
        String filename = rawName == null ? "uploaded.mp3" : decode(rawName);
        String lower = filename.toLowerCase(Locale.US);
        boolean video = lower.endsWith(".mp4");
        if (!video && !lower.endsWith(".mp3") && !lower.endsWith(".m4a") && !lower.endsWith(".aac")) {
            return "{\"ok\":false,\"message\":\"รองรับเฉพาะไฟล์ mp3, m4a, aac, mp4\"}";
        }
        if (body == null || body.length == 0) {
            return "{\"ok\":false,\"message\":\"ไฟล์ว่าง\"}";
        }
        String duplicate = duplicateWarning(filename);
        if (duplicate != null && !"true".equals(headers.get("x-force-upload"))) {
            return "{\"ok\":false,\"duplicate\":true,\"message\":\"" + escape(duplicate) + "\"}";
        }
        File dir = MusicLibrary.appMusicDir(context);
        File target = uniqueFile(dir, safeFilename(filename));
        try (FileOutputStream output = new FileOutputStream(target)) {
            output.write(body);
            MusicLibrary.scan(context, target);
            controller.reloadLibrary();
            return "{\"ok\":true,\"message\":\"เพิ่มเพลงแล้ว\",\"file\":\"" + escape(target.getName()) + "\"}";
        } catch (Exception e) {
            return "{\"ok\":false,\"message\":\"บันทึกไฟล์ไม่สำเร็จ\"}";
        }
    }

    private String handlePlaylist(Map<String, String> params) {
        String action = params.get("action");
        String name = cleanPlaylistName(params.get("name"));
        if (name.isEmpty()) {
            return "{\"ok\":false,\"message\":\"playlist_name_required\"}";
        }
        if ("create".equals(action)) {
            List<String> names = playlistNames();
            if (!names.contains(name)) {
                names.add(name);
                savePlaylistNames(names);
            }
            return playlistsJson();
        }
        if ("delete".equals(action)) {
            List<String> names = playlistNames();
            names.remove(name);
            SharedPreferences.Editor editor = prefs().edit();
            editor.putString("playlist_names", joinList(names));
            editor.remove("playlist:" + name);
            editor.apply();
            return playlistsJson();
        }
        if ("add".equals(action)) {
            Track track = findTrack(parseLong(params.get("id"), -1L));
            if (track == null) {
                return "{\"ok\":false,\"message\":\"track_not_found\"}";
            }
            List<String> names = playlistNames();
            if (!names.contains(name)) {
                names.add(name);
                savePlaylistNames(names);
            }
            List<String> keys = playlistKeys(name);
            if (!keys.contains(track.sourceKey)) {
                keys.add(track.sourceKey);
                savePlaylistKeys(name, keys);
            }
            return playlistsJson();
        }
        if ("remove".equals(action)) {
            String key = params.get("key");
            List<String> keys = playlistKeys(name);
            keys.remove(key);
            savePlaylistKeys(name, keys);
            return playlistsJson();
        }
        return "{\"ok\":false,\"message\":\"unknown_playlist_action\"}";
    }

    private String playlistsJson() {
        StringBuilder builder = new StringBuilder();
        builder.append("{\"playlists\":[");
        List<String> names = playlistNames();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            String name = names.get(i);
            builder.append("{\"name\":\"").append(escape(name)).append("\",\"tracks\":[");
            List<String> keys = playlistKeys(name);
            boolean first = true;
            for (String key : keys) {
                Track track = findTrackBySourceKey(key);
                if (track == null) {
                    continue;
                }
                if (!first) {
                    builder.append(',');
                }
                first = false;
                builder.append(trackJson(track));
            }
            builder.append("]}");
        }
        builder.append("]}");
        return builder.toString();
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences("music_library", Context.MODE_PRIVATE);
    }

    private List<String> playlistNames() {
        return splitList(prefs().getString("playlist_names", ""));
    }

    private List<String> playlistKeys(String name) {
        return splitList(prefs().getString("playlist:" + name, ""));
    }

    private void savePlaylistNames(List<String> names) {
        prefs().edit().putString("playlist_names", joinList(names)).apply();
    }

    private void savePlaylistKeys(String name, List<String> keys) {
        prefs().edit().putString("playlist:" + name, joinList(keys)).apply();
    }

    private List<String> splitList(String raw) {
        List<String> list = new ArrayList<>();
        if (raw == null || raw.isEmpty()) {
            return list;
        }
        for (String item : raw.split("\\|", -1)) {
            if (!item.isEmpty()) {
                list.add(item);
            }
        }
        return list;
    }

    private String joinList(List<String> list) {
        StringBuilder builder = new StringBuilder();
        for (String item : list) {
            if (item == null || item.isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append('|');
            }
            builder.append(item.replace("|", ""));
        }
        return builder.toString();
    }

    private String cleanPlaylistName(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("|", "").trim();
    }

    private String duplicateWarning(String filename) {
        String target = normalizeName(stripExtension(filename));
        for (Track track : controller.tracks()) {
            String existing = normalizeName(track.title + " " + track.artist);
            String display = normalizeName(stripExtension(track.displayName));
            if (target.equals(existing) || (!display.isEmpty() && target.equals(display))) {
                return "พบเพลงซ้ำ: " + track.title + " - " + track.artist;
            }
            if (similarity(target, existing) >= 0.82 || (!display.isEmpty() && similarity(target, display) >= 0.82)) {
                return "ชื่อเพลงคล้ายกับ: " + track.title + " - " + track.artist;
            }
        }
        return null;
    }

    private File uniqueFile(File dir, String filename) {
        File file = new File(dir, filename);
        if (!file.exists()) {
            return file;
        }
        int dot = filename.lastIndexOf('.');
        String base = dot > 0 ? filename.substring(0, dot) : filename;
        String ext = dot > 0 ? filename.substring(dot) : "";
        for (int i = 2; ; i++) {
            file = new File(dir, base + " (" + i + ")" + ext);
            if (!file.exists()) {
                return file;
            }
        }
    }

    private String safeFilename(String value) {
        String clean = value == null ? "uploaded.mp3" : value.replaceAll("[\\\\/:*?\"<>|]", "").trim();
        return clean.isEmpty() ? "uploaded.mp3" : clean;
    }

    private String stripExtension(String value) {
        if (value == null) {
            return "";
        }
        int dot = value.lastIndexOf('.');
        return dot > 0 ? value.substring(0, dot) : value;
    }

    private String normalizeName(String value) {
        return value == null ? "" : value.toLowerCase(Locale.US).replaceAll("[^a-z0-9ก-๙]+", " ").trim();
    }

    private double similarity(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        int distance = levenshtein(a, b);
        return 1.0 - (distance / (double) Math.max(a.length(), b.length()));
    }

    private int levenshtein(String a, String b) {
        int[] costs = new int[b.length() + 1];
        for (int j = 0; j < costs.length; j++) {
            costs[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            costs[0] = i;
            int nw = i - 1;
            for (int j = 1; j <= b.length(); j++) {
                int cj = Math.min(1 + Math.min(costs[j], costs[j - 1]), a.charAt(i - 1) == b.charAt(j - 1) ? nw : nw + 1);
                nw = costs[j];
                costs[j] = cj;
            }
        }
        return costs[b.length()];
    }

    private String statusJson() {
        Track track = controller.currentTrack();
        return "{"
                + "\"playing\":" + controller.isPlaying() + ","
                + "\"positionMs\":" + controller.positionMs() + ","
                + "\"durationMs\":" + controller.durationMs() + ","
                + "\"volumePercent\":" + controller.volumePercent() + ","
                + "\"eq\":{\"bass\":" + controller.bassLevel() + ",\"mid\":" + controller.midLevel() + ",\"treble\":" + controller.trebleLevel() + "},"
                + "\"currentIndex\":" + controller.currentTrackIndex() + ","
                + "\"tracksVersion\":" + controller.tracksVersion() + ","
                + "\"shuffle\":" + controller.shuffleEnabled() + ","
                + "\"sleepRemainingMs\":" + controller.sleepRemainingMs() + ","
                + "\"port\":" + getPort() + ","
                + "\"track\":" + trackJson(track)
                + "}";
    }

    private String tracksJson() {
        StringBuilder builder = new StringBuilder();
        builder.append("{\"tracks\":[");
        List<Track> tracks = controller.tracks();
        for (int i = 0; i < tracks.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(trackJson(tracks.get(i)));
        }
        builder.append("]}");
        return builder.toString();
    }

    private String trackJson(Track track) {
        if (track == null) {
            return "null";
        }
        return "{"
                + "\"id\":" + track.id + ","
                + "\"title\":\"" + escape(track.title) + "\","
                + "\"artist\":\"" + escape(track.artist) + "\","
                + "\"album\":\"" + escape(track.album) + "\","
                + "\"durationMs\":" + track.durationMs + ","
                + "\"duration\":\"" + escape(track.durationText()) + "\","
                + "\"video\":" + track.video + ","
                + "\"sourceKey\":\"" + escape(track.sourceKey) + "\","
                + "\"displayName\":\"" + escape(track.displayName == null ? "" : track.displayName) + "\","
                + "\"editableFile\":" + track.editableFile + ","
                + "\"mediaUrl\":\"/media?id=" + track.id + "\","
                + "\"frameUrl\":\"/frame?id=" + track.id + "\","
                + "\"artworkUrl\":\"/artwork?id=" + track.id + "\""
                + "}";
    }

    private void send(OutputStream out, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String reason = status == 200 ? "OK" : status == 404 ? "Not Found" : "Bad Request";
        String headers = "HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Cache-Control: no-store\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
    }

    private void sendHtml(OutputStream out, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String reason = status == 200 ? "OK" : "Bad Request";
        String headers = "HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: text/html; charset=utf-8\r\n"
                + "Cache-Control: no-store\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
    }

    private void sendArtwork(OutputStream out, long trackId) throws IOException {
        Track track = findTrack(trackId);
        byte[] artwork = track == null || track.video ? null : embeddedArtwork(track);
        if (artwork == null || artwork.length == 0) {
            sendPlaceholderArtwork(out);
            return;
        }
        String headers = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: image/jpeg\r\n"
                + "Cache-Control: no-store\r\n"
                + "Content-Length: " + artwork.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.UTF_8));
        out.write(artwork);
    }

    private void sendPlaceholderArtwork(OutputStream out) throws IOException {
        String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 512 512\">"
                + "<rect width=\"512\" height=\"512\" fill=\"#1a2027\"/>"
                + "<circle cx=\"256\" cy=\"256\" r=\"132\" fill=\"#24303a\"/>"
                + "<path fill=\"#22c55e\" d=\"M318 142v164a58 58 0 1 1-28-50V198l-104 24v108a58 58 0 1 1-28-50V198c0-10 7-19 17-21l126-29c9-2 17 5 17 14Z\"/>"
                + "</svg>";
        byte[] bytes = svg.getBytes(StandardCharsets.UTF_8);
        String headers = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: image/svg+xml; charset=utf-8\r\n"
                + "Cache-Control: no-store\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
    }

    private void sendVideoFrame(OutputStream out, long trackId, int positionMs) throws IOException {
        Track track = findTrack(trackId);
        if (track == null || !track.video) {
            sendPlaceholderArtwork(out);
            return;
        }

        int bucketMs = Math.max(0, positionMs / 2000) * 2000;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, Uri.parse(track.uri));
            Bitmap frame = retriever.getFrameAtTime(Math.max(0L, bucketMs) * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) {
                sendPlaceholderArtwork(out);
                return;
            }
            int maxWidth = 420;
            if (frame.getWidth() > maxWidth) {
                int height = Math.max(1, frame.getHeight() * maxWidth / frame.getWidth());
                Bitmap scaled = Bitmap.createScaledBitmap(frame, maxWidth, height, true);
                frame.recycle();
                frame = scaled;
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            frame.compress(Bitmap.CompressFormat.JPEG, 42, buffer);
            frame.recycle();
            byte[] bytes = buffer.toByteArray();
            sendJpeg(out, bytes);
        } catch (Exception ignored) {
            sendPlaceholderArtwork(out);
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }

    private void sendJpeg(OutputStream out, byte[] bytes) throws IOException {
        String headers = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: image/jpeg\r\n"
                + "Cache-Control: no-store\r\n"
                + "Pragma: no-cache\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
    }

    private void sendMedia(OutputStream out, long trackId, String rangeHeader) throws IOException {
        Track track = findTrack(trackId);
        if (track == null || !track.video) {
            send(out, 404, "{\"error\":\"media_not_found\"}");
            return;
        }

        Uri uri = Uri.parse(track.uri);
        long length = -1L;
        try (AssetFileDescriptor descriptor = context.getContentResolver().openAssetFileDescriptor(uri, "r")) {
            if (descriptor != null) {
                length = descriptor.getLength();
            }
        } catch (Exception ignored) {
        }

        InputStream input = context.getContentResolver().openInputStream(uri);
        if (input == null) {
            send(out, 404, "{\"error\":\"media_not_found\"}");
            return;
        }

        long start = 0L;
        long end = length > 0 ? length - 1 : -1L;
        boolean partial = false;
        if (length > 0 && rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            long[] range = parseRange(rangeHeader, length);
            start = range[0];
            end = range[1];
            partial = true;
        }
        long bytesToSend = length > 0 ? end - start + 1 : -1L;
        skipFully(input, start);

        String headers = (partial ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n")
                + "Content-Type: video/mp4\r\n"
                + "Accept-Ranges: bytes\r\n"
                + "Cache-Control: no-store\r\n"
                + "Pragma: no-cache\r\n"
                + (partial ? "Content-Range: bytes " + start + "-" + end + "/" + length + "\r\n" : "")
                + (bytesToSend >= 0 ? "Content-Length: " + bytesToSend + "\r\n" : "")
                + "Connection: close\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.UTF_8));
        byte[] buffer = new byte[64 * 1024];
        int read;
        try (InputStream stream = input) {
            long remaining = bytesToSend;
            while ((read = stream.read(buffer, 0, remaining >= 0 ? (int) Math.min(buffer.length, remaining) : buffer.length)) != -1) {
                out.write(buffer, 0, read);
                if (remaining >= 0) {
                    remaining -= read;
                    if (remaining <= 0) {
                        break;
                    }
                }
            }
        }
    }

    private long[] parseRange(String rangeHeader, long length) {
        String range = rangeHeader.substring("bytes=".length()).trim();
        int dash = range.indexOf('-');
        if (dash < 0) {
            return new long[]{0L, length - 1};
        }
        try {
            String startText = range.substring(0, dash).trim();
            String endText = range.substring(dash + 1).trim();
            if (startText.isEmpty()) {
                long suffixLength = Math.min(length, Long.parseLong(endText));
                return new long[]{length - suffixLength, length - 1};
            }
            long start = Math.max(0L, Long.parseLong(startText));
            long end = endText.isEmpty() ? length - 1 : Math.min(length - 1, Long.parseLong(endText));
            if (end < start) {
                end = length - 1;
            }
            return new long[]{start, end};
        } catch (Exception ignored) {
            return new long[]{0L, length - 1};
        }
    }

    private void skipFully(InputStream input, long bytes) throws IOException {
        long remaining = bytes;
        while (remaining > 0) {
            long skipped = input.skip(remaining);
            if (skipped <= 0) {
                if (input.read() == -1) {
                    break;
                }
                skipped = 1;
            }
            remaining -= skipped;
        }
    }

    private Track findTrack(long id) {
        for (Track track : controller.tracks()) {
            if (track.id == id) {
                return track;
            }
        }
        return null;
    }

    private Track findTrackBySourceKey(String sourceKey) {
        if (sourceKey == null) {
            return null;
        }
        for (Track track : controller.tracks()) {
            if (sourceKey.equals(track.sourceKey)) {
                return track;
            }
        }
        return null;
    }

    private byte[] embeddedArtwork(Track track) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, Uri.parse(track.uri));
            return retriever.getEmbeddedPicture();
        } catch (Exception ignored) {
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }

    private String remoteHtml2() {
        return "<!doctype html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>Music Connect</title>"
                + "<style>"
                + ":root{color-scheme:dark}*{box-sizing:border-box}body{margin:0;background:#0f1418;color:#f4f7fa;font-family:system-ui,-apple-system,Segoe UI,sans-serif}main{width:min(1120px,100%);margin:auto;padding:clamp(14px,3vw,28px)}.hero{display:grid;grid-template-columns:minmax(280px,1.35fr) minmax(260px,.8fr);gap:clamp(16px,3vw,32px);align-items:center}.hero section{text-align:center;min-width:0}.cover{width:100%;max-height:min(62vh,520px);aspect-ratio:16/9;background:#000;border-radius:8px;display:grid;place-items:center;overflow:hidden;box-shadow:0 18px 48px rgba(0,0,0,.28);margin:auto;animation:rise .35s ease-out}.cover.audio{width:min(320px,100%);aspect-ratio:1;background:#1a2027}.cover img,.cover video{width:100%;height:100%;object-fit:contain;transition:opacity .25s ease,transform .25s ease}.cover img{object-fit:cover}.disc{font-size:54px;color:var(--accent,#22c55e)}h1{font-size:clamp(24px,3vw,32px);margin:0 0 6px}.muted{color:#a9b3bd}.title{font-size:clamp(18px,2.2vw,24px);font-weight:700;overflow-wrap:anywhere}.meta,.time{margin-top:8px}.time{font-variant-numeric:tabular-nums}.barline{display:grid;grid-template-columns:minmax(40px,auto) minmax(120px,1fr) minmax(40px,auto);gap:10px;align-items:center;margin:18px 0 12px}.youtube-box{min-height:48px;margin:18px auto 0;display:flex;justify-content:center}.range{appearance:none;width:100%;height:6px;border-radius:999px;background:linear-gradient(90deg,var(--accent,#22c55e) var(--value,0%),#394550 var(--value,0%));outline:0}.range::-webkit-slider-thumb{appearance:none;width:14px;height:14px;border-radius:50%;background:#f4f7fa}.range::-moz-range-thumb{width:14px;height:14px;border:0;border-radius:50%;background:#f4f7fa}button,a.button{font:inherit;text-decoration:none;border:0;border-radius:8px;padding:12px 16px;background:#24303a;color:#f4f7fa;margin:0;cursor:pointer;display:inline-flex;align-items:center;justify-content:center;min-height:42px;transition:transform .14s ease,background .18s ease,box-shadow .18s ease}button:hover,a.button:hover{transform:translateY(-1px);box-shadow:0 8px 22px rgba(0,0,0,.22)}button:active,a.button:active{transform:scale(.96)}button.primary,button.active{background:var(--accent,#22c55e);color:#07100a}.row{display:flex;flex-wrap:wrap;justify-content:center;gap:10px;margin:16px auto}.desktop-grid{display:grid;grid-template-columns:minmax(0,1.45fr) minmax(260px,.75fr);gap:clamp(16px,3vw,28px);align-items:start}.desktop-grid>section>.row{justify-content:center}.panel{background:#1a2027;border-radius:8px;padding:clamp(12px,2vw,16px);margin:16px 0;min-width:0;animation:rise .28s ease-out}.volume-head{display:flex;justify-content:space-between;gap:12px;align-items:center}.tracks-head{display:grid;grid-template-columns:1fr minmax(220px,320px) 1fr;gap:12px;align-items:center}.tracks-head button{justify-self:end}.track-layout{display:grid;grid-template-columns:minmax(0,1fr);gap:16px;align-items:start}.add-panel,.playlist-panel{background:#121a21;border:1px solid #2b3640;border-radius:8px;padding:14px}.add-panel input,.playlist-panel input,.playlist-panel select{margin:10px 0;width:100%;padding:10px;border-radius:8px;border:1px solid #394550;background:#101820;color:#f4f7fa}.playlist-actions{display:grid;grid-template-columns:1fr 1fr;gap:8px}.playlist-song{display:flex;justify-content:space-between;gap:8px;align-items:center;border-top:1px solid #2b3640;padding:9px 0}.track{display:grid;grid-template-columns:28px minmax(0,1fr) auto auto auto auto auto;gap:10px;align-items:center;padding:12px 0;border-top:1px solid #2b3640;cursor:pointer;transition:background .18s ease,color .18s ease,transform .18s ease}.track:hover{background:#202a33;transform:translateX(2px)}.track.current{color:var(--accent,#22c55e)}.eq{display:flex;gap:3px;height:18px;align-items:end}.eq i{display:block;width:3px;background:var(--accent,#22c55e);border-radius:2px;transform-origin:bottom;animation:eq 1.05s infinite cubic-bezier(.45,0,.2,1)}.eq i:nth-child(2){animation-delay:140ms}.eq i:nth-child(3){animation-delay:280ms}@keyframes eq{0%,100%{transform:scaleY(.25);opacity:.65}45%{transform:scaleY(1);opacity:1}}@keyframes rise{from{opacity:0;transform:translateY(8px)}to{opacity:1;transform:translateY(0)}}.badge{color:var(--accent,#22c55e);font-size:12px}.small{font-size:13px}.icon{width:46px;height:46px;padding:0;border-radius:999px;font-size:20px;line-height:1;text-align:center}.primary.icon{width:56px;height:56px;font-size:24px}.order{white-space:nowrap}.order button,.mini{padding:6px 10px;margin:0 2px;min-height:34px}.panel input[type=text],.panel input[type=search],.panel input[type=file],.panel input[type=number]{width:min(100%,280px);padding:10px;border-radius:8px;border:1px solid #394550;background:#101820;color:#f4f7fa}.sleep .row{justify-content:flex-start}.eqrow{display:grid;grid-template-columns:58px 1fr 42px;gap:10px;align-items:center;margin-top:10px}@media(max-width:860px){.hero,.desktop-grid,.track-layout{grid-template-columns:1fr}.tracks-head{grid-template-columns:1fr}.tracks-head button,.tracks-head input{justify-self:stretch;width:100%}.hero section{text-align:left}.cover{width:100%;max-height:46vh}.cover.audio{margin:auto}.row{justify-content:center}.sleep .row{justify-content:center}}@media(max-width:560px){main{padding:12px}.barline{grid-template-columns:42px 1fr 42px;gap:8px}.track{grid-template-columns:22px minmax(0,1fr) auto}.order,.editcol,.deletecol,.playlistcol{display:none}.icon{width:42px;height:42px}.primary.icon{width:52px;height:52px}.panel input[type=text],.panel input[type=search],.panel input[type=file],.panel input[type=number]{width:100%}button,a.button{padding:10px 12px}.title{font-size:18px}}"
                + "</style></head><body><main>"
                + "<div class=\"hero\"><div class=\"cover\" id=\"cover\"><span class=\"disc\">&#9834;</span></div><section><h1>Music Connect</h1><div id=\"now\" class=\"title\">Loading...</div><div id=\"meta\" class=\"muted meta\"></div><div class=\"barline\"><span id=\"pos\" class=\"muted time\">0:00</span><input id=\"seek\" class=\"range\" type=\"range\" min=\"0\" max=\"1000\" value=\"0\" oninput=\"paint(this)\" onchange=\"seek(this.value)\"><span id=\"dur\" class=\"muted time\">0:00</span></div><div id=\"yt\" class=\"youtube-box\"></div></section></div>"
                + "<div class=\"desktop-grid\"><section><div class=\"row\"><button id=\"shuffle\" class=\"icon\" onclick=\"toggleShuffle()\">&#8644;</button><button class=\"icon\" onclick=\"cmd('previous')\">&#9198;</button><button id=\"playToggle\" class=\"primary icon\" onclick=\"cmd('toggle')\">&#9654;</button><button class=\"icon\" onclick=\"cmd('next')\">&#9197;</button><button class=\"icon\" onclick=\"cmd('stop')\">&#9632;</button></div>"
                + "<div class=\"panel\"><div class=\"volume-head\"><span class=\"muted\">Volume</span><strong id=\"volText\">100%</strong></div><input id=\"vol\" class=\"range\" type=\"range\" min=\"0\" max=\"100\" value=\"100\" oninput=\"paint(this);document.getElementById('volText').textContent=this.value+'%'\" onchange=\"volume(this.value)\"><div class=\"eqrow\"><span class=\"muted small\">Bass</span><input id=\"bass\" class=\"range\" type=\"range\" min=\"-10\" max=\"10\" value=\"0\" oninput=\"paintEq(this)\" onchange=\"eq()\"><strong id=\"bassText\">0</strong></div><div class=\"eqrow\"><span class=\"muted small\">Mid</span><input id=\"mid\" class=\"range\" type=\"range\" min=\"-10\" max=\"10\" value=\"0\" oninput=\"paintEq(this)\" onchange=\"eq()\"><strong id=\"midText\">0</strong></div><div class=\"eqrow\"><span class=\"muted small\">Treble</span><input id=\"treble\" class=\"range\" type=\"range\" min=\"-10\" max=\"10\" value=\"0\" oninput=\"paintEq(this)\" onchange=\"eq()\"><strong id=\"trebleText\">0</strong></div></div>"
                + "<div class=\"panel\"><div class=\"tracks-head\"><strong>Tracks</strong><input id=\"search\" type=\"search\" placeholder=\"Search songs\" oninput=\"renderTracks()\"><button onclick=\"rescan()\">Rescan</button></div><div class=\"track-layout\"><div id=\"tracks\" class=\"muted\">Loading...</div></div></div></section><aside>"
                + "<div class=\"panel sleep\"><strong>Sleep timer</strong><div class=\"row\"><button onclick=\"sleep(15)\">15 min</button><button onclick=\"sleep(30)\">30 min</button><button onclick=\"sleep(60)\">60 min</button><input id=\"sleepCustom\" type=\"number\" min=\"1\" placeholder=\"minutes\"><button onclick=\"sleepCustom()\">Set</button><button onclick=\"clearSleep()\">Clear</button></div><div id=\"sleepLeft\" class=\"muted small\"></div></div><div class=\"panel add-panel\"><strong>Add song</strong><input id=\"filePick\" type=\"file\" accept=\"audio/*,video/mp4\"><button onclick=\"uploadPicked(false)\">Add song</button><div id=\"uploadMsg\" class=\"muted small\"></div></div><div class=\"panel playlist-panel\"><strong>Playlists</strong><input id=\"playlistName\" type=\"text\" placeholder=\"Playlist name\"><div class=\"playlist-actions\"><button onclick=\"createPlaylist()\">Create</button><button onclick=\"deletePlaylist()\">Delete</button></div><select id=\"playlistSelect\" onchange=\"renderPlaylistSongs()\"></select><button onclick=\"addCurrentToPlaylist()\">Add current song</button><div id=\"playlistSongs\" class=\"muted small\"></div></div></aside></div>"
                + "<script>"
                + "let status={durationMs:0,currentIndex:-1,tracksVersion:-1},coverTrackId=null,trackData=[],playlists=[],knownTracksVersion=-1,lastFrameKey='';"
                + "async function api(path,opts){try{const options=opts||{};options.cache='no-store';const r=await fetch(path,options);if(!r.ok)return null;return r.json();}catch(e){document.getElementById('meta').textContent='Phone connection lost';return null;}}"
                + "function fmt(ms){let s=Math.max(0,Math.floor(ms/1000)),m=Math.floor(s/60);s=s%60;return m+':'+String(s).padStart(2,'0');}"
                + "function paint(el){el.style.setProperty('--value',(el.value/el.max*100)+'%')}"
                + "function paintEq(el){el.style.setProperty('--value',((Number(el.value)+10)/20*100)+'%');document.getElementById(el.id+'Text').textContent=el.value}"
                + "async function refresh(){const nextStatus=await api('/status');if(!nextStatus)return;status=nextStatus;if(status.tracksVersion!==knownTracksVersion){await loadTracks(false);knownTracksVersion=status.tracksVersion;}const t=status.track;document.getElementById('now').textContent=t?t.title:'No track selected';document.getElementById('meta').textContent=t?((status.playing?'Playing':'Paused')+' - '+(t.video?'MP4 video preview':'Audio')+' - '+t.artist):'';document.getElementById('playToggle').innerHTML=status.playing?'&#10074;&#10074;':'&#9654;';document.getElementById('pos').textContent=fmt(status.positionMs);document.getElementById('dur').textContent=fmt(status.durationMs);const seekEl=document.getElementById('seek');seekEl.value=status.durationMs?Math.floor(status.positionMs*1000/status.durationMs):0;paint(seekEl);const vol=document.getElementById('vol');vol.value=status.volumePercent;paint(vol);document.getElementById('volText').textContent=status.volumePercent+'%';['bass','mid','treble'].forEach(k=>{const e=document.getElementById(k);e.value=status.eq?status.eq[k]:0;paintEq(e)});document.getElementById('shuffle').className=status.shuffle?'active icon':'icon';document.getElementById('sleepLeft').textContent=status.sleepRemainingMs>0?'Stops in '+fmt(status.sleepRemainingMs):'No sleep timer';document.getElementById('yt').innerHTML=t?'<button onclick=\"searchYoutube()\">Search on YouTube</button>':'';cover(t);syncVideo();renderTracks();}"
                + "function cover(t){const el=document.getElementById('cover');const next=t?t.id+':'+t.video:'none';if(next===coverTrackId)return;coverTrackId=next;lastFrameKey='';el.className='cover'+(t&&!t.video?' audio':'');if(!t){el.innerHTML='<span class=\"disc\">&#9834;</span>';return;}if(t.video){el.innerHTML='<img id=\"previewFrame\" alt=\"video preview\" src=\"'+t.frameUrl+'&positionMs='+status.positionMs+'&_='+Date.now()+'\">';return;}el.innerHTML='<img alt=\"cover\" src=\"'+t.artworkUrl+'\">';}"
                + "function syncVideo(){const img=document.getElementById('previewFrame');if(!img||!status.track||!status.track.video)return;const bucket=Math.floor(status.positionMs/2000);const key=status.track.id+':'+bucket;if(key===lastFrameKey)return;lastFrameKey=key;img.src=status.track.frameUrl+'&positionMs='+(bucket*2000)+'&_='+Date.now();}"
                + "async function loadTracks(draw=true){const data=await api('/tracks');if(!data)return;trackData=data.tracks;if(draw)renderTracks();}"
                + "function renderTracks(){const box=document.getElementById('tracks');const q=(document.getElementById('search')?.value||'').toLowerCase();box.innerHTML=trackData.map((t,i)=>({t,i})).filter(x=>!q||(x.t.title+' '+x.t.artist+' '+x.t.displayName).toLowerCase().includes(q)).map(x=>{const t=x.t,i=x.i;return '<div class=\"track '+(i===status.currentIndex?'current':'')+'\" onclick=\"playIndex('+i+')\"><div>'+(i===status.currentIndex&&status.playing?'<span class=\"eq\"><i></i><i></i><i></i></span>':'')+'</div><div><span class=\"badge\">'+(t.video?'MP4':'Audio')+'</span><br>'+escapeHtml(t.title)+'<br><span class=\"muted small\">'+escapeHtml(t.artist)+'</span></div><span class=\"muted small\">'+escapeHtml(t.duration)+'</span><span class=\"playlistcol\"><button class=\"mini\" title=\"Add to playlist\" onclick=\"event.stopPropagation();addToPlaylist('+i+')\">+</button></span><span class=\"editcol\"><button class=\"mini\" title=\"Edit\" onclick=\"event.stopPropagation();editTrack('+i+')\">Edit</button></span><span class=\"order\"><button title=\"Move up\" onclick=\"event.stopPropagation();move('+i+','+(i-1)+')\">&#8593;</button><button title=\"Move down\" onclick=\"event.stopPropagation();move('+i+','+(i+1)+')\">&#8595;</button></span><span class=\"deletecol\">'+(t.editableFile?'<button class=\"mini\" title=\"Delete\" onclick=\"event.stopPropagation();deleteTrack('+i+')\">Delete</button>':'')+'</span></div>'}).join('')||'No tracks found';}"
                + "function escapeHtml(s){return String(s).replace(/[&<>\"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;',\"'\":'&#39;'}[c]));}"
                + "async function cmd(a){try{await api('/control?action='+a,{method:'POST'});refresh();}catch(e){}}"
                + "async function playIndex(i){try{await api('/play?index='+i,{method:'POST'});refresh();}catch(e){}}"
                + "async function seek(v){try{await api('/seek?positionMs='+Math.floor(status.durationMs*v/1000),{method:'POST'});refresh();}catch(e){}}"
                + "async function volume(v){try{await api('/volume?level='+v,{method:'POST'});refresh();}catch(e){}}"
                + "async function eq(){await api('/eq?bass='+document.getElementById('bass').value+'&mid='+document.getElementById('mid').value+'&treble='+document.getElementById('treble').value,{method:'POST'});refresh();}"
                + "async function toggleShuffle(){try{await api('/shuffle',{method:'POST'});refresh();}catch(e){}}"
                + "async function move(from,to){if(to<0||to>=trackData.length)return;const item=trackData.splice(from,1)[0];trackData.splice(to,0,item);renderTracks();await api('/reorder?from='+from+'&to='+to,{method:'POST'});knownTracksVersion=-1;await refresh();}"
                + "async function editTrack(i){const t=trackData[i];const title=prompt('Song title',t.title);if(title===null)return;const artist=prompt('Artist',t.artist);if(artist===null)return;await api('/rename?id='+t.id+'&title='+encodeURIComponent(title)+'&artist='+encodeURIComponent(artist),{method:'POST'});knownTracksVersion=-1;await refresh();}"
                + "async function rescan(){await api('/rescan',{method:'POST'});knownTracksVersion=-1;await refresh();}"
                + "async function uploadPicked(force){const input=document.getElementById('filePick');const file=input.files[0];if(!file)return;document.getElementById('uploadMsg').textContent='Uploading...';const r=await fetch('/upload',{method:'POST',headers:{'Content-Type':'application/octet-stream','X-Filename':encodeURIComponent(file.name),'X-Force-Upload':force?'true':'false'},body:await file.arrayBuffer()});const data=await r.json();if(data.duplicate&&!force&&confirm(data.message+'\\nเพิ่มอยู่ดีไหม?'))return uploadPicked(true);document.getElementById('uploadMsg').textContent=data.message||'';if(data.ok){input.value='';knownTracksVersion=-1;await refresh();}}"
                + "async function sleep(minutes){await api('/sleep?minutes='+minutes,{method:'POST'});refresh();}"
                + "async function sleepCustom(){const v=parseInt(document.getElementById('sleepCustom').value||'0',10);if(v>0)await sleep(v);}"
                + "async function clearSleep(){await api('/sleep?action=clear',{method:'POST'});refresh();}"
                + "function searchYoutube(){const t=status.track;if(!t)return;window.open('https://www.youtube.com/results?search_query='+encodeURIComponent(t.title+' '+t.artist),'_blank');}"
                + "async function deleteTrack(i){const t=trackData[i];if(!t||!t.editableFile)return;if(!confirm('Delete '+t.title+' from this app?'))return;await api('/delete?id='+t.id,{method:'POST'});knownTracksVersion=-1;await refresh();}"
                + "async function loadPlaylists(){const data=await api('/playlists');if(!data)return;playlists=data.playlists||[];renderPlaylists();}"
                + "function selectedPlaylist(){const sel=document.getElementById('playlistSelect');return sel?sel.value:'';}"
                + "function renderPlaylists(){const sel=document.getElementById('playlistSelect');if(!sel)return;const current=sel.value;sel.innerHTML=playlists.map(p=>'<option value=\"'+escapeHtml(p.name)+'\">'+escapeHtml(p.name)+'</option>').join('');if(current)sel.value=current;renderPlaylistSongs();}"
                + "function renderPlaylistSongs(){const name=selectedPlaylist();const box=document.getElementById('playlistSongs');const p=playlists.find(x=>x.name===name);if(!box)return;if(!p){box.textContent='No playlist selected';return;}box.innerHTML=(p.tracks||[]).map(t=>'<div class=\"playlist-song\"><span>'+escapeHtml(t.title)+'</span><button class=\"mini\" onclick=\"removeFromPlaylist(decodeURIComponent(\\''+jsArg(name)+'\\'),decodeURIComponent(\\''+jsArg(t.sourceKey)+'\\'))\">Remove</button></div>').join('')||'No songs yet';}"
                + "function jsArg(s){return encodeURIComponent(s).replace(/'/g,'%27');}"
                + "async function createPlaylist(){const input=document.getElementById('playlistName');const name=(input.value||'').trim();if(!name)return;const data=await api('/playlist?action=create&name='+encodeURIComponent(name),{method:'POST'});if(data&&data.playlists){playlists=data.playlists;renderPlaylists();input.value='';}}"
                + "async function deletePlaylist(){const name=selectedPlaylist();if(!name)return;if(!confirm('Delete playlist '+name+'?'))return;const data=await api('/playlist?action=delete&name='+encodeURIComponent(name),{method:'POST'});if(data&&data.playlists){playlists=data.playlists;renderPlaylists();}}"
                + "async function addToPlaylist(i){const name=selectedPlaylist();const t=trackData[i];if(!name||!t){alert('Create or select a playlist first');return;}const data=await api('/playlist?action=add&name='+encodeURIComponent(name)+'&id='+t.id,{method:'POST'});if(data&&data.playlists){playlists=data.playlists;renderPlaylists();}}"
                + "async function addCurrentToPlaylist(){if(status.currentIndex>=0)await addToPlaylist(status.currentIndex);}"
                + "async function removeFromPlaylist(name,key){const data=await api('/playlist?action=remove&name='+encodeURIComponent(name)+'&key='+encodeURIComponent(key),{method:'POST'});if(data&&data.playlists){playlists=data.playlists;renderPlaylists();}}"
                + "refresh().catch(()=>{});loadTracks().catch(()=>{});loadPlaylists().catch(()=>{});setInterval(()=>refresh().catch(()=>{}),1000);"
                + "</script></main></body></html>";
    }

    private String remoteHtml() {
        return "<!doctype html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>Music Connect</title>"
                + "<style>"
                + "body{margin:0;background:#0f1418;color:#f4f7fa;font-family:system-ui,-apple-system,Segoe UI,sans-serif}"
                + "main{max-width:1080px;margin:auto;padding:24px}.hero{display:grid;grid-template-columns:minmax(360px,58%) 1fr;gap:28px;align-items:center}.cover{width:100%;aspect-ratio:16/9;background:#000;border-radius:8px;display:grid;place-items:center;overflow:hidden;box-shadow:0 18px 48px rgba(0,0,0,.28)}.cover.audio{max-width:320px;aspect-ratio:1;background:#1a2027}.cover img{width:100%;height:100%;object-fit:cover}.cover video{width:100%;height:100%;object-fit:contain;background:#000}.disc{font-size:54px;color:var(--accent,#22c55e)}"
                + "h1{font-size:30px;margin:0 0 6px}.muted{color:#a9b3bd}.title{font-size:22px;font-weight:700}.meta{margin-top:6px}.time{font-variant-numeric:tabular-nums;margin-top:10px}"
                + "button,a.button{font:inherit;text-decoration:none;border:0;border-radius:8px;padding:12px 16px;background:#24303a;color:#f4f7fa;margin:4px;cursor:pointer;display:inline-block}button.primary{background:var(--accent,#22c55e);color:#07100a}.row{display:flex;flex-wrap:wrap;gap:8px;margin:18px 0}.panel{background:#1a2027;border-radius:8px;padding:16px;margin:16px 0}"
                + "input[type=range]{width:100%}.track{display:flex;justify-content:space-between;gap:12px;padding:12px 0;border-top:1px solid #2b3640;cursor:pointer}.track:hover{color:var(--accent,#22c55e)}.badge{color:var(--accent,#22c55e);font-size:12px}.small{font-size:13px}@media(max-width:780px){main{padding:16px}.hero{grid-template-columns:1fr}.cover{width:100%;max-width:none}.cover.audio{max-width:320px}}"
                + "</style></head><body><main>"
                + "<div class=\"hero\"><div class=\"cover\" id=\"cover\"><span class=\"disc\">&#9834;</span></div><section><h1>Music Connect</h1><div id=\"now\" class=\"title\">Loading...</div><div id=\"meta\" class=\"muted meta\"></div><div id=\"time\" class=\"muted time\">0:00 / 0:00</div><div id=\"yt\"></div></section></div>"
                + "<div class=\"row\"><button onclick=\"cmd('previous')\">Previous</button><button class=\"primary\" onclick=\"cmd('toggle')\">Play / Pause</button><button onclick=\"cmd('next')\">Next</button><button onclick=\"cmd('stop')\">Stop</button></div>"
                + "<div class=\"panel\"><div class=\"muted\">Seek</div><input id=\"seek\" type=\"range\" min=\"0\" max=\"1000\" value=\"0\" onchange=\"seek(this.value)\"></div>"
                + "<div class=\"panel\"><div class=\"muted\">Volume</div><input id=\"vol\" type=\"range\" min=\"0\" max=\"100\" value=\"100\" onchange=\"volume(this.value)\"></div>"
                + "<div class=\"panel\"><strong>Tracks</strong><div id=\"tracks\" class=\"muted\">Loading...</div></div>"
                + "<script>"
                + "let status={durationMs:0},coverTrackId=null;"
                + "async function api(path,opts){const r=await fetch(path,opts||{});return r.json();}"
                + "function fmt(ms){let s=Math.max(0,Math.floor(ms/1000)),m=Math.floor(s/60);s=s%60;return m+':'+String(s).padStart(2,'0');}"
                + "async function refresh(){status=await api('/status');const t=status.track;document.getElementById('now').textContent=t?t.title:'No track selected';document.getElementById('meta').textContent=t?((status.playing?'Playing':'Paused')+' ยท '+(t.video?'MP4 video':'Audio')+' ยท '+t.artist):'';document.getElementById('time').textContent=fmt(status.positionMs)+' / '+fmt(status.durationMs);document.getElementById('seek').value=status.durationMs?Math.floor(status.positionMs*1000/status.durationMs):0;document.getElementById('vol').value=status.volumePercent;document.getElementById('yt').innerHTML='';cover(t);syncVideo();}"
                + "function cover(t){const el=document.getElementById('cover');const next=t?t.id+':'+t.video:'none';if(next===coverTrackId)return;coverTrackId=next;el.className='cover'+(t&&!t.video?' audio':'');if(!t){el.innerHTML='<span class=\"disc\">&#9834;</span>';return;}if(t.video){el.innerHTML='<video id=\"preview\" muted playsinline src=\"'+t.mediaUrl+'\"></video>';return;}el.innerHTML='<img alt=\"cover\" src=\"'+t.artworkUrl+'\">';}"
                + "function syncVideo(){const v=document.getElementById('preview');if(!v||!status.track||!status.track.video)return;const target=status.positionMs/1000;if(Number.isFinite(target)&&v.readyState>0&&Math.abs(v.currentTime-target)>0.75){try{v.currentTime=target;}catch(e){}}if(status.playing){const p=v.play();if(p&&p.catch)p.catch(()=>{});}else{v.pause();}}"
                + "async function loadTracks(){const data=await api('/tracks');document.getElementById('tracks').innerHTML=data.tracks.map((t,i)=>'<div class=\"track\" onclick=\"playIndex('+i+')\"><div><span class=\"badge\">'+(t.video?'MP4':'Audio')+'</span><br>'+escapeHtml(t.title)+'<br><span class=\"muted small\">'+escapeHtml(t.artist)+'</span></div><span class=\"muted small\">'+escapeHtml(t.duration)+'</span></div>').join('')||'No tracks found';}"
                + "function escapeHtml(s){return String(s).replace(/[&<>\"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;',\"'\":'&#39;'}[c]));}"
                + "async function cmd(a){await api('/control?action='+a,{method:'POST'});refresh();}"
                + "async function playIndex(i){await api('/play?index='+i,{method:'POST'});refresh();}"
                + "async function seek(v){await api('/seek?positionMs='+Math.floor(status.durationMs*v/1000),{method:'POST'});refresh();}"
                + "async function volume(v){await api('/volume?level='+v,{method:'POST'});refresh();}"
                + "refresh();loadTracks();setInterval(refresh,1000);"
                + "</script></main></body></html>";
    }

    private void registerService(int servicePort) {
        nsdManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        if (nsdManager == null) {
            return;
        }
        NsdServiceInfo serviceInfo = new NsdServiceInfo();
        serviceInfo.setServiceName("Music Connect " + Build.MODEL);
        serviceInfo.setServiceType(SERVICE_TYPE);
        serviceInfo.setPort(servicePort);
        registrationListener = new NsdManager.RegistrationListener() {
            @Override public void onServiceRegistered(NsdServiceInfo info) { }
            @Override public void onRegistrationFailed(NsdServiceInfo info, int errorCode) { }
            @Override public void onServiceUnregistered(NsdServiceInfo info) { }
            @Override public void onUnregistrationFailed(NsdServiceInfo info, int errorCode) { }
        };
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener);
    }

    private void unregisterService() {
        if (nsdManager != null && registrationListener != null) {
            try {
                nsdManager.unregisterService(registrationListener);
            } catch (IllegalArgumentException ignored) {
            }
        }
        registrationListener = null;
        nsdManager = null;
    }

    private Map<String, String> parseQuery(String query) {
        Map<String, String> map = new LinkedHashMap<>();
        if (query == null || query.isEmpty()) {
            return map;
        }
        for (String part : query.split("&")) {
            int equals = part.indexOf('=');
            String key = equals >= 0 ? part.substring(0, equals) : part;
            String value = equals >= 0 ? part.substring(equals + 1) : "";
            map.put(decode(key), decode(value));
        }
        return map;
    }

    private String decode(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (Exception ignored) {
            return value;
        }
    }

    private String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int previous = -1;
        int current;
        while ((current = input.read()) != -1) {
            if (previous == '\r' && current == '\n') {
                byte[] bytes = buffer.toByteArray();
                return new String(bytes, 0, Math.max(0, bytes.length - 1), StandardCharsets.ISO_8859_1);
            }
            buffer.write(current);
            previous = current;
        }
        if (buffer.size() == 0) {
            return null;
        }
        return new String(buffer.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private byte[] readBody(InputStream input, int length) throws IOException {
        if (length <= 0) {
            return new byte[0];
        }
        byte[] body = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = input.read(body, offset, length - offset);
            if (read < 0) {
                break;
            }
            offset += read;
        }
        if (offset == length) {
            return body;
        }
        byte[] shortBody = new byte[offset];
        System.arraycopy(body, 0, shortBody, 0, offset);
        return shortBody;
    }

    private int parseVolume(String value) {
        if (value == null) {
            return 100;
        }
        try {
            if (value.contains(".")) {
                return Math.max(0, Math.min(100, Math.round(Float.parseFloat(value) * 100f)));
            }
            return Math.max(0, Math.min(100, Integer.parseInt(value)));
        } catch (NumberFormatException ignored) {
            return 100;
        }
    }

    private int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private String escape(String value) {
        return String.format(Locale.US, "%s", value)
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}

