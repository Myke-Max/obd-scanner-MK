package pt.mkmedia.obdscanner;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** История проверок: каждая проверка ошибок сохраняется с VIN и датой. */
public final class HistoryStore {
    private HistoryStore() {}

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String FILE = "history.txt";
    private static final int MAX_ENTRIES = 500;

    public static final class Entry {
        public final long time;
        public final String vin;
        public final boolean mil;
        public final List<String> stored;
        public final List<String> pending;
        public final List<String> permanent;

        public Entry(long time, String vin, boolean mil,
                     List<String> stored, List<String> pending, List<String> permanent) {
            this.time = time;
            this.vin = vin;
            this.mil = mil;
            this.stored = stored;
            this.pending = pending;
            this.permanent = permanent;
        }

        public Set<String> all() {
            Set<String> s = new LinkedHashSet<>();
            s.addAll(stored);
            s.addAll(pending);
            s.addAll(permanent);
            return s;
        }
    }

    private static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) {
            if (sb.length() > 0) sb.append(',');
            sb.append(s);
        }
        return sb.toString();
    }

    private static List<String> split(String s) {
        List<String> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        for (String p : s.split(",")) {
            if (!p.isEmpty()) out.add(p);
        }
        return out;
    }

    private static String line(Entry e) {
        return e.time + "\t" + e.vin.replace('\t', ' ') + "\t" + (e.mil ? "1" : "0") + "\t"
                + join(e.stored) + "\t" + join(e.pending) + "\t" + join(e.permanent);
    }

    public static synchronized List<Entry> readAll(File dir) {
        List<Entry> out = new ArrayList<>();
        File f = new File(dir, FILE);
        if (!f.exists()) return out;
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), UTF8));
            try {
                String ln;
                while ((ln = r.readLine()) != null) {
                    String[] p = ln.split("\t", -1);
                    if (p.length < 6) continue;
                    try {
                        out.add(new Entry(Long.parseLong(p[0]), p[1], "1".equals(p[2]),
                                split(p[3]), split(p[4]), split(p[5])));
                    } catch (NumberFormatException ignored) {
                    }
                }
            } finally {
                r.close();
            }
        } catch (IOException ignored) {
        }
        return out;
    }

    public static synchronized void append(File dir, Entry e) {
        List<Entry> all = readAll(dir);
        all.add(e);
        if (all.size() > MAX_ENTRIES) all = all.subList(all.size() - MAX_ENTRIES, all.size());
        try {
            OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(new File(dir, FILE), false), UTF8);
            try {
                for (Entry x : all) w.write(line(x) + "\n");
            } finally {
                w.close();
            }
        } catch (IOException ignored) {
        }
    }

    /** Последняя проверка машины с таким же VIN (или null). */
    public static Entry lastFor(List<Entry> all, String vin) {
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i).vin.equals(vin)) return all.get(i);
        }
        return null;
    }

    public static String date(long t) {
        return new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.ROOT).format(new Date(t));
    }

    /** Что изменилось с прошлой проверки. */
    public static String diffText(Entry prev, Entry cur) {
        Set<String> before = prev.all();
        Set<String> now = cur.all();
        List<String> added = new ArrayList<>();
        List<String> gone = new ArrayList<>();
        List<String> same = new ArrayList<>();
        for (String c : now) {
            if (before.contains(c)) same.add(c);
            else added.add(c);
        }
        for (String c : before) {
            if (!now.contains(c)) gone.add(c);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("СРАВНЕНИЕ С ПРОШЛОЙ ПРОВЕРКОЙ (").append(date(prev.time)).append("):\n");
        if (added.isEmpty() && gone.isEmpty()) {
            sb.append("  Ничего не изменилось");
            sb.append(now.isEmpty() ? " — ошибок по-прежнему нет.\n" : ".\n");
        } else {
            sb.append("  Новые: ").append(added.isEmpty() ? "нет" : join(added)).append("\n");
            sb.append("  Пропали: ").append(gone.isEmpty() ? "нет" : join(gone)).append("\n");
            if (!same.isEmpty()) sb.append("  Остались: ").append(join(same)).append("\n");
            if (!gone.isEmpty()) {
                sb.append("  Если ошибки пропали без ремонта — их могли стереть.\n");
            }
        }
        sb.append("\n");
        return sb.toString();
    }

    public static String describe(Entry e) {
        StringBuilder sb = new StringBuilder();
        sb.append(date(e.time)).append("\n");
        sb.append("  VIN: ").append(e.vin.isEmpty() ? "не определён" : e.vin).append("\n");
        sb.append("  Лампа: ").append(e.mil ? "горела" : "не горела").append("\n");
        Set<String> all = e.all();
        sb.append("  Ошибки: ").append(all.isEmpty() ? "нет" : join(new ArrayList<>(all))).append("\n");
        return sb.toString();
    }
}
