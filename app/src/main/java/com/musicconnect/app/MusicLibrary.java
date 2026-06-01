package com.musicconnect.app;

import android.content.ContentUris;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.provider.MediaStore;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class MusicLibrary {
    public static List<Track> load(Context context) {
        List<Track> tracks = new ArrayList<>();
        tracks.addAll(loadAudio(context));
        tracks.addAll(loadVideos(context));
        tracks.addAll(loadAppFiles(context));
        applyOverrides(context, tracks);
        tracks.sort(Comparator.comparing(track -> track.title.toLowerCase()));
        return tracks;
    }

    private static List<Track> loadAudio(Context context) {
        List<Track> tracks = new ArrayList<>();
        Uri collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String[] projection = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DISPLAY_NAME
        };
        String selection = MediaStore.Audio.Media.IS_MUSIC + " != 0";
        String sort = MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC";

        try (Cursor cursor = context.getContentResolver().query(collection, projection, selection, null, sort)) {
            if (cursor == null) {
                return tracks;
            }
            int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
            int titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
            int artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
            int albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM);
            int durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
            int displayNameColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME);

            while (cursor.moveToNext()) {
                long id = cursor.getLong(idColumn);
                Uri contentUri = ContentUris.withAppendedId(collection, id);
                String displayName = cursor.getString(displayNameColumn);
                AudioTags tags = resolveAudioTags(
                        context,
                        contentUri,
                        cursor.getString(titleColumn),
                        cursor.getString(artistColumn),
                        cursor.getString(albumColumn),
                        displayName
                );
                tracks.add(new Track(
                        id,
                        tags.title,
                        tags.artist,
                        tags.album,
                        cursor.getLong(durationColumn),
                        contentUri.toString(),
                        false,
                        "audio:" + id,
                        displayName,
                        false,
                        null
                ));
            }
        }
        return tracks;
    }

    private static AudioTags resolveAudioTags(Context context, Uri uri, String title, String artist, String album, String displayName) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, uri);
            title = firstUseful(title, retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE));
            artist = firstUseful(artist, retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST));
            album = firstUseful(album, retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM));
        } catch (Exception ignored) {
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }

        FilenameTags filenameTags = parseFilename(displayName);
        if (!isUseful(title)) {
            title = firstUseful(filenameTags.title, stripExtension(displayName));
        }
        if (!isUseful(artist)) {
            artist = firstUseful(filenameTags.artist, "Local audio");
        }
        if (!isUseful(album)) {
            album = "Unknown album";
        }
        return new AudioTags(title, artist, album);
    }

    private static String firstUseful(String first, String second) {
        return isUseful(first) ? first : second;
    }

    private static boolean isUseful(String value) {
        if (value == null) {
            return false;
        }
        String clean = value.trim();
        return !clean.isEmpty()
                && !"<unknown>".equalsIgnoreCase(clean)
                && !"unknown".equalsIgnoreCase(clean)
                && !"unknown artist".equalsIgnoreCase(clean);
    }

    private static FilenameTags parseFilename(String displayName) {
        String name = stripExtension(displayName);
        if (!isUseful(name)) {
            return new FilenameTags(null, null);
        }
        String normalized = name.replace('_', ' ').trim();
        String[] separators = {" - ", " – ", " — ", "-"};
        for (String separator : separators) {
            int index = normalized.indexOf(separator);
            if (index > 0 && index < normalized.length() - separator.length()) {
                String artist = normalized.substring(0, index).trim();
                String title = normalized.substring(index + separator.length()).trim();
                if (isUseful(artist) && isUseful(title)) {
                    return new FilenameTags(artist, title);
                }
            }
        }
        String featuredArtist = featuredArtist(normalized);
        String title = normalized.replaceAll("(?i)\\s*\\((feat\\.|ft\\.|featuring)\\s+[^)]*\\)", "").trim();
        return new FilenameTags(featuredArtist, isUseful(title) ? title : normalized);
    }

    private static String featuredArtist(String value) {
        String lower = value.toLowerCase();
        String[] markers = {"(feat. ", "(ft. ", "(featuring "};
        for (String marker : markers) {
            int start = lower.indexOf(marker);
            if (start >= 0) {
                int nameStart = start + marker.length();
                int end = value.indexOf(')', nameStart);
                if (end > nameStart) {
                    String artist = value.substring(nameStart, end).trim();
                    return isUseful(artist) ? artist : null;
                }
            }
        }
        return null;
    }

    private static String stripExtension(String displayName) {
        if (displayName == null) {
            return null;
        }
        int dot = displayName.lastIndexOf('.');
        return dot > 0 ? displayName.substring(0, dot) : displayName;
    }

    private static class AudioTags {
        final String title;
        final String artist;
        final String album;

        AudioTags(String title, String artist, String album) {
            this.title = title;
            this.artist = artist;
            this.album = album;
        }
    }

    private static class FilenameTags {
        final String artist;
        final String title;

        FilenameTags(String artist, String title) {
            this.artist = artist;
            this.title = title;
        }
    }

    private static List<Track> loadVideos(Context context) {
        List<Track> tracks = new ArrayList<>();
        Uri collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        String[] projection = {
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.TITLE,
                MediaStore.Video.Media.ALBUM,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.MIME_TYPE
        };
        String selection = MediaStore.Video.Media.MIME_TYPE + " = ?";
        String[] args = {"video/mp4"};
        String sort = MediaStore.Video.Media.TITLE + " COLLATE NOCASE ASC";

        try (Cursor cursor = context.getContentResolver().query(collection, projection, selection, args, sort)) {
            if (cursor == null) {
                return tracks;
            }
            int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
            int titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.TITLE);
            int albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.ALBUM);
            int durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION);

            while (cursor.moveToNext()) {
                long id = cursor.getLong(idColumn);
                Uri contentUri = ContentUris.withAppendedId(collection, id);
                tracks.add(new Track(
                        1_000_000_000L + id,
                        cursor.getString(titleColumn),
                        "Video",
                        cursor.getString(albumColumn),
                        cursor.getLong(durationColumn),
                        contentUri.toString(),
                        true,
                        "video:" + id,
                        cursor.getString(titleColumn) + ".mp4",
                        false,
                        null
                ));
            }
        }
        return tracks;
    }

    public static File appMusicDir(Context context) {
        File dir = new File(context.getExternalFilesDir(null), "Music");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    public static void scan(Context context, File file) {
        MediaScannerConnection.scanFile(context, new String[]{file.getAbsolutePath()}, null, null);
    }

    public static String standardFileName(String artist, String title, boolean video) {
        String cleanArtist = sanitizeFilePart(isUseful(artist) ? artist : "Unknown Artist");
        String cleanTitle = sanitizeFilePart(isUseful(title) ? title : "Unknown Title");
        return cleanArtist + " - " + cleanTitle + (video ? ".mp4" : ".mp3");
    }

    private static List<Track> loadAppFiles(Context context) {
        List<Track> tracks = new ArrayList<>();
        File dir = appMusicDir(context);
        File[] files = dir.listFiles();
        if (files == null) {
            return tracks;
        }
        for (File file : files) {
            if (!file.isFile()) {
                continue;
            }
            String lower = file.getName().toLowerCase();
            boolean video = lower.endsWith(".mp4");
            if (!video && !lower.endsWith(".mp3") && !lower.endsWith(".m4a") && !lower.endsWith(".aac")) {
                continue;
            }
            Uri uri = Uri.fromFile(file);
            AudioTags tags = resolveAudioTags(context, uri, null, null, null, file.getName());
            long duration = durationMs(context, uri);
            String key = "file:" + file.getAbsolutePath();
            tracks.add(new Track(
                    -Math.abs(key.hashCode()),
                    tags.title,
                    video ? "Video" : tags.artist,
                    tags.album,
                    duration,
                    uri.toString(),
                    video,
                    key,
                    file.getName(),
                    true,
                    file.getAbsolutePath()
            ));
        }
        return tracks;
    }

    private static long durationMs(Context context, Uri uri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, uri);
            String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return duration == null ? 0L : Long.parseLong(duration);
        } catch (Exception ignored) {
            return 0L;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }

    private static void applyOverrides(Context context, List<Track> tracks) {
        SharedPreferences prefs = context.getSharedPreferences("music_library", Context.MODE_PRIVATE);
        for (int i = 0; i < tracks.size(); i++) {
            Track track = tracks.get(i);
            String title = prefs.getString("title:" + track.sourceKey, track.title);
            String artist = prefs.getString("artist:" + track.sourceKey, track.artist);
            if (!title.equals(track.title) || !artist.equals(track.artist)) {
                tracks.set(i, new Track(track.id, title, artist, track.album, track.durationMs, track.uri,
                        track.video, track.sourceKey, track.displayName, track.editableFile, track.filePath));
            }
        }
    }

    private static String sanitizeFilePart(String value) {
        String clean = value == null ? "" : value.trim().replaceAll("[\\\\/:*?\"<>|]", "");
        clean = clean.replaceAll("\\s+", " ");
        return clean.isEmpty() ? "Unknown" : clean;
    }
}
