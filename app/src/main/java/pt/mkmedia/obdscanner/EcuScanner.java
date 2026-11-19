package pt.mkmedia.obdscanner;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Сканирование блоков управления, которые отвечают по стандарту OBD-II (11-битный CAN),
 * и чтение из них стандартных данных о пробеге.
 */
public final class EcuScanner {
    private EcuScanner() {}

    private static String guessName(int requestId) {
        if (requestId == 0x7E0) return "блок двигателя (ECM)";
        if (requestId == 0x7E1) return "блок коробки передач (TCM)";
        return "дополнительный блок";
    }

    public static String scan(ObdConnection obd) throws IOException {
        if (!obd.can) {
            return "Сканирование блоков пока работает только на машинах с шиной CAN. Эта машина использует старый протокол.";
        }
        StringBuilder sb = new StringBuilder("БЛОКИ, ОТВЕЧАЮЩИЕ ПО СТАНДАРТУ OBD\n\n");
        List<Double> odometers = new ArrayList<>();
        try {
            obd.send("ATH1", 1500);
            obd.send("ATSH 7DF", 1500);
            String raw = obd.send("0100", 6000);
            List<String> ids = ObdParser.canResponders(raw);
            if (ids.isEmpty()) {
                return "Блоки не ответили. Возможно, у машины 29-битная шина — её пока не поддерживаю. "
                        + "Журнал сохранён, его можно отправить кнопкой «Отправить журнал».";
            }
            sb.append("Найдено блоков: ").append(ids.size()).append("\n\n");
            for (String id : ids) {
                int resp = Integer.parseInt(id, 16);
                int req = resp - 8;
                String reqHex = Integer.toHexString(req).toUpperCase(Locale.ROOT);
                obd.send("ATSH " + reqHex, 1500);
                obd.send("ATCRA " + id, 1500);
                obd.send("ATH0", 1500);

                sb.append("БЛОК ").append(id).append(" — ").append(guessName(req)).append("\n");
                String ecuName = ObdParser.asciiPayload(obd.send("090A", 4000), 9, 0x0A);
                String cal = ObdParser.asciiPayload(obd.send("0904", 4000), 9, 0x04);
                String vin = ObdParser.asciiPayload(obd.send("0902", 4000), 9, 0x02);
                if (!ecuName.isEmpty()) sb.append("  Название: ").append(ecuName).append("\n");
                if (!cal.isEmpty()) sb.append("  Номер калибровки (версия прошивки): ").append(cal).append("\n");
                if (!vin.isEmpty()) sb.append("  VIN в блоке: ").append(vin).append("\n");

                // Читаем ошибки: режим 03 = активные, 07 = ожидающие, 0A = постоянные
                List<String> allDtcs = new ArrayList<>();
                for (int mode : new int[]{0x03, 0x07, 0x0A}) {
                    String dtcRaw = obd.send(String.format(Locale.ROOT, "%02X", mode), 4000);
                    if (dtcRaw != null && !ObdParser.isError(dtcRaw)) {
                        for (List<Integer> dtcResp : ObdParser.responses(dtcRaw)) {
                            int expectByte = 0x40 + mode;
                            if (!dtcResp.isEmpty() && dtcResp.get(0) == expectByte) {
                                List<Integer> data = dtcResp.subList(1, dtcResp.size());
                                allDtcs.addAll(ObdParser.parseDtcs(data, obd.can));
                            }
                        }
                    }
                }
                if (allDtcs.isEmpty()) {
                    sb.append("  Ошибок нет\n");
                } else {
                    sb.append("  Ошибки (").append(allDtcs.size()).append("): ");
                    sb.append(String.join(", ", allDtcs)).append("\n");
                }

                // Пробег по стандартным OBD PID
                boolean any = false;
                for (int pid : ObdParser.MILEAGE_PIDS) {
                    String r = obd.send(String.format(Locale.ROOT, "01%02X", pid), 2500);
                    List<Integer> d = ObdParser.findData(r, 1, pid);
                    Double v = d == null ? null : ObdParser.value(pid, d);
                    if (v != null) {
                        any = true;
                        sb.append("  ").append(ObdParser.name(pid)).append(": ")
                                .append(ObdParser.formatValue(pid, v)).append("\n");
                        if (pid == 0xA6) odometers.add(v);
                    }
                }
                if (!any) sb.append("  Данных о пробеге по стандарту этот блок не отдаёт.\n");
                sb.append("\n");
            }
        } finally {
            try {
                obd.send("ATAR", 1500);
                obd.send("ATSH 7DF", 1500);
                obd.send("ATH0", 1500);
            } catch (IOException ignored) {
            }
        }

        if (odometers.size() >= 2) {
            double min = odometers.get(0);
            double max = odometers.get(0);
            for (double o : odometers) {
                min = Math.min(min, o);
                max = Math.max(max, o);
            }
            if (max - min > 1.0) {
                sb.append("⚠ Общий пробег в разных блоках отличается: от ")
                        .append(String.format(Locale.ROOT, "%.1f", min)).append(" до ")
                        .append(String.format(Locale.ROOT, "%.1f", max)).append(" км. Стоит проверить.\n\n");
            } else {
                sb.append("Общий пробег в блоках совпадает.\n\n");
            }
        }
        sb.append(readVagInstrumentCluster(obd));
        return sb.toString();
    }

