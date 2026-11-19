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

    /**
     * Читает пробег из приборной панели VAG по UDS (адрес 7C0).
     * Работает на MQB (Golf VII, Octavia A7, Audi A3 8V и др., 2013+).
     * DID 0xF442 — одометр в км.
     */
    // Адреса модулей VAG для поиска пробега
    // Формат: { "TX_header", "RX_filter", "название", DID1, DID2, ... }
    // MQB (Octavia A7, Golf 7, Audi A3 8V): панель на 717→777, шлюз 710→770, ABS 713→773
    // PQ35 (Octavia II, Golf V/VI): панель 7C0→7C8
    // Формат: { "TX", "RX", "название", "METERS:1_если_в_метрах", DID1, DID2, ... }
    // METERS:1 = значение в метрах (делить на 1000 для км), METERS:0 = уже в км
    private static final String[][] VAG_MODULES = {
        // MQB приборная панель (адрес 17 в VCDS = 0x717 ELM) — значения в км
        {"717", "777", "Приборная панель MQB (17→77)", "METERS:0", "22F442", "22F40A", "22F450", "222801"},
        // MQB шлюз (адрес 19 = 0x719) — хранит копию пробега в км
        {"719", "779", "Шлюз MQB (19→79)", "METERS:0", "22F442", "22F40A", "222801"},
        // MQB ABS (адрес 13 = 0x713) — тоже хранит пробег в км
        {"713", "773", "ABS MQB (13→73)", "METERS:0", "22F442", "22F40A", "222801"},
        // PQ35 приборная панель (старый адрес 7C0) — значения в км
        {"7C0", "7C8", "Приборная панель PQ35 (7C0→7C8)", "METERS:0", "22F442", "22284F", "222801", "22F40A"},
        // Движок хранит пробег в МЕТРАХ — делим на 1000
        // F442=частичный, 2003=полный пробег в метрах (VAG ECU стандарт)
        {"7E0", "7E8", "Блок двигателя (7E0→7E8)", "METERS:1", "222003", "22F442", "22284F", "22F40A"},
    };

    private static String readVagInstrumentCluster(ObdConnection obd) {
        StringBuilder sb = new StringBuilder();
        StringBuilder debugSb = new StringBuilder("ПОИСК ОДОМЕТРА VAG\n\n");
        try {
            obd.send("ATH1", 1500);
            obd.send("ATAT 1", 1000);
            obd.send("ATST FF", 1000); // максимальный таймаут

            for (String[] module : VAG_MODULES) {
                String txAddr = module[0];
                String rxAddr = module[1];
                String moduleName = module[2];

                obd.send("ATSH " + txAddr, 1500);
                obd.send("ATCRA " + rxAddr, 1500);
                // Flow control для многофреймовых ответов
                obd.send("ATFCSH " + txAddr, 1000);
                obd.send("ATFCSD 30 00 00", 1000);
                obd.send("ATFCSM 1", 1000);

                boolean inMeters = "METERS:1".equals(module[3]);
                debugSb.append("[").append(moduleName).append(inMeters ? " (в метрах)" : " (в км)").append("]\n");

                for (int i = 4; i < module.length; i++) {
                    String did = module[i];
                    String raw = obd.send(did, 4000);
                    String rawTrim = raw == null ? "null" : raw.trim();
                    debugSb.append("  ").append(did).append(": ").append(rawTrim).append("\n");

                    if (raw != null && !raw.contains("NO DATA") && !raw.contains("ERROR")
                            && !raw.contains("?") && !raw.trim().isEmpty()) {
                        String hex = raw.replaceAll("[^0-9A-Fa-f]", "").toUpperCase(Locale.ROOT);
                        String didHex = did.substring(2).toUpperCase(Locale.ROOT);
                        int idx = hex.indexOf("62" + didHex);
                        if (idx >= 0) {
                            int dataStart = idx + 6;
                            // Пробуем 4 байта (32 бита) сначала — для метров нужно больше места
                            long rawVal = -1;
                            if (hex.length() >= dataStart + 8) {
                                try {
                                    rawVal = Long.parseLong(hex.substring(dataStart, dataStart + 8), 16);
                                } catch (NumberFormatException ignored) {}
                            }
                            // Затем 3 байта (24 бита)
                            if (rawVal < 0 && hex.length() >= dataStart + 6) {
                                try {
                                    rawVal = Long.parseLong(hex.substring(dataStart, dataStart + 6), 16);
                                } catch (NumberFormatException ignored) {}
                            }
                            if (rawVal > 0) {
                                long km = inMeters ? rawVal / 1000 : rawVal;
                                // Разумный диапазон: от 100 до 2 000 000 км
                                if (km >= 100 && km < 2000000) {
                                    sb.append("ОДОМЕТР VAG — реальный пробег\n\n");
                                    sb.append("  Пробег: ").append(km).append(" км\n");
                                    if (inMeters) sb.append("  (Хранится в метрах: ").append(rawVal).append(" м)\n");
                                    sb.append("  Источник: ").append(moduleName).append("\n");
                                    sb.append("  DID: ").append(did).append("\n\n");
                                    return sb.toString();
                                }
                            }
                        }
                    }
                }
                debugSb.append("\n");
            }

            // Ничего не нашли — показываем лог для диагностики
            sb.append(debugSb);
            sb.append("→ Одометр через OBD не прочитан.\n");
            sb.append("  Смотрите лог выше для диагностики.\n\n");
        } catch (Exception e) {
            sb.append("Одометр VAG: ошибка — ").append(e.getMessage()).append("\n");
        } finally {
            try {
                obd.send("ATFCSM 0", 500);
                obd.send("ATAR", 1500);
                obd.send("ATSH 7DF", 1500);
                obd.send("ATH0", 1500);
            } catch (IOException ignored) {}
        }
        return sb.toString();
    }
}
