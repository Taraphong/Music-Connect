package com.musicconnect.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Surface;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.animation.DecelerateInterpolator;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;

public class MainActivity extends Activity {
    private static final int REQUEST_PERMISSIONS = 7;
    private static final int REQUEST_PICK_MUSIC = 8;

    private PlaybackService service;
    private boolean bound;
    private final Handler handler = new Handler();
    private final List<Track> tracks = new ArrayList<>();
    private final List<Integer> filteredIndexes = new ArrayList<>();
    private ArrayAdapter<String> adapter;
    private EditText searchInput;
    private TextView title;
    private TextView subtitle;
    private TextView connectInfo;
    private TextView timeInfo;
    private SeekBar seekBar;
    private Button playPause;
    private Button shuffleButton;
    private EditText sleepInput;
    private ImageView artworkView;
    private TextureView videoView;
    private Surface videoSurface;
    private long knownTracksVersion = -1L;
    private long loadedArtworkTrackId = -1L;
    private String currentSearch = "";

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            renderState();
            handler.postDelayed(this, 1000);
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            PlaybackService.LocalBinder localBinder = (PlaybackService.LocalBinder) binder;
            service = localBinder.service();
            bound = true;
            attachVideoSurfaceIfReady();
            reloadLibrary();
            renderState();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound = false;
            service = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildLayout());
        requestNeededPermissions();
        Intent intent = new Intent(this, PlaybackService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        bindService(intent, connection, Context.BIND_AUTO_CREATE);
        requestBatteryOptimizationBypass();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(ticker);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(ticker);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (bound) {
            if (service != null && videoSurface != null) {
                service.clearVideoSurface(videoSurface);
            }
            unbindService(connection);
        }
        if (videoSurface != null) {
            videoSurface.release();
            videoSurface = null;
        }
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS && service != null) {
            reloadLibrary();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PICK_MUSIC && resultCode == RESULT_OK && data != null && data.getData() != null) {
            importPickedSong(data.getData());
        }
    }

    private View buildLayout() {
        int padding = compactUi() ? dp(12) : dp(18);
        int bg = backgroundColor();
        int textColor = textColor();
        int mutedColor = mutedColor();
        FrameLayout frameRoot = new FrameLayout(this);
        frameRoot.setBackgroundColor(bg);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(padding, padding, padding, padding);
        root.setBackgroundColor(bg);
        scrollView.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        frameRoot.addView(scrollView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        FrameLayout videoFrame = new FrameLayout(this);
        videoFrame.setBackgroundColor(Color.BLACK);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            videoFrame.setBackground(rounded(Color.BLACK, dp(10)));
        }

        videoView = new TextureView(this);
        videoView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
                videoSurface = new Surface(surfaceTexture);
                attachVideoSurfaceIfReady();
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) { }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
                if (service != null && videoSurface != null) {
                    service.clearVideoSurface(videoSurface);
                }
                if (videoSurface != null) {
                    videoSurface.release();
                    videoSurface = null;
                }
                return true;
            }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) { }
        });
        videoFrame.addView(videoView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
        ));
        artworkView = new ImageView(this);
        artworkView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        artworkView.setImageResource(R.drawable.ic_music);
        videoFrame.addView(artworkView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
        ));

        title = text("Music Connect", 24, textColor, true);
        subtitle = text("Downloaded music", 14, mutedColor, false);
        connectInfo = text("WiFi control starting...", 13, accentColor(), false);
        timeInfo = text("0:00 / 0:00", 13, mutedColor, false);

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(0, dp(10), 0, dp(8));

        shuffleButton = controlButton("Shuffle", false);
        Button previous = controlButton("Prev", false);
        playPause = controlButton("Play", true);
        Button next = controlButton("Next", false);
        Button stop = controlButton("Stop", false);
        shuffleButton.setOnClickListener(v -> {
            if (service != null) {
                service.toggleShuffle();
                renderState();
            }
        });
        previous.setOnClickListener(v -> {
            if (service != null) {
                service.previous();
                renderState();
            }
        });
        playPause.setOnClickListener(v -> {
            if (service == null) {
                return;
            }
            service.togglePlayPause();
            renderState();
        });
        next.setOnClickListener(v -> {
            if (service != null) {
                service.next();
                renderState();
            }
        });
        stop.setOnClickListener(v -> {
            if (service != null) {
                service.stopPlayback();
                renderState();
            }
        });
        controls.addView(shuffleButton, controlButtonParams(false));
        controls.addView(previous, controlButtonParams(false));
        controls.addView(playPause, controlButtonParams(true));
        controls.addView(next, controlButtonParams(false));
        controls.addView(stop, controlButtonParams(false));

        seekBar = new SeekBar(this);
        seekBar.setMax(1000);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && service != null && service.durationMs() > 0) {
                    service.seekTo((int) (service.durationMs() * (progress / 1000f)));
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });

        adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, new ArrayList<>()) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                int trackIndex = position < filteredIndexes.size() ? filteredIndexes.get(position) : position;
                LinearLayout row = new LinearLayout(MainActivity.this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(10), dp(8), dp(8), dp(8));
                TextView label = text(getItem(position), 15,
                        service != null && trackIndex == service.currentIndex() ? accentColor() : textColor(),
                        false);
                Button up = button("^", false);
                Button down = button("v", false);
                up.setFocusable(false);
                down.setFocusable(false);
                up.setFocusableInTouchMode(false);
                down.setFocusableInTouchMode(false);
                up.setOnClickListener(v -> moveTrack(trackIndex, trackIndex - 1));
                down.setOnClickListener(v -> moveTrack(trackIndex, trackIndex + 1));
                View.OnClickListener playListener = v -> {
                    if (service != null) {
                        service.playAt(trackIndex);
                        renderState();
                    }
                };
                row.setOnClickListener(playListener);
                label.setOnClickListener(playListener);
                row.setOnLongClickListener(v -> {
                    editTrack(trackIndex);
                    return true;
                });
                row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                row.addView(up, new LinearLayout.LayoutParams(dp(44), dp(40)));
                row.addView(down, new LinearLayout.LayoutParams(dp(44), dp(40)));
                return row;
            }
        };
        ListView listView = new ListView(this);
        listView.setAdapter(adapter);
        listView.setDividerHeight(1);
        listView.setBackgroundColor(panelColor());
        listView.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN || event.getAction() == MotionEvent.ACTION_MOVE) {
                disallowParentIntercept(view);
            }
            return false;
        });
        listView.setOnItemClickListener((parent, view, position, id) -> {
            if (service != null) {
                int trackIndex = position < filteredIndexes.size() ? filteredIndexes.get(position) : position;
                service.playAt(trackIndex);
                renderState();
            }
        });

        root.addView(videoFrame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                mediaHeight()
        ));
        root.addView(controls);
        LinearLayout nowPanel = panel();
        nowPanel.addView(title);
        nowPanel.addView(subtitle);
        nowPanel.addView(timeInfo);
        nowPanel.addView(seekBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        nowPanel.addView(connectInfo);
        nowPanel.addView(sleepControls());
        root.addView(nowPanel);
        TextView libraryLabel = text("Library", 15, textColor(), true);
        libraryLabel.setPadding(0, dp(12), 0, dp(6));
        libraryLabel.setText("Library");
        root.addView(libraryLabel);
        LinearLayout libraryTools = new LinearLayout(this);
        libraryTools.setGravity(Gravity.CENTER_VERTICAL);
        searchInput = new EditText(this);
        searchInput.setHint("Search songs");
        searchInput.setSingleLine(true);
        searchInput.setTextColor(textColor());
        searchInput.setHintTextColor(mutedColor());
        searchInput.setPadding(dp(10), 0, dp(10), 0);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            searchInput.setBackground(rounded(inputColor(), dp(8)));
        }
        searchInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                currentSearch = s.toString();
                updateAdapterRows();
            }
            @Override public void afterTextChanged(android.text.Editable s) { }
        });
        Button addSong = button("Add", false);
        addSong.setOnClickListener(v -> pickSong());
        Button rescan = button("Rescan", false);
        rescan.setOnClickListener(v -> {
            reloadLibrary();
            Toast.makeText(this, "Library refreshed", Toast.LENGTH_SHORT).show();
        });
        libraryTools.addView(searchInput, new LinearLayout.LayoutParams(0, dp(44), 1f));
        libraryTools.addView(rescan, buttonParams());
        libraryTools.addView(addSong, buttonParams());
        root.addView(libraryTools);
        root.addView(listView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                listHeight()
        ));

        return frameRoot;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setIncludeFontPadding(true);
        if (bold) {
            view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        }
        return view;
    }

    private Button button(String label, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextColor(primary ? primaryTextColor() : textColor());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            button.setBackground(rounded(primary ? accentColor() : buttonColor(), dp(999)));
        }
        return button;
    }

    private Button controlButton(String label, boolean primary) {
        Button button = button(label, primary);
        button.setTextSize(primary ? 22 : 19);
        button.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                view.animate().scaleX(0.92f).scaleY(0.92f).setDuration(80).start();
            } else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                view.animate().scaleX(1f).scaleY(1f).setDuration(140).setInterpolator(new DecelerateInterpolator()).start();
            }
            return false;
        });
        return button;
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(dp(6), 0, dp(6), 0);
        return params;
    }

    private LinearLayout.LayoutParams controlButtonParams(boolean primary) {
        int size = primary ? controlPrimarySize() : controlSecondarySize();
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
        params.setMargins(dp(compactUi() ? 4 : 8), 0, dp(compactUi() ? 4 : 8), 0);
        return params;
    }

    private LinearLayout sleepControls() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(0, dp(8), 0, 0);
        LinearLayout presetRow = new LinearLayout(this);
        presetRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout customRow = new LinearLayout(this);
        customRow.setGravity(Gravity.CENTER_VERTICAL);
        customRow.setPadding(0, dp(6), 0, 0);
        Button sleep15 = button("15m", false);
        Button sleep30 = button("30m", false);
        Button sleepClear = button("Clear", false);
        sleepInput = new EditText(this);
        sleepInput.setHint("min");
        sleepInput.setTextColor(textColor());
        sleepInput.setHintTextColor(mutedColor());
        sleepInput.setSingleLine(true);
        sleepInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        sleepInput.setPadding(dp(10), 0, dp(10), 0);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            sleepInput.setBackground(rounded(inputColor(), dp(8)));
        }
        Button sleepSet = button("Set", false);
        sleep15.setOnClickListener(v -> setSleep(15));
        sleep30.setOnClickListener(v -> setSleep(30));
        sleepClear.setOnClickListener(v -> {
            if (service != null) {
                service.clearSleepTimer();
                renderState();
            }
        });
        sleepSet.setOnClickListener(v -> {
            try {
                setSleep(Integer.parseInt(sleepInput.getText().toString()));
            } catch (NumberFormatException ignored) {
            }
        });
        presetRow.addView(sleep15, buttonParams());
        presetRow.addView(sleep30, buttonParams());
        presetRow.addView(sleepClear, buttonParams());
        customRow.addView(sleepInput, new LinearLayout.LayoutParams(dp(82), dp(44)));
        customRow.addView(sleepSet, buttonParams());
        column.addView(presetRow);
        column.addView(customRow);
        return column;
    }

    private void setSleep(int minutes) {
        if (service != null && minutes > 0) {
            service.setSleepTimerMinutes(minutes);
            renderState();
        }
    }

    private void moveTrack(int from, int to) {
        if (service == null || to < 0 || to >= tracks.size()) {
            return;
        }
        service.moveTrack(from, to);
        syncTracksFromService();
        renderState();
    }

    private LinearLayout panel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, dp(14), 0, dp(4));
        panel.setLayoutParams(params);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            panel.setBackground(rounded(panelColor(), dp(8)));
        }
        return panel;
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private void reloadLibrary() {
        if (service == null) {
            return;
        }
        service.reloadLibrary();
        syncTracksFromService();
        renderState();
    }

    private void syncTracksFromService() {
        if (service == null) {
            return;
        }
        knownTracksVersion = service.tracksVersion();
        tracks.clear();
        tracks.addAll(service.tracks());
        updateAdapterRows();
    }

    private void updateAdapterRows() {
        adapter.clear();
        filteredIndexes.clear();
        for (int i = 0; i < tracks.size(); i++) {
            Track track = tracks.get(i);
            String haystack = (track.title + " " + track.artist + " " + track.displayName).toLowerCase();
            if (currentSearch != null && !currentSearch.trim().isEmpty() && !haystack.contains(currentSearch.toLowerCase().trim())) {
                continue;
            }
            boolean current = service != null && i == service.currentIndex();
            filteredIndexes.add(i);
            adapter.add((current && service.isPlaying() ? "|||  " : "") + (track.video ? "MP4  " : "Audio  ") + track.title + " - " + track.artist + "  " + track.durationText());
        }
        adapter.notifyDataSetChanged();
    }

    private void pickSong() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"audio/*", "video/mp4"});
        startActivityForResult(intent, REQUEST_PICK_MUSIC);
    }

    private void editTrack(int index) {
        if (service == null || index < 0 || index >= tracks.size()) {
            return;
        }
        Track track = tracks.get(index);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(16), dp(8), dp(16), 0);
        EditText titleInput = new EditText(this);
        titleInput.setHint("Song title");
        titleInput.setText(track.title);
        EditText artistInput = new EditText(this);
        artistInput.setHint("Artist");
        artistInput.setText(track.artist);
        form.addView(titleInput);
        form.addView(artistInput);
        new AlertDialog.Builder(this)
                .setTitle("Edit song")
                .setView(form)
                .setPositiveButton("Save", (dialog, which) -> {
                    service.renameTrack(track.id, titleInput.getText().toString(), artistInput.getText().toString());
                    handler.postDelayed(() -> {
                        syncTracksFromService();
                        renderState();
                    }, 400);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void importPickedSong(Uri uri) {
        String name = "imported.mp3";
        try {
            String type = getContentResolver().getType(uri);
            boolean video = type != null && type.contains("mp4");
            name = "Imported " + System.currentTimeMillis() + (video ? ".mp4" : ".mp3");
            File target = new File(MusicLibrary.appMusicDir(this), name);
            try (InputStream input = getContentResolver().openInputStream(uri);
                 FileOutputStream output = new FileOutputStream(target)) {
                if (input == null) {
                    return;
                }
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
            }
            MusicLibrary.scan(this, target);
            Toast.makeText(this, "Added " + target.getName(), Toast.LENGTH_SHORT).show();
            reloadLibrary();
        } catch (Exception e) {
            Toast.makeText(this, "Add song failed", Toast.LENGTH_SHORT).show();
        }
    }

    private void renderState() {
        if (service == null) {
            return;
        }
        if (service.tracksVersion() != knownTracksVersion) {
            syncTracksFromService();
        }
        Track current = service.currentTrack();
        if (current == null) {
            title.setText(tracks.isEmpty() ? "No music found" : "Ready");
            subtitle.setText(tracks.isEmpty() ? "Grant media permission or add downloaded music/video" : "Choose a song or MP4");
            artworkView.setVisibility(View.VISIBLE);
            artworkView.setImageResource(R.drawable.ic_music);
            videoView.setVisibility(View.GONE);
            loadedArtworkTrackId = -1L;
        } else {
            title.setText(current.title);
            subtitle.setText((current.video ? "Video" : "Audio") + " - " + current.artist + " - " + current.album);
            videoView.setVisibility(current.video ? View.VISIBLE : View.GONE);
            artworkView.setVisibility(current.video ? View.GONE : View.VISIBLE);
            if (!current.video && loadedArtworkTrackId != current.id) {
                loadArtwork(current);
                loadedArtworkTrackId = current.id;
                artworkView.setAlpha(0f);
                artworkView.setScaleX(0.96f);
                artworkView.setScaleY(0.96f);
                artworkView.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(240).setInterpolator(new DecelerateInterpolator()).start();
            }
        }
        playPause.setText(service.isPlaying() ? "Pause" : "Play");
        if (shuffleButton != null) {
            shuffleButton.setText("Shuffle");
            setButtonColor(shuffleButton, service.shuffleEnabled());
        }
        int duration = service.durationMs();
        int progress = duration <= 0 ? 0 : Math.min(1000, Math.max(0, (int) ((service.positionMs() * 1000L) / duration)));
        seekBar.setProgress(progress);
        String sleep = service.sleepRemainingMs() > 0 ? " - stops in " + formatTime((int) service.sleepRemainingMs()) : "";
        timeInfo.setText(formatTime(service.positionMs()) + " / " + formatTime(duration) + sleep);
        connectInfo.setText("WiFi control: http://" + localHint() + ":" + service.connectPort() + "/status");
        adapter.notifyDataSetChanged();
    }

    private void setButtonColor(Button button, boolean active) {
        button.setTextColor(active ? primaryTextColor() : textColor());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            button.setBackground(rounded(active ? accentColor() : buttonColor(), dp(999)));
        }
    }

    private void attachVideoSurfaceIfReady() {
        if (service != null && videoSurface != null) {
            service.setVideoSurface(videoSurface);
        }
    }

    private String formatTime(int millis) {
        int totalSeconds = Math.max(0, millis / 1000);
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        return minutes + ":" + (seconds < 10 ? "0" : "") + seconds;
    }

    private void loadArtwork(Track track) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(this, Uri.parse(track.uri));
            byte[] data = retriever.getEmbeddedPicture();
            if (data != null && data.length > 0) {
                Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
                artworkView.setImageBitmap(bitmap);
            } else {
                artworkView.setImageResource(R.drawable.ic_music);
            }
        } catch (Exception ignored) {
            artworkView.setImageResource(R.drawable.ic_music);
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }

    private String localHint() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface networkInterface : interfaces) {
                List<InetAddress> addresses = Collections.list(networkInterface.getInetAddresses());
                for (InetAddress address : addresses) {
                    if (!address.isLoopbackAddress() && address instanceof Inet4Address) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "phone-ip";
    }

    private void requestNeededPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        List<String> needed = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33) {
            addIfMissing(needed, Manifest.permission.READ_MEDIA_AUDIO);
            addIfMissing(needed, Manifest.permission.READ_MEDIA_VIDEO);
            addIfMissing(needed, Manifest.permission.POST_NOTIFICATIONS);
        } else {
            addIfMissing(needed, Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        if (Build.VERSION.SDK_INT >= 31) {
            addIfMissing(needed, Manifest.permission.BLUETOOTH_CONNECT);
            addIfMissing(needed, Manifest.permission.BLUETOOTH_SCAN);
        }
        if (!needed.isEmpty()) {
            requestPermissions(needed.toArray(new String[0]), REQUEST_PERMISSIONS);
        }
    }

    private void requestBatteryOptimizationBypass() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        try {
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (powerManager == null || powerManager.isIgnoringBatteryOptimizations(getPackageName())) {
                return;
            }
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception ignored) {
        }
    }

    private void addIfMissing(List<String> permissions, String permission) {
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(permission);
        }
    }

    private int backgroundColor() {
        return Color.rgb(16, 20, 24);
    }

    private int panelColor() {
        return Color.rgb(26, 32, 39);
    }

    private int inputColor() {
        return Color.rgb(16, 24, 32);
    }

    private int buttonColor() {
        return Color.rgb(36, 48, 58);
    }

    private int textColor() {
        return Color.rgb(244, 247, 250);
    }

    private int mutedColor() {
        return Color.rgb(169, 179, 189);
    }

    private int primaryTextColor() {
        return Color.rgb(7, 16, 10);
    }

    private int accentColor() {
        return Color.rgb(34, 197, 94);
    }

    private void disallowParentIntercept(View view) {
        ViewParent parent = view.getParent();
        while (parent != null) {
            parent.requestDisallowInterceptTouchEvent(true);
            parent = parent.getParent();
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private boolean compactUi() {
        return getResources().getDisplayMetrics().widthPixels < dp(390);
    }

    private int controlSecondarySize() {
        int available = getResources().getDisplayMetrics().widthPixels - dp(compactUi() ? 56 : 100);
        int size = Math.max(dp(40), Math.min(dp(48), available / 6));
        return size;
    }

    private int controlPrimarySize() {
        return controlSecondarySize() + dp(compactUi() ? 6 : 10);
    }

    private int mediaHeight() {
        int width = getResources().getDisplayMetrics().widthPixels - dp(compactUi() ? 24 : 36);
        int byRatio = Math.max(dp(150), width * 9 / 16);
        int byHeight = Math.max(dp(150), (int) (getResources().getDisplayMetrics().heightPixels * 0.30f));
        return Math.min(dp(240), Math.min(byRatio, byHeight));
    }

    private int listHeight() {
        int height = getResources().getDisplayMetrics().heightPixels;
        return Math.max(dp(220), (int) (height * (compactUi() ? 0.36f : 0.42f)));
    }
}