    // ===================== VAG (VW, Audi, Škoda, SEAT) =====================
    // Диагностические адреса VAG на CAN: ответ = запрос + 0x6A.
    // Номер блока в VCDS (17, 19, 03...) — это НЕ CAN-адрес.
    // Источник адресов 713/77D и 714/77E и DID 2203: проект OVMS (vehicle_vweup, vweup_obd.h).
    // Формат: { запрос, ответ, название, читать_пробег(1/0) }
    private static final String[][] VAG_MODULES = {
        {"7E0", "7E8", "01 Двигатель", "0"},
        {"7E1", "7E9", "02 Коробка передач", "0"},
        {"713", "77D", "03 ABS / тормоза", "1"},
        {"70E", "778", "09 Центральная электроника", "1"},
        {"715", "77F", "15 Подушки безопасности", "0"},
        {"712", "77C", "44 Усилитель руля", "1"},
        {"714", "77E", "17 Приборная панель", "1"},
        {"710", "77A", "19 Шлюз (Gateway)", "1"},
    };

    // DID пробега: 2203 — пробег в приборке VAG (OVMS: VWUP_MFD_ODOMETER), F4A6 — OBD-пробег через UDS
    private static final String[] VAG_ODO_DIDS = {"2203", "F4A6"};

    private static String readVagInstrumentCluster(ObdConnection obd) {
        StringBuilder sb = new StringBuilder("БЛОКИ VAG (ошибки и пробег)\n\n");
        List<long[]> odos = new ArrayList<>(); // {км}
        List<String> odoSrc = new ArrayList<>();
        int answered = 0;
        try {
            obd.send("ATH0", 1500);
            obd.send("ATCAF1", 1000);
            obd.send("ATAT 1", 1000);
            obd.send("ATST 96", 1000); // ~600 мс ожидания ответа
            obd.send("ATFCSD 30 00 00", 1000);

            for (String[] m : VAG_MODULES) {
                String tx = m[0], rx = m[1], name = m[2];
                boolean readOdo = "1".equals(m[3]);
                obd.send("ATSH " + tx, 1500);
                obd.send("ATCRA " + rx, 1500);
                obd.send("ATFCSH " + tx, 1000);
                obd.send("ATFCSM 1", 1000);

                // Ошибки по протоколу UDS: 19 02 FF = все сохранённые ошибки блока
                List<Integer> dtc = udsRequest(obd, "1902FF", 4000);
                if (dtc == null) {
                    sb.append(name).append(": не отвечает\n");
                    continue;
                }
                answered++;
                sb.append(name).append(":\n");
                if (dtc.size() >= 2 && dtc.get(0) == 0x59) {
                    List<String> faults = parseUdsDtcs(dtc);
                    if (faults.isEmpty()) {
                        sb.append("  Ошибок нет\n");
                    } else {
                        sb.append("  Ошибки (").append(faults.size()).append("):\n");
                        for (String f : faults) sb.append("   • ").append(f).append("\n");
                    }
                } else if (dtc.size() >= 3 && dtc.get(0) == 0x7F) {
                    sb.append("  Блок отказал в чтении ошибок (код ")
                            .append(String.format(Locale.ROOT, "%02X", dtc.get(2))).append(")\n");
                }

                if (readOdo) {
                    for (String did : VAG_ODO_DIDS) {
                        List<Integer> r = udsRequest(obd, "22" + did, 3000);
                        if (r == null || r.size() < 4 || r.get(0) != 0x62) continue;
                        int hi = Integer.parseInt(did.substring(0, 2), 16);
                        int lo = Integer.parseInt(did.substring(2, 4), 16);
                        if (r.get(1) != hi || r.get(2) != lo) continue;
                        List<Integer> data = r.subList(3, r.size());
                        long v = 0;
                        int n = Math.min(4, data.size());
                        for (int i = 0; i < n; i++) v = (v << 8) | data.get(i);
                        if (did.equals("F4A6")) v = v / 10; // OBD формула: 0.1 км
                        sb.append("  Пробег (DID ").append(did).append("): ").append(v).append(" км")
                                .append("  [байты: ").append(bytesHex(data)).append("]\n");
                        if (v > 0 && v < 2000000) {
                            odos.add(new long[]{v});
                            odoSrc.add(name);
                        }
                        break;
                    }
                }
            }
        } catch (Exception e) {
            sb.append("Ошибка при опросе блоков VAG: ").append(e.getMessage()).append("\n");
        } finally {
            try {
                obd.send("ATFCSM 0", 500);
                obd.send("ATST 32", 500);
                obd.send("ATAR", 1500);
                obd.send("ATSH 7DF", 1500);
                obd.send("ATH0", 1500);
            } catch (IOException ignored) {}
        }

        if (answered == 0) {
            sb.append("\nНи один блок VAG не ответил — возможно, это не VAG или нужен другой адрес.\n");
        }
        if (!odos.isEmpty()) {
            long min = Long.MAX_VALUE, max = 0;
            for (long[] o : odos) { min = Math.min(min, o[0]); max = Math.max(max, o[0]); }
            sb.append("\nПРОБЕГ ПО БЛОКАМ:\n");
            for (int i = 0; i < odos.size(); i++) {
                sb.append("  ").append(odoSrc.get(i)).append(": ").append(odos.get(i)[0]).append(" км\n");
            }
            if (odos.size() >= 2 && max - min > 100) {
                sb.append("⚠ Пробег в блоках отличается на ").append(max - min)
                        .append(" км — возможна скрутка или замена блока.\n");
            }
        } else {
            sb.append("\nПробег из блоков VAG прочитать не удалось.\n");
        }
        sb.append("\n");
        return sb.toString();
    }

