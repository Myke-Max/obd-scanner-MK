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
    // Формат: { запрос, ответ, название, DID пробега или "-" если блок пробег не отдаёт }
    private static final String[][] VAG_MODULES = {
        {"7E0", "7E8", "01 Двигатель", "-"},
        {"7E1", "7E9", "02 Коробка передач", "-"},
        {"713", "77D", "03 ABS / тормоза", "2203"},
        {"70E", "778", "09 Центральная электроника", "2203"},
        {"715", "77F", "15 Подушки безопасности", "-"},
        {"712", "77C", "44 Усилитель руля", "2203"},
        {"714", "77E", "17 Приборная панель", "2203"},
        {"710", "77A", "19 Шлюз (Gateway)", "2203"},
        {"7E5", "7ED", "8C Батарея (только электро/гибрид)", "02BD"},
    };

    // DID 2203 — пробег в приборке VAG (OVMS: VWUP_MFD_ODOMETER). Проверено на Octavia A7 2014.
    private static final String ODO_DID = "2203";

    /** Последний пробег, прочитанный с приборки (без поправки). -1 = не читали. */
    public static volatile long lastClusterKm = -1;

    // Первые 3 знака VIN машин группы VAG
    private static final String[] VAG_WMI = {"TMB", "TMP", "VSS", "VS6", "WVW", "WV1", "WV2", "WV3",
            "WAU", "WA1", "WUA", "TRU", "3VW", "9BW", "8AW", "LFV", "LSV"};

    /** VAG или неизвестно (пустой VIN) — тогда стоит опросить блоки VAG. */
    public static boolean maybeVag(String vin) {
        if (vin == null || vin.length() < 3) return true;
        String w = vin.substring(0, 3).toUpperCase(Locale.ROOT);
        for (String s : VAG_WMI) if (s.equals(w)) return true;
        return false;
    }

    /** Ошибки из всех блоков VAG (для кнопки «Ошибки»). */
    public static String readVagFaults(ObdConnection obd) {
        return readVagInstrumentCluster(obd);
    }

    private static String readVagInstrumentCluster(ObdConnection obd) {
        StringBuilder sb = new StringBuilder("ОШИБКИ И ПРОБЕГ ПО БЛОКАМ VAG\n");
        sb.append("(номер ошибки — как в VCDS)\n\n");
        long clusterKm = -1;
        List<String> odoByBlock = new ArrayList<>();
        List<Long> odoValues = new ArrayList<>();
        lastClusterKm = -1;
        String clusterSrc = "";
        List<Long> faultKm = new ArrayList<>();
        List<String> faultKmWhere = new ArrayList<>();
        int answered = 0, totalFaults = 0;
        try {
            obd.send("ATH0", 1500);
            obd.send("ATCAF1", 1000);
            obd.send("ATAT 1", 1000);
            obd.send("ATST 96", 1000); // ~600 мс ожидания ответа
            obd.send("ATFCSD 30 00 00", 1000);

            for (String[] m : VAG_MODULES) {
                String tx = m[0], rx = m[1], name = m[2];
                String odoDid = m[3];
                obd.send("ATSH " + tx, 1500);
                obd.send("ATCRA " + rx, 1500);
                obd.send("ATFCSH " + tx, 1000);
                obd.send("ATFCSM 1", 1000);

                // 19 02 FF — все записи блока. Блок отдаёт и «просто не проверялось» —
                // настоящая ошибка только если стоит бит: 01 сейчас, 04 ожидает, 08 подтверждена.
                List<Integer> dtc = udsRequest(obd, "1902FF", 4000);
                int blockStart = sb.length();
                sb.append(name).append(":\n");
                if (dtc != null) answered++;
                else sb.append("  на запрос ошибок не отвечает\n");
                if (dtc != null && dtc.size() >= 3 && dtc.get(0) == 0x59) {
                    List<int[]> faults = realUdsDtcs(dtc);
                    totalFaults += faults.size();
                    if (faults.isEmpty()) {
                        sb.append("  Ошибок нет\n");
                    } else {
                        sb.append("  Ошибки (").append(faults.size()).append("):\n");
                        int n = 0;
                        for (int[] f : faults) {
                            sb.append("   • ").append(dtcLabel(f));
                            // Замороженный кадр: в нём VAG пишет пробег, при котором была ошибка
                            if (n++ < 10) {
                                long km = faultMileage(obd, f);
                                if (km > 0) {
                                    sb.append("\n     пробег при ошибке: ").append(km).append(" км");
                                    faultKm.add(km);
                                    faultKmWhere.add(name + ", ошибка " + vcdsNumber(f));
                                }
                            }
                            sb.append("\n");
                        }
                    }
                } else if (dtc != null && dtc.size() >= 3 && dtc.get(0) == 0x7F) {
                    sb.append("  Блок отказал в чтении ошибок (код ")
                            .append(String.format(Locale.ROOT, "%02X", dtc.get(2))).append(")\n");
                }

                long km = -1;
                if (!"-".equals(odoDid)) {
                    km = readOdometerDid(obd, odoDid);
                    if (km > 0) {
                        sb.append("  Пробег в блоке: ").append(km).append(" км\n");
                        odoByBlock.add(name + ": " + km + " км");
                        odoValues.add(km);
                        if (name.startsWith("17")) { clusterKm = km; clusterSrc = name; }
                    }
                }
                // Блок батареи есть только у электро/гибридов — на обычной машине молча пропускаем
                if (tx.equals("7E5") && dtc == null && km <= 0) sb.setLength(blockStart);
                else sb.append("\n");
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

        lastClusterKm = clusterKm;
        if (answered == 0 && odoValues.isEmpty()) {
            sb.append("Ни один блок VAG не ответил — возможно, это не VAG.\n\n");
            return sb.toString();
        }
        sb.append("ИТОГО: ошибок во всех блоках — ").append(totalFaults).append("\n\n");

        sb.append("ПРОБЕГ ПО БЛОКАМ:\n");
        if (odoByBlock.isEmpty()) {
            sb.append("  Ни один блок не отдал пробег.\n");
        } else {
            for (String l : odoByBlock) sb.append("  ").append(l).append("\n");
            if (odoValues.size() >= 2) {
                long mn = Long.MAX_VALUE, mx = 0;
                for (long v : odoValues) { mn = Math.min(mn, v); mx = Math.max(mx, v); }
                if (mx - mn > 100) {
                    sb.append("⚠ Пробег в блоках НЕ совпадает: от ").append(mn).append(" до ").append(mx)
                            .append(" км. Нужно разобраться, какой блок врёт.\n");
                } else {
                    sb.append("  Во всех блоках, где есть пробег, он совпадает.\n");
                }
            }
        }
        if (!faultKm.isEmpty()) {
            long maxKm = 0; String maxWhere = "";
            for (int i = 0; i < faultKm.size(); i++) {
                if (faultKm.get(i) > maxKm) { maxKm = faultKm.get(i); maxWhere = faultKmWhere.get(i); }
            }
            sb.append("  Самый большой пробег, записанный в ошибках: ").append(maxKm)
                    .append(" км (").append(maxWhere).append(")\n");
            if (clusterKm > 0 && maxKm > clusterKm + 100) {
                sb.append("⚠ Возможный признак скрутки: ошибка записана при пробеге БОЛЬШЕ, чем сейчас на приборке (на ")
                        .append(maxKm - clusterKm).append(" км). Бывает и при замене приборки. Проверка новая — сверь с VCDS.\n");
            } else if (clusterKm > 0) {
                sb.append("  Противоречий с приборкой нет.\n");
            }
        }
        sb.append("\n");
        return sb.toString();
    }

    /** Пробег из блока по DID: 3 байта, в км. -1 если блок не отвечает или значение странное. */
    private static long readOdometerDid(ObdConnection obd, String did) throws IOException {
        List<Integer> r = udsRequest(obd, "22" + did, 3000);
        int hi = Integer.parseInt(did.substring(0, 2), 16);
        int lo = Integer.parseInt(did.substring(2, 4), 16);
        if (r == null || r.size() < 6 || r.get(0) != 0x62 || r.get(1) != hi || r.get(2) != lo) return -1;
        long v = ((long) r.get(3) << 16) | (r.get(4) << 8) | r.get(5);
        return (v > 0 && v < 2000000) ? v : -1;
    }

    /** Пробег из замороженного кадра ошибки (19 04 ... FF, ищем в нём DID 2203). -1 если нет. */
    private static long faultMileage(ObdConnection obd, int[] f) throws IOException {
        String cmd = String.format(Locale.ROOT, "1904%02X%02X%02XFF", f[0], f[1], f[2]);
        List<Integer> r = udsRequest(obd, cmd, 3000);
        if (r == null || r.size() < 9 || r.get(0) != 0x59 || r.get(1) != 0x04) return -1;
        for (int i = 6; i + 4 < r.size(); i++) {
            if (r.get(i) == 0x22 && r.get(i + 1) == 0x03) {
                long v = ((long) r.get(i + 2) << 16) | (r.get(i + 3) << 8) | r.get(i + 4);
                if (v > 0 && v < 2000000) return v;
            }
        }
        return -1;
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


    /** Из ответа 59 02 берёт только настоящие ошибки: {байт1, байт2, байт3, статус}. */
    private static List<int[]> realUdsDtcs(List<Integer> r) {
        List<int[]> out = new ArrayList<>();
        for (int i = 3; i + 3 < r.size(); i += 4) {
            int a = r.get(i), b = r.get(i + 1), c = r.get(i + 2), st = r.get(i + 3);
            if (a == 0 && b == 0 && c == 0) continue;
            if ((st & 0x0D) == 0) continue; // только «не проверялось» — это не ошибка
            out.add(new int[]{a, b, c, st});
        }
        return out;
    }

    private static long vcdsNumber(int[] f) {
        return ((long) f[0] << 16) | (f[1] << 8) | f[2];
    }

    private static String dtcLabel(int[] f) {
        String state;
        if ((f[3] & 0x01) != 0) state = "АКТИВНА сейчас";
        else if ((f[3] & 0x08) != 0) state = "была раньше, сохранена в памяти";
        else state = "появлялась, ждёт подтверждения";
        return String.format(Locale.ROOT, "%d  [%02X %02X %02X] — %s", vcdsNumber(f), f[0], f[1], f[2], state);
    }
}
