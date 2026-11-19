package pt.mkmedia.obdscanner;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Разбор ответов адаптера. Здесь нет ничего от Android, поэтому это можно проверять тестами. */
public final class ObdParser {
    private ObdParser() {}

    private static final String[] ERROR_WORDS = {
            "NO DATA", "UNABLE TO CONNECT", "CAN ERROR", "BUS ERROR", "BUS BUSY",
            "FB ERROR", "DATA ERROR", "STOPPED", "ERROR", "?"
    };

    public static boolean isError(String raw) {
        String u = raw.toUpperCase(Locale.ROOT);
        for (String w : ERROR_WORDS) {
            if (u.contains(w)) return true;
        }
        return false;
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'A' && c <= 'F');
    }

    private static boolean isHexString(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!isHex(s.charAt(i))) return false;
        }
        return true;
    }

    private static void addBytes(List<Integer> to, String hex) {
        for (int i = 0; i + 1 < hex.length(); i += 2) {
            to.add(Integer.parseInt(hex.substring(i, i + 2), 16));
        }
    }

    /** Делит сырой ответ на отдельные ответы блоков (каждый — список байт). */
    public static List<List<Integer>> responses(String raw) {
        List<List<Integer>> out = new ArrayList<>();
        List<Integer> cur = null;
        for (String line : raw.split("[\\r\\n]+")) {
            String s = line.replace(" ", "").replace(">", "").toUpperCase(Locale.ROOT);
            if (s.isEmpty()) continue;
            boolean cont = s.length() >= 2 && s.charAt(1) == ':' && isHex(s.charAt(0));
            String body = cont ? s.substring(2) : s;
            if (body.isEmpty() || !isHexString(body)) continue;
            if (cont) {
                if (cur != null) addBytes(cur, body);
            } else if (body.length() % 2 == 1) {
                // строка с длиной ("014") — начало многострочного ответа
                cur = new ArrayList<>();
                out.add(cur);
            } else {
                cur = new ArrayList<>();
                addBytes(cur, body);
                out.add(cur);
            }
        }
        return out;
    }

    /** Данные первого ответа на запрос mode/pid (без заголовка). pid = -1, если PID нет. */
    public static List<Integer> findData(String raw, int mode, int pid) {
        List<List<Integer>> all = findAllData(raw, mode, pid);
        return all.isEmpty() ? null : all.get(0);
    }

    public static List<List<Integer>> findAllData(String raw, int mode, int pid) {
        int header = 0x40 + mode;
        List<List<Integer>> out = new ArrayList<>();
        for (List<Integer> r : responses(raw)) {
            int need = pid >= 0 ? 2 : 1;
            if (r.size() < need) continue;
            if (r.get(0) != header) continue;
            if (pid >= 0 && r.get(1) != pid) continue;
            out.add(new ArrayList<>(r.subList(need, r.size())));
        }
        return out;
    }

    /** Ответ на ATDPN: A6, 6, 3 и т.д. Возвращает true для CAN-протоколов. */
    public static boolean isCanProtocol(String raw) {
        String s = raw.replace(">", "").replaceAll("\\s", "").toUpperCase(Locale.ROOT);
        if (s.startsWith("A") && s.length() > 1) s = s.substring(1);
        if (s.isEmpty()) return true;
        return "6789BC".indexOf(s.charAt(0)) >= 0;
    }

    /**
     * Какие блоки ответили на общий запрос (адаптер в режиме ATH1, 11-битный CAN).
     * Возвращает идентификаторы ответов, например "7E8", "7E9".
     */
    public static List<String> canResponders(String raw) {
        Set<String> ids = new LinkedHashSet<>();
        for (String line : raw.split("[\\r\\n]+")) {
            String s = line.replace(" ", "").replace(">", "").toUpperCase(Locale.ROOT);
            if (s.length() >= 5 && isHexString(s) && s.startsWith("7E")
                    && s.charAt(2) >= '8' && s.charAt(2) <= 'F') {
                ids.add(s.substring(0, 3));
            }
        }
        return new ArrayList<>(ids);
    }

    public static String dtcCode(int a, int b) {
        char letter = "PCBU".charAt((a >> 6) & 3);
        int d2 = (a >> 4) & 3;
        return String.format(Locale.ROOT, "%c%d%X%02X", letter, d2, a & 0xF, b);
    }

    /** Коды ошибок из данных ответа на режим 03/07/0A. */
    public static List<String> parseDtcs(List<Integer> data, boolean can) {
        Set<String> codes = new LinkedHashSet<>();
        int start = 0;
        int end = data.size();
        if (can && end > 0) {
            int count = data.get(0);
            if (count * 2 <= end - 1) {
                start = 1;
                end = 1 + count * 2;
            }
        }
        for (int i = start; i + 1 < end; i += 2) {
            int a = data.get(i);
            int b = data.get(i + 1);
            if (a == 0 && b == 0) continue;
            codes.add(dtcCode(a, b));
        }
        return new ArrayList<>(codes);
    }

    /** Текст из ответа режима 09 (VIN, имя блока, номер калибровки). Работает для CAN и старых протоколов. */
    public static String asciiPayload(String raw, int mode, int pid) {
        StringBuilder sb = new StringBuilder();
        for (List<Integer> r : findAllData(raw, mode, pid)) {
            for (int b : r) {
                if (b >= 0x20 && b < 0x7F) sb.append((char) b);
            }
        }
        return sb.toString().trim();
    }

    /** VIN из ответа на 09 02. */
    public static String parseVin(String raw) {
        return asciiPayload(raw, 9, 2);
    }

    /** Список PID, которые показываем в «живых данных». */
    public static final int[] LIVE_PIDS = {
            0x0C, 0x0D, 0x05, 0x5C, 0x04, 0x11, 0x0B, 0x10, 0x0F, 0x06, 0x07, 0x2F, 0x42
    };

    /** PID, связанные с пробегом (стандартные). */
    public static final int[] MILEAGE_PIDS = {0xA6, 0x31, 0x21, 0x4D, 0x4E};

    /** Числовое значение PID или null, если данных мало / PID неизвестен. */
    public static Double value(int pid, List<Integer> d) {
        int n = d.size();
        switch (pid) {
            case 0x0C:
                return n >= 2 ? (d.get(0) * 256 + d.get(1)) / 4.0 : null;
            case 0x0D:
                return n >= 1 ? (double) d.get(0) : null;
            case 0x05:
            case 0x5C:
            case 0x0F:
                return n >= 1 ? (double) (d.get(0) - 40) : null;
            case 0x04:
            case 0x11:
            case 0x2F:
                return n >= 1 ? d.get(0) * 100.0 / 255 : null;
            case 0x0B:
                return n >= 1 ? (double) d.get(0) : null;
            case 0x10:
                return n >= 2 ? (d.get(0) * 256 + d.get(1)) / 100.0 : null;
            case 0x06:
            case 0x07:
                return n >= 1 ? (d.get(0) - 128) * 100.0 / 128 : null;
            case 0x42:
                return n >= 2 ? (d.get(0) * 256 + d.get(1)) / 1000.0 : null;
            case 0x31:
            case 0x21:
            case 0x4D:
            case 0x4E:
                return n >= 2 ? (double) (d.get(0) * 256 + d.get(1)) : null;
            case 0xA6:
                if (n < 4) return null;
                long v = ((long) d.get(0) << 24) | ((long) d.get(1) << 16) | ((long) d.get(2) << 8) | d.get(3);
                return v / 10.0;
            default:
                return null;
        }
    }

    public static String name(int pid) {
        switch (pid) {
            case 0x0C: return "Обороты двигателя";
            case 0x0D: return "Скорость";
            case 0x05: return "Охлаждающая жидкость";
            case 0x5C: return "Температура масла";
            case 0x04: return "Нагрузка двигателя";
            case 0x11: return "Положение дросселя";
            case 0x0B: return "Давление во впуске";
            case 0x10: return "Расход воздуха (MAF)";
            case 0x0F: return "Температура впуска";
            case 0x06: return "Коррекция топлива (короткая)";
            case 0x07: return "Коррекция топлива (длинная)";
            case 0x2F: return "Уровень топлива";
            case 0x42: return "Напряжение бортсети";
            case 0xA6: return "Общий пробег (одометр)";
            case 0x31: return "Пробег с момента стирания ошибок";
            case 0x21: return "Пробег с горящей лампой Check Engine";
            case 0x4D: return "Время работы с горящей лампой";
            case 0x4E: return "Время с момента стирания ошибок";
            default: return "PID " + Integer.toHexString(pid).toUpperCase(Locale.ROOT);
        }
    }

    public static String unit(int pid) {
        switch (pid) {
            case 0x0C: return "об/мин";
            case 0x0D: return "км/ч";
            case 0x05:
            case 0x5C:
            case 0x0F: return "°C";
            case 0x04:
            case 0x11:
            case 0x2F:
            case 0x06:
            case 0x07: return "%";
            case 0x0B: return "кПа";
            case 0x10: return "г/с";
            case 0x42: return "В";
            case 0xA6:
            case 0x31:
            case 0x21: return "км";
            case 0x4D:
            case 0x4E: return "мин";
            default: return "";
        }
    }

    public static int decimals(int pid) {
        switch (pid) {
            case 0x10:
            case 0x06:
            case 0x07:
            case 0x42:
            case 0xA6:
                return 1;
            default:
                return 0;
        }
    }

    /** Значение с единицей: «850 об/мин». */
    public static String formatValue(int pid, double v) {
        return String.format(Locale.ROOT, "%." + decimals(pid) + "f", v) + " " + unit(pid);
    }

    /** Готовая строка вида «Обороты двигателя: 850 об/мин» или null, если данных мало. */
    public static String describePid(int pid, List<Integer> d) {
        Double v = value(pid, d);
        if (v == null) return null;
        return name(pid) + ": " + formatValue(pid, v);
    }
}
