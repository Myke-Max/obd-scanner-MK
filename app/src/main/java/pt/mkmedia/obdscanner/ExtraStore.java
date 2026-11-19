package pt.mkmedia.obdscanner;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/** Заметки по VIN и настройки Telegram. Всё хранится только на телефоне. */
public final class ExtraStore {
    private ExtraStore() {}

    private static final String PREFS = "obd_prefs";

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String token(Context c) { return prefs(c).getString("tg_token", ""); }

    public static String chat(Context c) { return prefs(c).getString("tg_chat", ""); }

    public static boolean autoSend(Context c) { return prefs(c).getBoolean("tg_auto", false); }

    public static void saveTelegram(Context c, String token, String chat, boolean auto) {
        prefs(c).edit().putString("tg_token", token).putString("tg_chat", chat)
                .putBoolean("tg_auto", auto).apply();
    }

    private static File noteFile(Context c, String vin) {
        File dir = new File(c.getFilesDir(), "notes");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new File(dir, vin.replaceAll("[^A-Za-z0-9]", "") + ".txt");
    }

    public static String note(Context c, String vin) {
        File f = noteFile(c, vin);
        if (!f.exists()) return "";
        try {
            FileInputStream in = new FileInputStream(f);
            try {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                return new String(bo.toByteArray(), "UTF-8");
            } finally {
                in.close();
            }
        } catch (IOException e) {
            return "";
        }
    }

    public static void saveNote(Context c, String vin, String text) {
        try {
            FileOutputStream out = new FileOutputStream(noteFile(c, vin));
            try {
                out.write(text.getBytes("UTF-8"));
            } finally {
                out.close();
            }
        } catch (IOException ignored) {
        }
    }
}
