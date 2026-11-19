package com.jason.bbccourses.data;

import android.content.Context;
import android.content.res.AssetManager;

import com.jason.bbccourses.model.Lesson;
import com.jason.bbccourses.model.Programme;
import com.jason.bbccourses.model.TranscriptCleaner;
import com.jason.bbccourses.net.BbcScraper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Single source of truth for catalogue data.
 *
 * Bundled (assets/data): programmes.json + lessons/&lt;programme-slug&gt;.json
 * Anything missing is fetched live from the mirror site and cached on disk.
 */
public class Repository {

    public static final String PROGRAMME_6ME = "6-minute-english";
    public static final String PROGRAMME_NEWS = "learning-english-from-the-news";

    /** programme slug -> prefix of its lesson slugs (used to complete lists) */
    private static final Map<String, String> LESSON_PREFIX = new HashMap<>();
    static {
        LESSON_PREFIX.put(PROGRAMME_6ME, "6-minute-english-");
        LESSON_PREFIX.put(PROGRAMME_NEWS, "learning-english-from-the-news-");
    }

    private static Repository instance;

    public static synchronized Repository get(Context ctx) {
        if (instance == null) instance = new Repository(ctx.getApplicationContext());
        return instance;
    }

    private final Context ctx;
    private final File lessonCacheDir;
    private List<Programme> programmes;
    private final Map<String, List<Lesson>> lessonsByProgramme = new HashMap<>();
    private final Map<String, Lesson> lessonCache = new HashMap<>();

    private Repository(Context c) {
        ctx = c;
        lessonCacheDir = new File(c.getFilesDir(), "lessons");
        if (!lessonCacheDir.exists()) lessonCacheDir.mkdirs();
    }

    // ------------------------------------------------------------------ programmes
    public synchronized List<Programme> programmes() {
        if (programmes != null) return programmes;
        List<Programme> list = new ArrayList<>();
        try {
            String json = readAsset("data/programmes.json");
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) list.add(Programme.fromJson(o));
            }
        } catch (Exception ignored) {
        }
        // 6 Minute English first, the rest alphabetical
        list.sort((a, b) -> {
            boolean am = PROGRAMME_6ME.equals(a.slug);
            boolean bm = PROGRAMME_6ME.equals(b.slug);
            if (am != bm) return am ? -1 : 1;
            return a.title.compareToIgnoreCase(b.title);
        });
        programmes = list;
        return list;
    }

    public Programme programme(String slug) {
        for (Programme p : programmes()) if (p.slug.equals(slug)) return p;
        return null;
    }

    // ------------------------------------------------------------------ lessons of a programme
    /** Full offline list for programmes that were bundled, otherwise the 24 latest from the site. */
    public synchronized List<Lesson> lessons(String programmeSlug) {
        if (lessonsByProgramme.containsKey(programmeSlug)) return lessonsByProgramme.get(programmeSlug);
        List<Lesson> list = new ArrayList<>();
        try {
            String json = readAsset("data/lessons/" + programmeSlug + ".json");
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Lesson l = Lesson.fromJson(o);
                l.programmeSlug = programmeSlug;
                if (l.programme.isEmpty()) {
                    Programme p = programme(programmeSlug);
                    if (p != null) l.programme = p.title;
                }
                list.add(l);
                lessonCache.put(l.slug, l);
            }
            list.sort((a, b) -> b.episode.compareTo(a.episode));
        } catch (Exception ignored) {
            list = null;
        }
        if (list == null) {
            Programme p = programme(programmeSlug);
            list = p == null ? new ArrayList<>() : new ArrayList<>(p.latest);
        }
        // Fill in episodes we know exist (from the bundled sitemap) but have no
        // offline data for yet: they show up in the list and are fetched live
        // when opened.
        String prefix = LESSON_PREFIX.get(programmeSlug);
        if (prefix != null) {
            java.util.Set<String> known = new java.util.HashSet<>();
            for (Lesson l : list) known.add(l.slug);
            for (String s : sitemapSlugs()) {
                if (!s.startsWith(prefix) || known.contains(s)) continue;
                Lesson l = new Lesson();
                l.slug = s;
                l.programmeSlug = programmeSlug;
                Programme p = programme(programmeSlug);
                l.programme = p == null ? programmeSlug : p.title;
                l.title = prettify(s, prefix);
                list.add(l);
            }
            list.sort((a, b) -> b.episode.compareTo(a.episode));
        }
        lessonsByProgramme.put(programmeSlug, list);
        return list;
    }

    public synchronized void putLessons(String programmeSlug, List<Lesson> list) {
        lessonsByProgramme.put(programmeSlug, list);
    }

    /** Refresh a programme from the live site (24 newest episodes). */
    public List<Lesson> refreshProgramme(String programmeSlug) throws Exception {
        Programme p = programme(programmeSlug);
        if (p == null) {
            p = new Programme();
            p.slug = programmeSlug;
        }
        BbcScraper.fillProgramme(p);
        synchronized (this) {
            // keep any bundled lessons, but merge refreshed cards in front
            List<Lesson> merged = new ArrayList<>();
            Map<String, Boolean> seen = new HashMap<>();
            for (Lesson l : p.latest) {
                merged.add(l);
                seen.put(l.slug, true);
            }
            List<Lesson> existing = lessonsByProgramme.get(programmeSlug);
            if (existing != null) {
                for (Lesson l : existing) {
                    if (!seen.containsKey(l.slug)) merged.add(l);
                }
            }
            lessonsByProgramme.put(programmeSlug, merged);
            return merged;
        }
    }

    // ------------------------------------------------------------------ lesson detail
    public synchronized Lesson cachedLesson(String slug) {
        return lessonCache.get(slug);
    }
