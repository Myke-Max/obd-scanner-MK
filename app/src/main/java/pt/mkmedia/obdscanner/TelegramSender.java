package pt.mkmedia.obdscanner;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/** Отправка текста в Telegram через бота. Возвращает null при успехе или текст ошибки. */
public final class TelegramSender {
    private TelegramSender() {}

    public static String send(String token, String chatId, String text) {
        if (token.isEmpty() || chatId.isEmpty()) return "не заполнены токен или номер чата";
        while (text.length() > 3900) {
            int cut = text.lastIndexOf('\n', 3900);
            if (cut < 1000) cut = 3900;
            String err = sendOne(token, chatId, text.substring(0, cut));
            if (err != null) return err;
            text = text.substring(cut).trim();
        }
        return text.isEmpty() ? null : sendOne(token, chatId, text);
    }

    private static String sendOne(String token, String chatId, String text) {
        HttpURLConnection c = null;
        try {
            URL u = new URL("https://api.telegram.org/bot" + token + "/sendMessage");
            c = (HttpURLConnection) u.openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            String body = "chat_id=" + URLEncoder.encode(chatId, "UTF-8")
                    + "&text=" + URLEncoder.encode(text, "UTF-8")
                    + "&disable_web_page_preview=true";
            OutputStream o = c.getOutputStream();
            o.write(body.getBytes("UTF-8"));
            o.close();
            int code = c.getResponseCode();
            return code == 200 ? null : "сервер ответил " + code + " (проверьте токен и номер чата)";
        } catch (Exception e) {
            return String.valueOf(e.getMessage());
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
