package com.musicconnect.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.audiofx.Equalizer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.view.Surface;
import android.net.wifi.WifiManager;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public class PlaybackService extends Service implements ConnectServer.Controller {
    public static final String ACTION_PLAY = "com.musicconnect.app.PLAY";
    public static final String ACTION_PAUSE = "com.musicconnect.app.PAUSE";
    public static final String ACTION_TOGGLE = "com.musicconnect.app.TOGGLE";
    public static final String ACTION_STOP = "com.musicconnect.app.STOP";
    public static final String ACTION_NEXT = "com.musicconnect.app.NEXT";
    public static final String ACTION_PREVIOUS = "com.musicconnect.app.PREVIOUS";

    private static final String CHANNEL_ID = "playback";
    private static final int NOTIFICATION_ID = 42;

    private final LocalBinder binder = new LocalBinder();
    private final List<Track> tracks = new ArrayList<>();
    private final Object playerLock = new Object();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final List<Long> shuffleQueue = new ArrayList<>();
    private MediaPlayer player;
    private Equalizer equalizer;
    private MediaSession mediaSession;
    private ConnectServer connectServer;
    private PowerManager.WakeLock serviceWakeLock;
    private WifiManager.WifiLock wifiLock;
    private Surface videoSurface;
    private int currentIndex = -1;
    private int volumePercent = 100;
    private boolean prepared;
    private boolean shuffleEnabled;
    private long sleepTimerEndMs;
    private long tracksVersion;
    private int bassLevel;
    private int midLevel;
    private int trebleLevel;
    private final Runnable sleepTimerRunnable = this::stopPlayback;

    public class LocalBinder extends Binder {
        PlaybackService service() {
            return PlaybackService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        loadEqSettings();
        setupMediaSession();
        acquireServiceLocks();
        connectServer = new ConnectServer(this, this);
        connectServer.start();
        startForeground(NOTIFICATION_ID, buildNotification());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            handleAction(intent.getAction());
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(sleepTimerRunnable);
        if (connectServer != null) {
            connectServer.stop();
        }
        releasePlayer();
        if (mediaSession != null) {
            mediaSession.release();
        }
        releaseServiceLocks();
        super.onDestroy();
    }

    public void reloadLibrary() {
        Track current = currentTrack();
        synchronized (tracks) {
            tracks.clear();
            tracks.addAll(MusicLibrary.load(this));
            applySavedOrderLocked();
            if (currentIndex >= tracks.size()) {
                currentIndex = tracks.isEmpty() ? -1 : 0;
            }
            if (current != null) {
                currentIndex = indexOfSourceKeyLocked(current.sourceKey);
            }
            shuffleQueue.clear();
            tracksVersion++;
        }
        updateNotification();
    }

    @Override
    public void playAt(int index) {
        if (runOnMain(() -> playAt(index))) {
            return;
        }
        List<Track> snapshot = tracks();
        if (index < 0 || index >= snapshot.size()) {
            return;
        }
        currentIndex = index;
        removeFromShuffleQueue(snapshot.get(index).id);
        startTrack(snapshot.get(index));
    }

    @Override
    public boolean playTrackId(long trackId) {
        List<Track> snapshot = tracks();
        for (int i = 0; i < snapshot.size(); i++) {
            if (snapshot.get(i).id == trackId) {
                playAt(i);
                return true;
            }
        }
        return false;
    }

    public int currentIndex() {
        return currentIndex;
    }

    @Override
    public int currentTrackIndex() {
        return currentIndex;
    }

    @Override
    public long tracksVersion() {
        return tracksVersion;
    }

    public int connectPort() {
        return connectServer == null ? 0 : connectServer.getPort();
    }

    public void setVideoSurface(Surface surface) {
        if (runOnMain(() -> setVideoSurface(surface))) {
            return;
        }
        videoSurface = surface;
        synchronized (playerLock) {
            if (player != null) {
                try {
                    player.setSurface(videoSurface);
                } catch (IllegalStateException ignored) {
                }
            }
        }
    }

    public void clearVideoSurface(Surface surface) {
        if (runOnMain(() -> clearVideoSurface(surface))) {
            return;
        }
        if (videoSurface == surface) {
            videoSurface = null;
            synchronized (playerLock) {
                if (player != null) {
                    try {
                        player.setSurface(null);
                    } catch (IllegalStateException ignored) {
                    }
                }
            }
        }
    }

    @Override
    public List<Track> tracks() {
        synchronized (tracks) {
            return Collections.unmodifiableList(new ArrayList<>(tracks));
        }
    }

    @Override
    public Track currentTrack() {
        List<Track> snapshot = tracks();
        if (currentIndex < 0 || currentIndex >= snapshot.size()) {
            return null;
        }
        return snapshot.get(currentIndex);
    }

    @Override
    public boolean isPlaying() {
        synchronized (playerLock) {
            try {
                return player != null && prepared && player.isPlaying();
            } catch (IllegalStateException ignored) {
                return false;
            }
        }
    }

    @Override
    public int positionMs() {
        synchronized (playerLock) {
            if (player == null || !prepared) {
                return 0;
            }
            try {
                return player.getCurrentPosition();
            } catch (IllegalStateException ignored) {
                return 0;
            }
        }
    }

    @Override
    public int durationMs() {
        Track track = currentTrack();
        if (track != null && track.durationMs > 0) {
            return (int) Math.min(Integer.MAX_VALUE, track.durationMs);
        }
        synchronized (playerLock) {
            if (player == null || !prepared) {
                return 0;
            }
            try {
                return player.getDuration();
            } catch (IllegalStateException ignored) {
                return 0;
            }
        }
    }

    @Override
    public int volumePercent() {
        return volumePercent;
    }

    @Override
    public int bassLevel() {
        return bassLevel;
    }

    @Override
    public int midLevel() {
        return midLevel;
    }

    @Override
    public int trebleLevel() {
        return trebleLevel;
    }

    @Override
    public boolean shuffleEnabled() {
        return shuffleEnabled;
    }

    @Override
    public long sleepRemainingMs() {
        if (sleepTimerEndMs <= 0) {
            return 0L;
        }
        return Math.max(0L, sleepTimerEndMs - System.currentTimeMillis());
    }

    @Override
    public void play() {
        if (runOnMain(this::play)) {
            return;
        }
        if (tracks().isEmpty()) {
            reloadLibrary();
        }
        if (currentTrack() == null && !tracks().isEmpty()) {
            playAt(0);
            return;
        }
        synchronized (playerLock) {
            if (player != null && prepared) {
                try {
                    player.start();
                    mediaSession.setActive(true);
                    updatePlaybackState();
                    startForeground(NOTIFICATION_ID, buildNotification());
                } catch (IllegalStateException ignored) {
                    releasePlayer();
                }
            }
        }
    }

    @Override
    public void pause() {
        if (runOnMain(this::pause)) {
            return;
        }
        synchronized (playerLock) {
            try {
                if (player != null && prepared && player.isPlaying()) {
                    player.pause();
                }
        } catch (IllegalStateException ignored) {
                releasePlayer();
            }
        }
        updatePlaybackState();
        updateNotification();
    }

    @Override
    public void togglePlayPause() {
        if (runOnMain(this::togglePlayPause)) {
            return;
        }
        if (isPlaying()) {
            pause();
        } else {
            play();
        }
    }

    @Override
    public void stopPlayback() {
        if (runOnMain(this::stopPlayback)) {
            return;
        }
        releasePlayer();
        mediaSession.setActive(false);
        updatePlaybackState();
        updateNotification();
    }

    @Override
    public void next() {
        if (runOnMain(this::next)) {
            return;
        }
        List<Track> snapshot = tracks();
        if (snapshot.isEmpty()) {
            return;
        }
        int nextIndex;
        if (shuffleEnabled && snapshot.size() > 1) {
            nextIndex = nextShuffleIndex(snapshot);
        } else {
            nextIndex = currentIndex < 0 ? 0 : (currentIndex + 1) % snapshot.size();
        }
        playAt(nextIndex);
    }

    @Override
    public void previous() {
        if (runOnMain(this::previous)) {
            return;
        }
        List<Track> snapshot = tracks();
        if (snapshot.isEmpty()) {
            return;
        }
        int previousIndex = currentIndex <= 0 ? snapshot.size() - 1 : currentIndex - 1;
        playAt(previousIndex);
    }

    @Override
    public void seekTo(int positionMs) {
        if (runOnMain(() -> seekTo(positionMs))) {
            return;
        }
        synchronized (playerLock) {
            if (player != null && prepared) {
                try {
                    player.seekTo(Math.max(0, positionMs));
                    updatePlaybackState();
                } catch (IllegalStateException ignored) {
                    releasePlayer();
                }
            }
        }
    }

    @Override
    public void setVolumePercent(int value) {
        if (runOnMain(() -> setVolumePercent(value))) {
            return;
        }
        volumePercent = Math.max(0, Math.min(100, value));
        synchronized (playerLock) {
            if (player != null) {
                try {
                    float volume = volumePercent / 100f;
                    player.setVolume(volume, volume);
                } catch (IllegalStateException ignored) {
                }
            }
        }
        updateNotification();
    }

    @Override
    public void setEqualizer(int bass, int mid, int treble) {
        if (runOnMain(() -> setEqualizer(bass, mid, treble))) {
            return;
        }
        bassLevel = clampEq(bass);
        midLevel = clampEq(mid);
        trebleLevel = clampEq(treble);
        getSharedPreferences("music_library", MODE_PRIVATE)
                .edit()
                .putInt("eq:bass", bassLevel)
                .putInt("eq:mid", midLevel)
                .putInt("eq:treble", trebleLevel)
                .apply();
        applyEqualizer();
    }

    @Override
    public void setShuffleEnabled(boolean enabled) {
        if (runOnMain(() -> setShuffleEnabled(enabled))) {
            return;
        }
        shuffleEnabled = enabled;
        shuffleQueue.clear();
    }

    @Override
    public void toggleShuffle() {
        if (runOnMain(this::toggleShuffle)) {
            return;
        }
        shuffleEnabled = !shuffleEnabled;
        shuffleQueue.clear();
    }

    @Override
    public void moveTrack(int fromIndex, int toIndex) {
        if (runOnMain(() -> moveTrack(fromIndex, toIndex))) {
            return;
        }
        synchronized (tracks) {
            if (fromIndex < 0 || fromIndex >= tracks.size() || toIndex < 0 || toIndex >= tracks.size() || fromIndex == toIndex) {
                return;
            }
            Track current = currentTrack();
            Track moved = tracks.remove(fromIndex);
            tracks.add(toIndex, moved);
            if (current != null) {
                currentIndex = -1;
                for (int i = 0; i < tracks.size(); i++) {
                    if (tracks.get(i).id == current.id) {
                        currentIndex = i;
                        break;
                    }
                }
            }
            tracksVersion++;
            shuffleQueue.clear();
            saveOrderLocked();
        }
    }

    @Override
    public boolean renameTrack(long trackId, String title, String artist) {
        if (runOnMain(() -> renameTrack(trackId, title, artist))) {
            return true;
        }
        Track track = findTrackById(trackId);
        if (track == null || title == null || title.trim().isEmpty()) {
            return false;
        }
        String cleanTitle = title.trim();
        String cleanArtist = artist == null || artist.trim().isEmpty() ? "Unknown Artist" : artist.trim();
        String newName = MusicLibrary.standardFileName(cleanArtist, cleanTitle, track.video);
        boolean renamed = renameBackingFile(track, newName);
        getSharedPreferences("music_library", MODE_PRIVATE)
                .edit()
                .putString("title:" + track.sourceKey, cleanTitle)
                .putString("artist:" + track.sourceKey, cleanArtist)
                .apply();
        reloadLibrary();
        return renamed || true;
    }

    @Override
    public boolean deleteTrack(long trackId) {
        if (runOnMain(() -> deleteTrack(trackId))) {
            return true;
        }
        Track track = findTrackById(trackId);
        if (track == null || !track.editableFile || track.filePath == null) {
            return false;
        }
        File file = new File(track.filePath);
        boolean wasCurrent = currentTrack() != null && currentTrack().id == track.id;
        boolean deleted = file.exists() && file.delete();
        if (!deleted) {
            return false;
        }
        if (wasCurrent) {
            stopPlayback();
            currentIndex = -1;
        }
        MusicLibrary.scan(this, file);
        getSharedPreferences("music_library", MODE_PRIVATE)
                .edit()
                .remove("title:" + track.sourceKey)
                .remove("artist:" + track.sourceKey)
                .apply();
        removeFromShuffleQueue(track.id);
        reloadLibrary();
        return true;
    }

    private int nextShuffleIndex(List<Track> snapshot) {
        if (shuffleQueue.isEmpty()) {
            refillShuffleQueue(snapshot);
        }
        while (!shuffleQueue.isEmpty()) {
            long nextId = shuffleQueue.remove(0);
            for (int i = 0; i < snapshot.size(); i++) {
                if (snapshot.get(i).id == nextId) {
                    return i;
                }
            }
        }
        return currentIndex < 0 ? 0 : (currentIndex + 1) % snapshot.size();
    }

    private void refillShuffleQueue(List<Track> snapshot) {
        shuffleQueue.clear();
        for (Track track : snapshot) {
            if (currentTrack() == null || track.id != currentTrack().id) {
                shuffleQueue.add(track.id);
            }
        }
        Collections.shuffle(shuffleQueue, random);
    }

    private void removeFromShuffleQueue(long trackId) {
        for (int i = shuffleQueue.size() - 1; i >= 0; i--) {
            if (shuffleQueue.get(i) == trackId) {
                shuffleQueue.remove(i);
            }
        }
    }

    private Track findTrackById(long id) {
        for (Track track : tracks()) {
            if (track.id == id) {
                return track;
            }
        }
        return null;
    }

    private boolean renameBackingFile(Track track, String newName) {
        try {
            if (track.editableFile && track.filePath != null) {
                File oldFile = new File(track.filePath);
                File newFile = new File(oldFile.getParentFile(), newName);
                boolean ok = oldFile.renameTo(newFile);
                if (ok) {
                    MusicLibrary.scan(this, newFile);
                }
                return ok;
            }
            ContentValues values = new ContentValues();
            values.put(track.video ? MediaStore.Video.Media.DISPLAY_NAME : MediaStore.Audio.Media.DISPLAY_NAME, newName);
            return getContentResolver().update(Uri.parse(track.uri), values, null, null) > 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void applySavedOrderLocked() {
        String order = getSharedPreferences("music_library", MODE_PRIVATE).getString("order", "");
        if (order == null || order.isEmpty() || tracks.size() < 2) {
            return;
        }
        List<Track> ordered = new ArrayList<>();
        for (String key : order.split("\\|", -1)) {
            for (int i = 0; i < tracks.size(); i++) {
                Track track = tracks.get(i);
                if (track.sourceKey.equals(key)) {
                    ordered.add(track);
                    tracks.remove(i);
                    break;
                }
            }
        }
        ordered.addAll(tracks);
        tracks.clear();
        tracks.addAll(ordered);
    }

    private void saveOrderLocked() {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < tracks.size(); i++) {
            if (i > 0) {
                builder.append('|');
            }
            builder.append(tracks.get(i).sourceKey);
        }
        getSharedPreferences("music_library", MODE_PRIVATE).edit().putString("order", builder.toString()).apply();
    }

    private int indexOfSourceKeyLocked(String sourceKey) {
        for (int i = 0; i < tracks.size(); i++) {
            if (tracks.get(i).sourceKey.equals(sourceKey)) {
                return i;
            }
        }
        return tracks.isEmpty() ? -1 : 0;
    }

    @Override
    public void setSleepTimerMinutes(int minutes) {
        if (runOnMain(() -> setSleepTimerMinutes(minutes))) {
            return;
        }
        handler.removeCallbacks(sleepTimerRunnable);
        if (minutes <= 0) {
            sleepTimerEndMs = 0L;
            return;
        }
        long delayMs = minutes * 60_000L;
        sleepTimerEndMs = System.currentTimeMillis() + delayMs;
        handler.postDelayed(sleepTimerRunnable, delayMs);
    }

    @Override
    public void clearSleepTimer() {
        if (runOnMain(this::clearSleepTimer)) {
            return;
        }
        handler.removeCallbacks(sleepTimerRunnable);
        sleepTimerEndMs = 0L;
    }

    private boolean runOnMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return false;
        }
        handler.post(action);
        return true;
    }

    private void handleAction(String action) {
        if (ACTION_PLAY.equals(action)) {
            play();
        } else if (ACTION_PAUSE.equals(action)) {
            pause();
        } else if (ACTION_TOGGLE.equals(action)) {
            togglePlayPause();
        } else if (ACTION_STOP.equals(action)) {
            stopPlayback();
        } else if (ACTION_NEXT.equals(action)) {
            next();
        } else if (ACTION_PREVIOUS.equals(action)) {
            previous();
        }
    }

    private void startTrack(Track track) {
        synchronized (playerLock) {
            releasePlayer();
            prepared = false;
            player = new MediaPlayer();
            player.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build());
            if (videoSurface != null) {
                try {
                    player.setSurface(videoSurface);
                } catch (IllegalStateException ignored) {
                }
            }
            player.setOnPreparedListener(mp -> {
                synchronized (playerLock) {
                    prepared = true;
                    try {
                        float volume = volumePercent / 100f;
                        mp.setVolume(volume, volume);
                        setupEqualizer(mp.getAudioSessionId());
                        mp.start();
                    } catch (IllegalStateException ignored) {
                        releasePlayer();
                        return;
                    }
                }
                mediaSession.setActive(true);
                updatePlaybackState();
                startForeground(NOTIFICATION_ID, buildNotification());
            });
            player.setOnCompletionListener(mp -> next());
            player.setOnErrorListener((mp, what, extra) -> {
                next();
                return true;
            });
            try {
                player.setDataSource(this, Uri.parse(track.uri));
                player.prepareAsync();
            } catch (IOException | IllegalArgumentException | SecurityException | IllegalStateException e) {
                releasePlayer();
                next();
            }
        }
        updateNotification();
    }

    private void releasePlayer() {
        synchronized (playerLock) {
            releaseEqualizer();
            if (player != null) {
                try {
                    player.release();
                } catch (Exception ignored) {
                }
                player = null;
            }
            prepared = false;
        }
    }

    private void acquireServiceLocks() {
        try {
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (powerManager != null) {
                serviceWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MusicConnect:service");
                serviceWakeLock.setReferenceCounted(false);
                serviceWakeLock.acquire();
            }
        } catch (Exception ignored) {
        }
        try {
            WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifiManager != null) {
                int mode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                        ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                        : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
                wifiLock = wifiManager.createWifiLock(mode, "MusicConnect:wifi");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
        } catch (Exception ignored) {
        }
    }

    private void releaseServiceLocks() {
        try {
            if (wifiLock != null && wifiLock.isHeld()) {
                wifiLock.release();
            }
        } catch (Exception ignored) {
        }
        wifiLock = null;
        try {
            if (serviceWakeLock != null && serviceWakeLock.isHeld()) {
                serviceWakeLock.release();
            }
        } catch (Exception ignored) {
        }
        serviceWakeLock = null;
    }

    private void loadEqSettings() {
        SharedPreferences prefs = getSharedPreferences("music_library", MODE_PRIVATE);
        bassLevel = prefs.getInt("eq:bass", 0);
        midLevel = prefs.getInt("eq:mid", 0);
        trebleLevel = prefs.getInt("eq:treble", 0);
    }

    private void setupEqualizer(int audioSessionId) {
        releaseEqualizer();
        try {
            equalizer = new Equalizer(0, audioSessionId);
            equalizer.setEnabled(true);
            applyEqualizer();
        } catch (Exception ignored) {
            equalizer = null;
        }
    }

    private void releaseEqualizer() {
        if (equalizer != null) {
            try {
                equalizer.release();
            } catch (Exception ignored) {
            }
            equalizer = null;
        }
    }

    private void applyEqualizer() {
        if (equalizer == null) {
            return;
        }
        try {
            short bands = equalizer.getNumberOfBands();
            short[] range = equalizer.getBandLevelRange();
            for (short band = 0; band < bands; band++) {
                int freq = equalizer.getCenterFreq(band) / 1000;
                int value = freq < 250 ? bassLevel : freq < 4000 ? midLevel : trebleLevel;
                short level = (short) (value * Math.min(Math.abs(range[0]), Math.abs(range[1])) / 10);
                equalizer.setBandLevel(band, level);
            }
        } catch (Exception ignored) {
        }
    }

    private int clampEq(int value) {
        return Math.max(-10, Math.min(10, value));
    }

    private void setupMediaSession() {
        mediaSession = new MediaSession(this, "MusicConnect");
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { play(); }
            @Override public void onPause() { pause(); }
            @Override public void onStop() { stopPlayback(); }
            @Override public void onSkipToNext() { next(); }
            @Override public void onSkipToPrevious() { previous(); }
            @Override public void onSeekTo(long pos) { seekTo((int) pos); }
        });
        updatePlaybackState();
    }

    private void updatePlaybackState() {
        if (mediaSession == null) {
            return;
        }
        long actions = PlaybackState.ACTION_PLAY
                | PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_STOP
                | PlaybackState.ACTION_SKIP_TO_NEXT
                | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                | PlaybackState.ACTION_SEEK_TO;
        int state = isPlaying() ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
        mediaSession.setPlaybackState(new PlaybackState.Builder()
                .setActions(actions)
                .setState(state, positionMs(), isPlaying() ? 1f : 0f)
                .build());
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Playback",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private void updateNotification() {
        updatePlaybackState();
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification());
        }
    }

    private Notification buildNotification() {
        Track track = currentTrack();
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPendingIntent = PendingIntent.getActivity(this, 0, openIntent, pendingFlags());

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setSmallIcon(R.drawable.ic_music)
                .setContentTitle(track == null ? "Music Connect" : track.title)
                .setContentText(track == null ? "Ready on WiFi port " + connectPort() : track.artist)
                .setContentIntent(openPendingIntent)
                .setShowWhen(false)
                .setOngoing(isPlaying())
                .addAction(R.drawable.ic_notification_previous, "Previous", serviceIntent(ACTION_PREVIOUS, 1))
                .addAction(isPlaying() ? R.drawable.ic_notification_pause : R.drawable.ic_notification_play,
                        isPlaying() ? "Pause" : "Play", serviceIntent(ACTION_TOGGLE, 2))
                .addAction(R.drawable.ic_notification_next, "Next", serviceIntent(ACTION_NEXT, 3));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            builder.setStyle(new Notification.MediaStyle()
                    .setMediaSession(mediaSession.getSessionToken())
                    .setShowActionsInCompactView(0, 1, 2));
        }
        return builder.build();
    }

    private PendingIntent serviceIntent(String action, int requestCode) {
        Intent intent = new Intent(this, PlaybackService.class);
        intent.setAction(action);
        return PendingIntent.getService(this, requestCode, intent, pendingFlags());
    }

    private int pendingFlags() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                : PendingIntent.FLAG_UPDATE_CURRENT;
    }
}