public Lesson lesson(String slug, String programmeSlug) throws Exception {
        return lesson(slug, programmeSlug, false);
    }

    /** @param force refetch even if we already have rows (used by the "诊断/重取" action). */
    public Lesson lesson(String slug, String programmeSlug, boolean force) throws Exception {
        Lesson l;
        synchronized (this) {
            l = lessonCache.get(slug);
            // only short-circuit when this lesson already went through the full
            // pipeline (cleaning + completeness check); the catalogue loader also
            // fills lessonCache with raw rows.
            if (l != null && prepared.contains(slug) && !force) return l;
        }
        if (force) {
            synchronized (this) {
                prepared.remove(slug);
                cleaned.remove(slug);
                lessonCache.remove(slug);
            }
            File cached = new File(lessonCacheDir, slug + ".json");
            if (cached.exists()) cached.delete();
            l = null;
        }

        // 1) bundled catalogue
        if (l == null) {
            for (Lesson cand : lessons(programmeSlug)) {
                if (cand.slug.equals(slug) && cand.hasTranscript()) {
                    l = cand;
                    break;
                }
            }
        }
        // 2) disk cache
        boolean fromDisk = false;
        if (l == null) {
            File f = new File(lessonCacheDir, slug + ".json");
            if (f.exists()) {
                try {
                    l = Lesson.fromJson(new JSONObject(readFile(f)));
                    fromDisk = l != null;
                } catch (Exception ignored) {
                }
            }
        }
        // 3) live scrape — also used to repair empty / truncated transcripts
        boolean hasNotes = l != null && ((l.summary != null && !l.summary.isEmpty())
                || (l.headlines != null && !l.headlines.isEmpty())
                || (l.keywords != null && !l.keywords.isEmpty()));
        boolean suspicious = !hasNotes && (l == null || !l.hasTranscript() || l.transcript.size() < MIN_ROWS);
        if (suspicious) {
            try {
                Lesson fresh = BbcScraper.fetchLesson(slug, programmeSlug);
                int oldRows = (l == null || l.transcript == null) ? 0 : l.transcript.size();
                if (fresh.hasTranscript() && fresh.transcript.size() > oldRows) {
                    l = fresh;
                    fromDisk = false;
                    try {
                        writeFile(new File(lessonCacheDir, slug + ".json"), lessonJson(l).toString());
                    } catch (Exception ignored) {
                    }
                } else if (l == null) {
                    l = fresh;
                }
            } catch (Exception e) {
                if (l == null) throw e;
                android.util.Log.w("BBC6Min", "refetch failed for " + slug, e);
            }
        }
        if (!cleaned.contains(slug)) {
            l.transcript = TranscriptCleaner.clean(l.transcript);
            cleaned.add(slug);
        }
        // A transcript only counts as "done" when it is actually complete; otherwise
        // we must NOT cache it as prepared, so the next open retries the refetch.
        boolean complete = hasNotes || (l.hasTranscript() && l.transcript.size() >= MIN_ROWS);
        if (!complete && fromDisk) {
            File bad = new File(lessonCacheDir, slug + ".json");
            if (bad.exists()) bad.delete();
        }
        synchronized (this) {
            lessonCache.put(slug, l);
            if (complete || force) {
                prepared.add(slug);
            } else {
                prepared.remove(slug);
            }
        }
        return l;
    }

    private static final int MIN_ROWS = 25;
    private final java.util.Set<String> cleaned = new java.util.HashSet<>();
    private final java.util.Set<String> prepared = new java.util.HashSet<>();

    private JSONObject lessonJson(Lesson l) throws Exception {
        JSONObject o = new JSONObject();
        o.put("slug", l.slug);
        o.put("title", l.title);
        o.put("description", l.description);
        o.put("programme", l.programme);
        o.put("programmeSlug", l.programmeSlug);
        o.put("episode", l.episode);
        o.put("date", l.date);
        o.put("level", l.level);
        o.put("imageUrl", l.imageUrl);
        o.put("audioUrl", l.audioUrl);
        o.put("pdfUrl", l.pdfUrl);
        o.put("url", l.url);
        o.put("summary", l.summary);
        JSONArray hl = new JSONArray();
        for (String h : l.headlines) hl.put(h);
        o.put("headlines", hl);
        JSONArray kws = new JSONArray();
        for (Lesson.Keyword k : l.keywords) kws.put(k.toJson());
        o.put("keywords", kws);
        JSONArray tr = new JSONArray();
        for (int i = 0; i < l.transcript.size(); i++) tr.put(l.transcript.get(i).toJson());
        o.put("transcript", tr);
        JSONArray tp = new JSONArray();
        for (String t : l.topics) tp.put(t);
        o.put("topics", tp);
        return o;
    }

    // ------------------------------------------------------------------ io helpers
    private java.util.Set<String> sitemapCache;

    private java.util.Set<String> sitemapSlugs() {
        if (sitemapCache != null) return sitemapCache;
        java.util.Set<String> out = new java.util.HashSet<>();
        try {
            JSONObject o = new JSONObject(readAsset("data/sitemap.json"));
            JSONArray arr = o.optJSONArray("lessons");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) out.add(arr.getString(i));
            }
        } catch (Exception ignored) {
        }
        sitemapCache = out;
        return out;
    }

    private static String prettify(String slug, String prefix) {
        String s = slug;
        if (prefix != null && s.startsWith(prefix)) s = s.substring(prefix.length());
        s = s.replace('-', ' ').trim();
        if (s.isEmpty()) return slug;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private String readAsset(String path) throws Exception {
        AssetManager am = ctx.getAssets();
        InputStream in = am.open(path);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String readFile(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void writeFile(File f, String s) throws Exception {
        FileOutputStream out = new FileOutputStream(f);
        out.write(s.getBytes(StandardCharsets.UTF_8));
        out.close();
    }
}