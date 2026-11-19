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
        sb.append("Важно: приборная панель, где обычно хранится настоящий пробег, по стандарту OBD чаще всего недоступна. ")
                .append("Её чтение зависит от марки машины.\n");
        return sb.toString();
    }
}
