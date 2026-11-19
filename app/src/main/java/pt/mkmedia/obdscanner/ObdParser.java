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

    /** VIN из ответа на 09 02. Работает и для CAN, и для старых протоколов. */
    public static String parseVin(String raw) {
        StringBuilder sb = new StringBuilder();
        for (List<Integer> r : findAllData(raw, 9, 2)) {
            for (int b : r) {
                if (b >= 0x20 && b < 0x7F) sb.append((char) b);
            }
        }
        return sb.toString();
    }

    /** Список PID, которые показываем в «живых данных». */
    public static final int[] LIVE_PIDS = {
            0x0C, 0x0D, 0x05, 0x5C, 0x04, 0x11, 0x0B, 0x10, 0x0F, 0x06, 0x07, 0x2F, 0x42
    };

    /** Готовая строка вида «Обороты двигателя: 850 об/мин» или null, если данных мало. */
    public static String describePid(int pid, List<Integer> d) {
        int n = d.size();
        switch (pid) {
            case 0x0C:
                if (n < 2) return null;
                return fmt("Обороты двигателя", (d.get(0) * 256 + d.get(1)) / 4.0, 0, "об/мин");
            case 0x0D:
                if (n < 1) return null;
                return fmt("Скорость", d.get(0), 0, "км/ч");
            case 0x05:
                if (n < 1) return null;
                return fmt("Охлаждающая жидкость", d.get(0) - 40, 0, "°C");
            case 0x5C:
                if (n < 1) return null;
                return fmt("Температура масла", d.get(0) - 40, 0, "°C");
            case 0x04:
                if (n < 1) return null;
                return fmt("Нагрузка двигателя", d.get(0) * 100.0 / 255, 0, "%");
            case 0x11:
                if (n < 1) return null;
                return fmt("Положение дросселя", d.get(0) * 100.0 / 255, 0, "%");
            case 0x0B:
                if (n < 1) return null;
                return fmt("Давление во впуске", d.get(0), 0, "кПа");
            case 0x10:
                if (n < 2) return null;
                return fmt("Расход воздуха (MAF)", (d.get(0) * 256 + d.get(1)) / 100.0, 1, "г/с");
            case 0x0F:
                if (n < 1) return null;
                return fmt("Температура впуска", d.get(0) - 40, 0, "°C");
            case 0x06:
                if (n < 1) return null;
                return fmt("Коррекция топлива (короткая)", (d.get(0) - 128) * 100.0 / 128, 1, "%");
            case 0x07:
                if (n < 1) return null;
                return fmt("Коррекция топлива (длинная)", (d.get(0) - 128) * 100.0 / 128, 1, "%");
            case 0x2F:
                if (n < 1) return null;
                return fmt("Уровень топлива", d.get(0) * 100.0 / 255, 0, "%");
            case 0x42:
                if (n < 2) return null;
                return fmt("Напряжение бортсети", (d.get(0) * 256 + d.get(1)) / 1000.0, 1, "В");
            default:
                return null;
        }
    }

    private static String fmt(String name, double value, int decimals, String unit) {
        return name + ": " + String.format(Locale.ROOT, "%." + decimals + "f", value) + " " + unit;
    }
}
