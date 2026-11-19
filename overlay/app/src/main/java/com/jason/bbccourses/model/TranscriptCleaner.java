package com.jason.bbccourses.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the raw scraped transcript into clean, readable lines.
 *
 * The mirror publishes the transcript in two shapes:
 *  - older episodes: one row per sentence, with a speaker field;
 *  - newer episodes: one row per speaker turn (a whole paragraph) where the next
 *    speaker's name is glued to the end of the previous sentence
 *    ("… I'm Georgie. NeilAnd I'm Neil."), plus a trailing block of site
 *    boilerplate and "related lesson" descriptions.
 *
 * So we: cut everything from the boilerplate tail, split glued speaker names
 * (including names that never appear as a speaker field), split long turns into
 * sentences, and drop noise rows.
 */
public class TranscriptCleaner {

    /** Once we meet one of these, the rest of the "transcript" is site junk. */
    private static final Pattern[] TAIL = new Pattern[]{
            Pattern.compile("(?i)^what do you think of the new version.*"),
            Pattern.compile("(?i)^find an a-z list.*"),
            Pattern.compile("(?i)^find more programmes for your level.*"),
            Pattern.compile("(?i)^practise your (reading|listening|speaking|writing) skills.*"),
            Pattern.compile("(?i)^listen to the full episode.*"),
            Pattern.compile("(?i)^find us on (facebook|instagram|twitter|youtube).*"),
            Pattern.compile("(?i)^subscribe .*bbclearningenglish.*"),
            Pattern.compile("(?i)^download (the|our) .*app.*")
    };

    private static final Pattern[] NOISE = new Pattern[]{
            Pattern.compile("(?i)^note:.*transcript.*$"),
            Pattern.compile("(?i)^this is (a|not a) .*transcript.*$"),
            Pattern.compile("(?i)^this is a transcript of a spoken conversation.*$"),
            Pattern.compile("(?i)^email us at .*$"),
            Pattern.compile("(?i)^bbc learning english$"),
            Pattern.compile("(?i)^copyright .*bbc.*$"),
            Pattern.compile("^\\s*[\\$\\u00a3\\u20ac]?[0-9][0-9.,:]*\\s*$"),
            Pattern.compile("^\\s*[\\-\\u2013\\u2014.~*#]+\\s*$"),
            Pattern.compile("^\\$[0-9a-f]{1,4}$")
    };

    /** "Name" or "First Last" glued in front of a new sentence: "NeilAnd I'm Neil." */
    private static final Pattern GLUE_CANDIDATE = Pattern.compile(
            "([A-Z][a-z]{2,}(?: [A-Z][a-z]{2,})?)(?=[A-Z][a-z]{2,})");

    private static final Pattern SENTENCE = Pattern.compile("(?<=[.!?\\u2019\\\"'])\\s+");

    private static final int MAX_LEN = 150;

    public static List<TranscriptLine> clean(List<TranscriptLine> in) {
        List<TranscriptLine> result = new ArrayList<>();
        if (in == null || in.isEmpty()) return result;

        // 1) cut the boilerplate tail
        List<TranscriptLine> kept = new ArrayList<>();
        for (TranscriptLine l : in) {
            String t = l.text == null ? "" : l.text.trim();
            if (isTail(t)) break;
            kept.add(l);
        }

        // 2) collect speaker names: explicit ones + names glued inside the text
        Set<String> names = new LinkedHashSet<>();
        for (TranscriptLine l : kept) {
            if (l.speaker != null && l.speaker.trim().length() > 2) names.add(l.speaker.trim());
        }
        StringBuilder all = new StringBuilder();
        for (TranscriptLine l : kept) {
            if (l.text != null) all.append(l.text).append('\n');
        }
        Map<String, Integer> counts = new HashMap<>();
        Matcher cm = GLUE_CANDIDATE.matcher(all.toString());
        while (cm.find()) {
            String cand = cm.group(1);
            Integer old = counts.get(cand);
            counts.put(cand, old == null ? 1 : old + 1);
        }
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() >= 2) names.add(e.getKey());
        }
        // a name may appear glued only once but stand alone elsewhere ("I'm Neil.")
        for (String cand : new ArrayList<>(counts.keySet())) {
            if (names.contains(cand)) continue;
            Matcher sm = Pattern.compile("\\b" + Pattern.quote(cand) + "\\b").matcher(all.toString());
            int standalone = 0;
            while (sm.find()) standalone++;
            if (counts.get(cand) + standalone >= 2) names.add(cand);
        }

        Pattern glued = null;
        if (!names.isEmpty()) {
            List<String> sorted = new ArrayList<>(names);
            Collections.sort(sorted, (a, b) -> b.length() - a.length());
            StringBuilder sb = new StringBuilder();
            for (String n : sorted) {
                if (sb.length() > 0) sb.append('|');
                sb.append(Pattern.quote(n));
            }
            glued = Pattern.compile("(" + sb + ")(?=[A-Z\\u2018\\u201c])");
        }

        // 3) split turns into sentences
        for (TranscriptLine l : kept) {
            String text = l.text == null ? "" : l.text;
            text = text.replace('\u00a0', ' ').replaceAll("[ \\t]{2,}", " ").trim();
            if (text.isEmpty() || isNoise(text)) continue;

            for (String[] turn : splitTurns(text, l.speaker, glued)) {
                for (String sentence : splitSentences(turn[1])) {
                    if (isNoise(sentence)) continue;
                    result.add(new TranscriptLine(turn[0], sentence));
                }
            }
        }
        return result;
    }

    /** Splits "… Georgie. NeilWell …" into (Georgie, …) and (Neil, Well …). */
    private static List<String[]> splitTurns(String text, String speaker, Pattern glued) {
        List<String[]> turns = new ArrayList<>();
        if (glued == null) {
            turns.add(new String[]{speaker, text});
            return turns;
        }
        Matcher m = glued.matcher(text);
        int last = 0;
        String cur = speaker;
        boolean any = false;
        while (m.find()) {
            String seg = text.substring(last, m.start()).trim();
            if (!seg.isEmpty()) {
                turns.add(new String[]{cur, seg});
                any = true;
            }
            cur = m.group(1);
            last = m.end();
        }
        if (!any) {
            turns.add(new String[]{speaker, text});
            return turns;
        }
        String tail = text.substring(last).trim();
        if (!tail.isEmpty()) turns.add(new String[]{cur, tail});
        return turns;
    }

    /** Split a long turn into sentence-sized pieces, merging very short fragments. */
    private static List<String> splitSentences(String text) {
        List<String> out = new ArrayList<>();
        if (text.length() <= MAX_LEN) {
            out.add(text);
            return out;
        }
        String[] parts = SENTENCE.split(text);
        StringBuilder buf = new StringBuilder();
        for (String p : parts) {
            String s = p.trim();
            if (s.isEmpty()) continue;
            if (buf.length() > 0 && buf.length() + s.length() + 1 > MAX_LEN) {
                out.add(buf.toString());
                buf.setLength(0);
            }
            if (buf.length() > 0) buf.append(' ');
            buf.append(s);
        }
        if (buf.length() > 0) out.add(buf.toString());
        if (out.isEmpty()) out.add(text);
        return out;
    }

    private static boolean isTail(String text) {
        for (Pattern p : TAIL) {
            if (p.matcher(text).matches()) return true;
        }
        return false;
    }

    private static boolean isNoise(String text) {
        for (Pattern p : NOISE) {
            if (p.matcher(text).matches()) return true;
        }
        return false;
    }
}