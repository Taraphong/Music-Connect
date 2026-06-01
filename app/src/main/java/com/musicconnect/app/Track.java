package com.musicconnect.app;

public class Track {
    public final long id;
    public final String title;
    public final String artist;
    public final String album;
    public final long durationMs;
    public final String uri;
    public final boolean video;
    public final String sourceKey;
    public final String displayName;
    public final boolean editableFile;
    public final String filePath;

    public Track(long id, String title, String artist, String album, long durationMs, String uri, boolean video) {
        this(id, title, artist, album, durationMs, uri, video, String.valueOf(id), null, false, null);
    }

    public Track(long id, String title, String artist, String album, long durationMs, String uri, boolean video,
                 String sourceKey, String displayName, boolean editableFile, String filePath) {
        this.id = id;
        this.title = clean(title, "Unknown title");
        this.artist = clean(artist, "Unknown artist");
        this.album = clean(album, "Unknown album");
        this.durationMs = durationMs;
        this.uri = uri;
        this.video = video;
        this.sourceKey = clean(sourceKey, String.valueOf(id));
        this.displayName = displayName;
        this.editableFile = editableFile;
        this.filePath = filePath;
    }

    private static String clean(String value, String fallback) {
        if (value == null || value.trim().isEmpty() || "<unknown>".equalsIgnoreCase(value)) {
            return fallback;
        }
        return value;
    }

    public String displayLine() {
        return title + "\n" + artist;
    }

    public String durationText() {
        long totalSeconds = Math.max(0, durationMs / 1000);
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return minutes + ":" + (seconds < 10 ? "0" : "") + seconds;
    }
}
