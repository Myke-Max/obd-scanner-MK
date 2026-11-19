package pt.mkmedia.obdscanner;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Журнал работы приложения: что происходило, какие были сбои. Хранится в файле на телефоне. */
public final class AppLog {
    private AppLog() {}

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final long MAX_BYTES = 200 * 1024;
    private static final long KEEP_BYTES = 100 * 1024;

    private static File logFile;
    private static File crashFlag;

    public static synchronized void init(File dir) {
        logFile = new File(dir, "app.log");
        crashFlag = new File(dir, "crash.flag");
        try {
            if (logFile.exists() && logFile.length() > MAX_BYTES) {
                byte[] all = readBytes(logFile);
                int from = (int) Math.max(0, all.length - KEEP_BYTES);
                FileOutputStream o = new FileOutputStream(logFile, false);
                try {
                    o.write(all, from, all.length - from);
                } finally {
                    o.close();
                }
            }
        } catch (IOException ignored) {
        }
    }

    private static byte[] readBytes(File f) throws IOException {
        FileInputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        } finally {
            in.close();
        }
    }

    public static synchronized void log(String msg) {
        if (logFile == null) return;
        try {
            String ts = new SimpleDateFormat("dd.MM HH:mm:ss.SSS", Locale.ROOT).format(new Date());
            OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(logFile, true), UTF8);
            try {
                w.write(ts + " " + msg + "\n");
            } finally {
                w.close();
            }
        } catch (IOException ignored) {
        }
    }

    public static void logError(String where, Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        log("ОШИБКА (" + where + "): " + sw);
    }

    /** Последние maxChars символов журнала. */
    public static synchronized String read(int maxChars) {
        if (logFile == null || !logFile.exists()) return "Журнал пока пуст.";
        try {
            String s = new String(readBytes(logFile), UTF8);
            if (s.length() > maxChars) s = "…\n" + s.substring(s.length() - maxChars);
            return s;
        } catch (IOException e) {
            return "Не удалось прочитать журнал: " + e.getMessage();
        }
    }

    /** Если в прошлый раз приложение вылетело — вернёт true (один раз) и сбросит отметку. */
    public static synchronized boolean consumeCrashFlag() {
        if (crashFlag != null && crashFlag.exists()) {
            //noinspection ResultOfMethodCallIgnored
            crashFlag.delete();
            return true;
        }
        return false;
    }

    /** Записывает причину вылета в журнал перед тем, как приложение закроется. */
    public static void installCrashHandler() {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    logError("ВЫЛЕТ в потоке " + t.getName(), e);
                    if (crashFlag != null) {
                        new FileOutputStream(crashFlag).close();
                    }
                } catch (Throwable ignored) {
                }
                if (previous != null) previous.uncaughtException(t, e);
            }
        });
    }
}
