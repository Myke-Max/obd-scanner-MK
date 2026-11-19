package pt.mkmedia.obdscanner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * Следит за живыми данными и замечает признаки неисправностей.
 * Это правила по типичным порогам, а не диагноз: аномалия считается, только если держится несколько замеров подряд.
 */
public final class AnomalyDetector {
    private static final int NEED_IN_ROW = 3;

    private final Map<String, Integer> streak = new HashMap<>();
    private final Map<Integer, double[]> minMax = new HashMap<>();
    private final LinkedList<Double> idleRpm = new LinkedList<>();
    private long runningSince = -1;

    private void rule(String key, boolean cond, String text, List<String> out) {
        int n = cond ? (streak.containsKey(key) ? streak.get(key) : 0) + 1 : 0;
        streak.put(key, n);
        if (n >= NEED_IN_ROW) out.add(text);
    }

    /** Принимает новые значения (по номерам PID) и возвращает список текущих аномалий. */
    public List<String> update(Map<Integer, Double> v, long now) {
        for (Map.Entry<Integer, Double> e : v.entrySet()) {
            double[] mm = minMax.get(e.getKey());
            if (mm == null) {
                minMax.put(e.getKey(), new double[]{e.getValue(), e.getValue()});
            } else {
                mm[0] = Math.min(mm[0], e.getValue());
                mm[1] = Math.max(mm[1], e.getValue());
            }
        }

        Double rpm = v.get(0x0C);
        Double speed = v.get(0x0D);
        Double cool = v.get(0x05);
        Double volt = v.get(0x42);
        Double stft = v.get(0x06);
        Double ltft = v.get(0x07);
        Double oil = v.get(0x5C);
        Double load = v.get(0x04);

        boolean running = rpm != null && rpm > 500;
        boolean off = rpm != null && rpm < 100;
        if (running) {
            if (runningSince < 0) runningSince = now;
        } else {
            runningSince = -1;
        }

        boolean idle = running && speed != null && speed < 1 && rpm < 1500;
        if (idle) {
            idleRpm.add(rpm);
            if (idleRpm.size() > 20) idleRpm.removeFirst();
        } else {
            idleRpm.clear();
        }

        List<String> out = new ArrayList<>();

        rule("heat", cool != null && cool > 110,
                "Перегрев двигателя: охлаждающая жидкость выше 110 °C", out);
        rule("warm", cool != null && cool > 102 && cool <= 110,
                "Температура охлаждающей жидкости повышена (выше 102 °C)", out);
        rule("cold", running && runningSince >= 0 && now - runningSince > 10 * 60 * 1000L
                        && cool != null && cool < 65,
                "Двигатель не прогрелся за 10 минут работы (возможно, термостат)", out);
        rule("oil", oil != null && oil > 140,
                "Масло перегрето (выше 140 °C)", out);

        rule("lowv", running && volt != null && volt < 13.0,
                "Низкое напряжение при работающем двигателе (генератор или аккумулятор)", out);
        rule("highv", running && volt != null && volt > 15.0,
                "Слишком высокое напряжение бортсети (регулятор генератора)", out);
        rule("offv", off && volt != null && volt < 11.8,
                "Аккумулятор сильно разряжен", out);

        rule("ltft", ltft != null && Math.abs(ltft) > 15,
                "Длинная коррекция топлива вне нормы (подсос воздуха, топливо, датчики)", out);
        rule("stft", running && stft != null && Math.abs(stft) > 25,
                "Короткая коррекция топлива сильно отклонена", out);

        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (double r : idleRpm) {
            min = Math.min(min, r);
            max = Math.max(max, r);
        }
        rule("idle", idleRpm.size() >= 15 && (max - min) > 300,
                "Нестабильные обороты на холостом ходу", out);
        rule("loadidle", idle && load != null && load > 65,
                "Высокая нагрузка двигателя на холостом ходу", out);
        return out;
    }

    /** Сводка за сеанс: от минимума до максимума по каждому показателю. */
    public String statsText() {
        StringBuilder sb = new StringBuilder();
        for (int pid : ObdParser.LIVE_PIDS) {
            double[] mm = minMax.get(pid);
            if (mm == null) continue;
            sb.append(ObdParser.name(pid)).append(": ")
                    .append(String.format(java.util.Locale.ROOT, "%." + ObdParser.decimals(pid) + "f", mm[0]))
                    .append(" … ")
                    .append(ObdParser.formatValue(pid, mm[1])).append("\n");
        }
        return sb.toString();
    }
}
