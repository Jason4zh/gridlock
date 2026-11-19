package com.jason.bbccourses.ui;

import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.common.util.concurrent.ListenableFuture;
import com.jason.bbccourses.App;
import com.jason.bbccourses.R;
import com.jason.bbccourses.data.Store;
import com.jason.bbccourses.model.Lesson;
import com.jason.bbccourses.model.TranscriptLine;
import com.jason.bbccourses.net.Translator;
import com.jason.bbccourses.playback.PlaybackService;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PlayerActivity extends AppCompatActivity {

    public static final String EXTRA_SLUG = "lesson_slug";
    public static final String EXTRA_PROGRAMME = "programme_slug";

    private static final float[] SPEEDS = {0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 0.5f};

    private String slug, programmeSlug;
    private Lesson lesson;

    private MediaController controller;
    private ListenableFuture<MediaController> controllerFuture;

    private TranscriptAdapter adapter;
    private RecyclerView transcriptView;
    private TextView curView, durView, metaView;
    private SeekBar seekBar;
    private ImageButton playButton;
    private Button speedButton, zhButton, autoButton, favButton;

    private final Handler ticker = new Handler(Looper.getMainLooper());
    private final ExecutorService translatePool = Executors.newSingleThreadExecutor();

    private int[] cum;
    private int totalChars = 1;
    private boolean userSeeking = false;
    private boolean pendingAutoStart = false;
    private long pendingStartPos = -1;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);

        slug = getIntent().getStringExtra(EXTRA_SLUG);
        programmeSlug = getIntent().getStringExtra(EXTRA_PROGRAMME);
        if (slug == null) {
            finish();
            return;
        }

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationIcon(R.drawable.ic_back);
        toolbar.setNavigationOnClickListener(v -> finish());
        setSupportActionBar(toolbar);

        metaView = findViewById(R.id.meta);
        curView = findViewById(R.id.cur);
        durView = findViewById(R.id.dur);
        seekBar = findViewById(R.id.seek);
        playButton = findViewById(R.id.play);
        speedButton = findViewById(R.id.speed);
        zhButton = findViewById(R.id.zh);
        autoButton = findViewById(R.id.autoScroll);
        favButton = findViewById(R.id.fav);
        transcriptView = findViewById(R.id.transcript);

        adapter = new TranscriptAdapter(new TranscriptAdapter.OnLineClick() {
            @Override
            public void onClick(int index, TranscriptLine line) {
                onLineClicked(index, line);
            }

            @Override
            public void onLongClick(int index, TranscriptLine line) {
                onLineLongClicked(index);
            }
        });
        transcriptView.setLayoutManager(new LinearLayoutManager(this));
        transcriptView.setAdapter(adapter);

        seekBar.setMax(1000);

        playButton.setOnClickListener(v -> togglePlay());
        findViewById(R.id.minus10).setOnClickListener(v -> seekBy(-10000));
        findViewById(R.id.plus10).setOnClickListener(v -> seekBy(10000));
        speedButton.setOnClickListener(v -> cycleSpeed());
        zhButton.setOnClickListener(v -> toggleZh());
        autoButton.setOnClickListener(v -> toggleAutoScroll());
        favButton.setOnClickListener(v -> toggleFavourite());
        findViewById(R.id.pdf).setOnClickListener(v -> openPdf());

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser) {
                    long dur = duration();
                    curView.setText(fmt(dur * progress / 1000));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
                userSeeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                userSeeking = false;
                if (controller != null) {
                    long dur = duration();
                    if (dur > 0) controller.seekTo(dur * sb.getProgress() / 1000);
                }
            }
        });

        updateToggleLabels();
        metaView.setText("正在获取文稿…");
        loadLesson();
    }

    // ------------------------------------------------------------------ data
    private void loadLesson() {
        App.app().io().execute(() -> {
            Lesson l = null;
            try {
                l = App.app().repo().lesson(slug, programmeSlug);
            } catch (Exception ignored) {
            }
            final Lesson result = l;
            runOnUiThread(() -> {
                if (result == null) {
                    Toast.makeText(this, "文稿获取失败，请检查网络后重试", Toast.LENGTH_LONG).show();
                    finish();
                    return;
                }
                lesson = result;
                bindLesson();
            });
        });
    }

    private void bindLesson() {
        setTitle(lesson.shortTitle());
        StringBuilder sb = new StringBuilder();
        if (!lesson.programme.isEmpty()) sb.append(lesson.programme);
        if (!lesson.episode.isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append("第 ").append(lesson.episode).append(" 期");
        }
        if (!lesson.date.isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(lesson.date);
        }
        sb.append("  ·  句子高亮为按字数估算");
        sb.append("  ·  v").append(appVersion());
        metaView.setText(sb.toString());

        // belt and braces: never show raw scrape noise
        lesson.transcript = com.jason.bbccourses.model.TranscriptCleaner.clean(lesson.transcript);

        buildTimeline();
        boolean notes = !lesson.hasTranscript();
        notesMode = notes;
        List<TranscriptLine> rows;
        if (notes) {
            rows = buildNoteRows(lesson);
            metaView.setText(metaView.getText() + "  ·  本期官方无逐句文稿：以下为新闻摘要/标题/关键词讲解");
        } else {
            rows = lesson.transcript;
        }
        adapter.setShowZh(Store.get(this).zhEnabled());
        adapter.submit(rows);
        displayLines = rows;

        pendingStartPos = Store.get(this).progress(lesson.slug);
        if (controller != null) {
            startPlayback();
        }
        if (Store.get(this).zhEnabled()) startBulkTranslation();
    }

    private void buildTimeline() {
        cum = new int[lesson.transcript.size()];
        int acc = 0;
        for (int i = 0; i < lesson.transcript.size(); i++) {
            cum[i] = acc;
            acc += Math.max(12, lesson.transcript.get(i).chars);
        }
        totalChars = Math.max(1, acc);
    }

    /** Notes shown for programmes that publish no word-by-word transcript. */
    private List<TranscriptLine> buildNoteRows(Lesson l) {
        List<TranscriptLine> rows = new ArrayList<>();
        if (l.summary != null && !l.summary.isEmpty()) {
            for (String p : l.summary.split("\n\n")) {
                if (!p.trim().isEmpty()) rows.add(new TranscriptLine("THE STORY", p.trim()));
            }
        }
        for (String h : l.headlines) {
            if (!h.trim().isEmpty()) rows.add(new TranscriptLine("HEADLINE", h.trim()));
        }
        for (Lesson.Keyword k : l.keywords) {
            StringBuilder sb = new StringBuilder(k.definition);
            if (!k.example.isEmpty()) sb.append("\n").append(k.example);
            rows.add(new TranscriptLine(k.word.toUpperCase(java.util.Locale.ROOT), sb.toString()));
        }
        return rows;
    }

    // ------------------------------------------------------------------ playback
    @Override
    protected void onStart() {
        super.onStart();
        SessionToken token = new SessionToken(this, new ComponentName(this, PlaybackService.class));
        controllerFuture = new MediaController.Builder(this, token).buildAsync();
        controllerFuture.addListener(() -> {
            try {
                controller = controllerFuture.get();
                controller.addListener(playerListener);
                controller.setPlaybackParameters(new PlaybackParameters(Store.get(this).speed()));
                if (lesson != null) startPlayback();
                startTicker();
            } catch (Throwable e) {
                android.util.Log.e("BBC6Min", "controller init failed", e);
                Toast.makeText(this, "播放器初始化失败：" + e, Toast.LENGTH_LONG).show();
            }
        }, command -> ticker.post(command));
    }

    @Override
    protected void onStop() {
        saveProgress();
        ticker.removeCallbacks(tick);
        if (controllerFuture != null) {
            MediaController.releaseFuture(controllerFuture);
            controllerFuture = null;
        }
        controller = null;
        super.onStop();
    }

    /** Local file when the user downloaded this episode, otherwise the stream URL. */
    private String audioUri() {
        if (lesson == null) return "";
        java.io.File f = com.jason.bbccourses.net.Downloader.audioFile(this, lesson.slug);
        if (f.exists() && f.length() > 1024) {
            return android.net.Uri.fromFile(f).toString();
        }
        return lesson.audioUrl;
    }

    private void startPlayback() {
        if (controller == null || lesson == null) return;
        MediaItem current = controller.getCurrentMediaItem();
        boolean same = current != null && slug.equals(current.mediaId);
        if (!same) {
            MediaItem item = new MediaItem.Builder()
                    .setUri(audioUri())
                    .setMediaId(lesson.slug)
                    .setMediaMetadata(new MediaMetadata.Builder()
                            .setTitle(lesson.shortTitle())
                            .setArtist(lesson.programme)
                            .build())
                    .build();
            long start = pendingStartPos > 5000 ? pendingStartPos : 0;
            controller.setMediaItem(item, start);
            controller.prepare();
            controller.play();
        } else if (pendingAutoStart) {
            controller.play();
        }
        pendingAutoStart = false;
        updatePlayIcon();
    }

    private void togglePlay() {
        if (controller == null) {
            pendingAutoStart = true;
            return;
        }
        if (controller.isPlaying()) controller.pause();
        else {
            if (controller.getPlaybackState() == Player.STATE_IDLE) startPlayback();
            else controller.play();
        }
        updatePlayIcon();
    }

    private void seekBy(long deltaMs) {
        if (controller == null) return;
        long pos = controller.getCurrentPosition() + deltaMs;
        long dur = duration();
        if (pos < 0) pos = 0;
        if (dur > 0 && pos > dur) pos = dur;
        controller.seekTo(pos);
    }

    private long duration() {
        if (controller == null) return 0;
        long d = controller.getDuration();
        if (d <= 0) {
            d = Store.get(this).duration(slug);
        }
        return d;
    }

    private void cycleSpeed() {
        Store store = Store.get(this);
        float cur = store.speed();
        float next = SPEEDS[0];
        for (int i = 0; i < SPEEDS.length; i++) {
            if (Math.abs(SPEEDS[i] - cur) < 0.01f) {
                next = SPEEDS[(i + 1) % SPEEDS.length];
                break;
            }
        }
        store.setSpeed(next);
        if (controller != null) controller.setPlaybackParameters(new PlaybackParameters(next));
        updateToggleLabels();
    }

    private void updateToggleLabels() {
        speedButton.setText(String.format(java.util.Locale.getDefault(), "%.2fx", Store.get(this).speed()));
        zhButton.setText(Store.get(this).zhEnabled() ? "中文对照 开" : "中文对照");
        autoButton.setText(Store.get(this).autoScroll() ? "自动滚动 开" : "自动滚动");
        favButton.setText(Store.get(this).isFavourite(slug) ? "已收藏 ★" : "收藏");
        updatePlayIcon();
    }

    private void updatePlayIcon() {
        boolean playing = controller != null && controller.isPlaying();
        playButton.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
    }

    private final Player.Listener playerListener = new Player.Listener() {
        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            updatePlayIcon();
        }

        @Override
        public void onPlaybackStateChanged(int state) {
            if (state == Player.STATE_READY) {
                long d = controller == null ? 0 : controller.getDuration();
                durView.setText(fmt(d));
            }
        }

        @Override
        public void onPlayerError(PlaybackException error) {
            Toast.makeText(PlayerActivity.this,
                    "音频加载失败（downloads.bbc.co.uk 可能需要开启加速器）", Toast.LENGTH_LONG).show();
        }
    };

    private void startTicker() {
        ticker.removeCallbacks(tick);
        ticker.post(tick);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateProgressUi();
            ticker.postDelayed(this, 500);
        }
    };

    private void updateProgressUi() {
        if (controller == null) return;
        long pos = controller.getCurrentPosition();
        long dur = duration();
        if (loopEnd > 0 && (pos > loopEnd || pos < loopStart - 1500)) {
            controller.seekTo(loopStart);
            pos = loopStart;
        }
        if (dur > 0) {
            durView.setText(fmt(dur));
            if (!userSeeking) {
                seekBar.setProgress((int) Math.min(1000, pos * 1000 / dur));
            }
        }
        curView.setText(fmt(pos));
        int index = notesMode ? -1 : estimateLine(pos, dur);
        if (index != adapter.highlight()) {
            adapter.setHighlight(index);
            if (Store.get(this).autoScroll() && index >= 0) {
                ((LinearLayoutManager) transcriptView.getLayoutManager())
                        .scrollToPositionWithOffset(Math.max(0, index - 2), 0);
            }
        }
    }

    /** No timestamps are published, so map playback position -> line by character count. */
    private int estimateLine(long pos, long dur) {
        if (cum == null || cum.length == 0 || dur <= 0) return -1;
        double ratio = Math.min(1.0, Math.max(0.0, (double) pos / (double) dur));
        int target = (int) (ratio * totalChars);
        int lo = 0, hi = cum.length - 1, ans = 0;
        while (lo <= hi) {
            int mid = (lo + hi) / 2;
            if (cum[mid] <= target) {
                ans = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return ans;
    }

    // ------------------------------------------------------------------ translation
    private void toggleZh() {
        Store store = Store.get(this);
        boolean on = !store.zhEnabled();
        store.setZhEnabled(on);
        adapter.setShowZh(on);
        updateToggleLabels();
        if (on) startBulkTranslation();
    }

    private void toggleAutoScroll() {
        Store store = Store.get(this);
        store.setAutoScroll(!store.autoScroll());
        updateToggleLabels();
    }

    private void toggleFavourite() {
        Store.get(this).toggleFavourite(slug);
        updateToggleLabels();
        Toast.makeText(this, Store.get(this).isFavourite(slug) ? "已添加到收藏" : "已取消收藏",
                Toast.LENGTH_SHORT).show();
    }

    private void toggleFavouriteQuiet() {
        Store.get(this).toggleFavourite(slug);
        updateToggleLabels();
    }

    private void startBulkTranslation() {
        if (displayLines == null || displayLines.isEmpty()) return;
        final Translator tr = App.app().translator();
        translatePool.execute(() -> {
            for (int i = 0; i < displayLines.size(); i++) {
                if (isFinishing()) return;
                TranscriptLine line = displayLines.get(i);
                if (line.zh != null && !line.zh.isEmpty()) continue;
                boolean cached = tr.cached(line.text) != null;
                String zh = tr.translate(line.text);
                if (zh != null) {
                    line.zh = zh;
                    final int idx = i;
                    runOnUiThread(() -> adapter.refreshLine(idx));
                }
                if (!cached) {
                    try {
                        Thread.sleep(120);
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        });
    }

    private long loopStart = -1, loopEnd = -1;
    private boolean notesMode = false;
    private List<TranscriptLine> displayLines = new ArrayList<>();

    private void onLineLongClicked(int index) {
        if (notesMode) {
            Toast.makeText(this, "本期没有逐句文稿，无法按句循环", Toast.LENGTH_SHORT).show();
            return;
        }
        long dur = duration();
        if (dur <= 0) {
            Toast.makeText(this, "音频还没准备好", Toast.LENGTH_SHORT).show();
            return;
        }
        long start = (long) cum[index] * dur / totalChars;
        int endChars = index + 1 < cum.length ? cum[index + 1] : totalChars;
        long end = (long) endChars * dur / totalChars;
        if (loopEnd > 0 && Math.abs(loopStart - start) < 800) {
            loopStart = loopEnd = -1;
            Toast.makeText(this, "已取消单句循环", Toast.LENGTH_SHORT).show();
            return;
        }
        loopStart = start;
        loopEnd = end;
        if (controller != null) {
            controller.seekTo(start);
            controller.play();
        }
        Toast.makeText(this, "单句循环（近似区间）已开启，长按同句可取消", Toast.LENGTH_SHORT).show();
    }

    private void onLineClicked(int index, TranscriptLine line) {
        if (line.zh != null && !line.zh.isEmpty()) {
            // already translated: copy english + chinese to clipboard
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText("transcript",
                        line.text + "\n" + line.zh));
                Toast.makeText(this, "已复制该句（英文+中文）", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        Toast.makeText(this, "正在翻译该句…", Toast.LENGTH_SHORT).show();
        translatePool.execute(() -> {
            String zh = App.app().translator().translate(line.text);
            if (zh != null) line.zh = zh;
            runOnUiThread(() -> adapter.refreshLine(index));
        });
    }

    // ------------------------------------------------------------------ misc
    private void openPdf() {
        if (lesson == null || lesson.pdfUrl == null || lesson.pdfUrl.isEmpty()) {
            Toast.makeText(this, "本期没有讲义 PDF", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(lesson.pdfUrl)));
        } catch (Exception e) {
            Toast.makeText(this, "没有可打开 PDF 的应用", Toast.LENGTH_SHORT).show();
        }
    }

    private void saveProgress() {
        if (controller == null || lesson == null) return;
        long pos = controller.getCurrentPosition();
        long dur = controller.getDuration();
        if (pos > 0) Store.get(this).saveProgress(lesson.slug, pos, dur > 0 ? dur : 0);
    }

    @Override
    protected void onDestroy() {
        saveProgress();
        translatePool.shutdownNow();
        super.onDestroy();
    }
    @Override
    public boolean onCreateOptionsMenu(android.view.Menu menu) {
        menu.add(0, 20, 0, "小测验");
        menu.add(0, 21, 1, "重新获取文稿");
        menu.add(0, 22, 2, "文稿诊断");
        return true;
    }

    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    /** Force a refetch (drops the on-disk cache too) — for "文稿不对" troubleshooting. */
    private void refetchTranscript() {
        Toast.makeText(this, "正在重新获取文稿…", Toast.LENGTH_SHORT).show();
        App.app().io().execute(() -> {
            try {
                Lesson fresh = App.app().repo().lesson(slug, programmeSlug, true);
                runOnUiThread(() -> {
                    lesson = fresh;
                    bindLesson();
                    Toast.makeText(this, "已更新，共 " + fresh.transcript.size() + " 行", Toast.LENGTH_LONG).show();
                });
            } catch (Throwable e) {
                runOnUiThread(() -> Toast.makeText(this, "重取失败：" + e, Toast.LENGTH_LONG).show());
            }
        });
    }

    /** Prints what the scraper actually got, so failures are visible instead of silent. */
    private void diagnose() {
        App.app().io().execute(() -> {
            StringBuilder sb = new StringBuilder();
            sb.append("slug: ").append(slug).append('\n');
            sb.append("programme: ").append(programmeSlug).append('\n');
            sb.append("current rows: ").append(lesson == null ? -1 : lesson.transcript.size()).append('\n');
            try {
                String url = com.jason.bbccourses.net.BbcScraper.BASE + "/lesson/" + slug;
                String html = com.jason.bbccourses.net.Http.get(url);
                String payload = com.jason.bbccourses.net.BbcScraper.flightPayload(html);
                int chunks = com.jason.bbccourses.net.BbcScraper.textChunks(payload).size();
                sb.append("url: ").append(url).append('\n');
                sb.append("html bytes: ").append(html == null ? -1 : html.length()).append('\n');
                sb.append("payload chars: ").append(payload.length()).append('\n');
                sb.append("T-chunks: ").append(chunks).append('\n');
                sb.append("has \"lesson\": ").append(payload.contains("\"lesson\":")).append('\n');
                Lesson fresh = com.jason.bbccourses.net.BbcScraper.fetchLesson(slug, programmeSlug);
                sb.append("fresh rows: ").append(fresh.transcript.size()).append('\n');
                if (!fresh.transcript.isEmpty()) {
                    sb.append("first: ").append(fresh.transcript.get(0).text, 0,
                            Math.min(80, fresh.transcript.get(0).text.length()));
                }
            } catch (Throwable e) {
                sb.append("ERROR: ").append(e.getClass().getSimpleName()).append(": ").append(e.getMessage());
            }
            final String msg = sb.toString();
            runOnUiThread(() -> new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("文稿诊断")
                    .setMessage(msg)
                    .setPositiveButton("好", null)
                    .show());
        });
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        if (item.getItemId() == 20) {
            openQuiz();
            return true;
        }
        if (item.getItemId() == 21) {
            refetchTranscript();
            return true;
        }
        if (item.getItemId() == 22) {
            diagnose();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /** Quiz is generated on the fly from the worksheet PDF (see QuizActivity). */
    private void openQuiz() {
        if (lesson == null) {
            Toast.makeText(this, "文稿还在加载，请稍后再试", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(this, QuizActivity.class);
        i.putExtra(QuizActivity.EXTRA_SLUG, lesson.slug);
        i.putExtra(QuizActivity.EXTRA_PDF, lesson.pdfUrl);
        i.putExtra(QuizActivity.EXTRA_TITLE, lesson.shortTitle());
        startActivity(i);
    }

    private static String fmt(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000;
        if (s >= 3600) return String.format("%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60);
        return String.format("%d:%02d", s / 60, s % 60);
    }
}