    /** Отправляет UDS-запрос и собирает ответ (в т.ч. многострочный). null = нет ответа. */
    private static List<Integer> udsRequest(ObdConnection obd, String cmd, int timeout) throws IOException {
        String raw = obd.send(cmd, timeout);
        if (raw == null) return null;
        String u = raw.toUpperCase(Locale.ROOT);
        if (u.contains("NO DATA") || u.contains("ERROR") || u.contains("?") || u.contains("STOPPED")) return null;
        List<Integer> out = new ArrayList<>();
        int declared = -1;
        for (String line : raw.split("[\\r\\n|]+")) {
            String s = line.replace(" ", "").replace(">", "").toUpperCase(Locale.ROOT);
            if (s.isEmpty() || s.startsWith("SEARCHING")) continue;
            boolean cont = s.length() >= 2 && s.charAt(1) == ':';
            String body = cont ? s.substring(2) : s;
            if (body.isEmpty() || !body.matches("[0-9A-F]+")) continue;
            if (!cont && body.length() % 2 == 1 && body.length() <= 3) {
                declared = Integer.parseInt(body, 16); // длина многострочного ответа
                continue;
            }
            for (int i = 0; i + 1 < body.length(); i += 2) {
                out.add(Integer.parseInt(body.substring(i, i + 2), 16));
            }
        }
        // Ответ "ещё думаю" (7F xx 78) — пропускаем, берём последний настоящий
        if (out.size() >= 3 && out.get(0) == 0x7F && out.get(2) == 0x78 && out.size() > 3) {
            out = new ArrayList<>(out.subList(3, out.size()));
        }
        if (declared > 0 && out.size() > declared) out = new ArrayList<>(out.subList(0, declared));
        return out.isEmpty() ? null : out;
    }

    /** Разбирает ответ 59 02: [59][02][маска] затем по 4 байта: 3 байта кода + 1 байт статуса. */
    private static List<String> parseUdsDtcs(List<Integer> r) {
        List<String> out = new ArrayList<>();
        for (int i = 3; i + 3 < r.size(); i += 4) {
            int a = r.get(i), b = r.get(i + 1), c = r.get(i + 2), st = r.get(i + 3);
            if (a == 0 && b == 0 && c == 0) continue;
            String code = ObdParser.dtcCode(a, b) + String.format(Locale.ROOT, " %02X", c);
            String state = (st & 0x01) != 0 ? "активная сейчас" : "сохранена в памяти";
            out.add(code + " — " + state);
        }
        return out;
    }

    private static String bytesHex(List<Integer> d) {
        StringBuilder s = new StringBuilder();
        for (int x : d) s.append(String.format(Locale.ROOT, "%02X", x));
        return s.toString();
    }
}
