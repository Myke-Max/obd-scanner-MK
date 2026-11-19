package pt.mkmedia.obdscanner;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private interface ObdTask {
        void run() throws Exception;
    }

    private TextView status;
    private TextView output;
    private Button btnConnect, btnDtc, btnClear, btnLive, btnVin, btnShare, btnHistory, btnLog;

    private ObdConnection obd;
    private String deviceName = "";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean liveRunning = false;

    private String lastVin = "";
    private String lastDtcText = "";
    private String lastLiveText = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppLog.init(getFilesDir());
        AppLog.installCrashHandler();
        AppLog.log("=== Запуск приложения. Android " + Build.VERSION.RELEASE + ", " + Build.MODEL + " ===");
        buildUi();
        setConnectedUi(false);
        String start = "Как начать:\n"
                + "1. Вставьте адаптер OBDLink LX в разъём машины и включите зажигание.\n"
                + "2. В настройках Bluetooth телефона один раз свяжите адаптер (PIN: 1234 или 0000).\n"
                + "3. Нажмите «Подключиться к адаптеру».";
        if (AppLog.consumeCrashFlag()) {
            start = "⚠ В прошлый раз приложение закрылось с ошибкой. Причина записана — нажмите «Отправить журнал» и пришлите мне.\n\n" + start;
        }
        show(start);
    }

    // ---------- интерфейс ----------

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private Button makeButton(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout row(Button a, Button b) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        r.addView(a, p);
        r.addView(b, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return r;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(28), dp(12), dp(12));

        status = new TextView(this);
        status.setTextSize(16);
        status.setTypeface(Typeface.DEFAULT_BOLD);
        status.setPadding(0, 0, 0, dp(8));
        root.addView(status);

        btnConnect = makeButton("Подключиться к адаптеру", v -> onConnectClicked());
        root.addView(btnConnect);

        btnDtc = makeButton("Прочитать ошибки", v -> readDtc());
        btnClear = makeButton("Стереть ошибки", v -> confirmClear());
        root.addView(row(btnDtc, btnClear));

        btnLive = makeButton("Живые данные", v -> toggleLive());
        btnVin = makeButton("Номер VIN", v -> readVin());
        root.addView(row(btnLive, btnVin));

        btnShare = makeButton("Отправить отчёт", v -> shareReport());
        btnHistory = makeButton("История проверок", v -> showHistory());
        root.addView(row(btnShare, btnHistory));

        btnLog = makeButton("Отправить журнал (для поддержки)", v -> shareLog());
        root.addView(btnLog);

        ScrollView scroll = new ScrollView(this);
        output = new TextView(this);
        output.setTypeface(Typeface.MONOSPACE);
        output.setTextSize(13);
        output.setTextIsSelectable(true);
        output.setPadding(0, dp(8), 0, 0);
        scroll.addView(output);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    private void setConnectedUi(boolean connected) {
        btnDtc.setEnabled(connected);
        btnClear.setEnabled(connected);
        btnLive.setEnabled(connected);
        btnVin.setEnabled(connected);
        status.setText(connected ? "Подключено: " + deviceName : "Не подключено");
    }

    private void show(String text) {
        runOnUiThread(() -> output.setText(text));
    }

    private void setStatus(String text) {
        runOnUiThread(() -> status.setText(text));
    }

    // ---------- подключение ----------

    private void onConnectClicked() {
        if (Build.VERSION.SDK_INT >= 31
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 1);
            return;
        }
        chooseDevice();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == 1 && res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) {
            chooseDevice();
        } else {
            show("Нужно разрешение «Устройства поблизости» — без него Bluetooth не работает.");
        }
    }

    private void chooseDevice() {
        BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
        if (ad == null || !ad.isEnabled()) {
            show("Включите Bluetooth на телефоне и нажмите кнопку ещё раз.");
            return;
        }
        List<BluetoothDevice> list = new ArrayList<>(ad.getBondedDevices());
        if (list.isEmpty()) {
            show("Нет связанных устройств. Откройте настройки Bluetooth, найдите OBDLink LX и свяжите его (PIN 1234 или 0000).");
            return;
        }
        // адаптеры с «OBD» в названии — наверх
        list.sort((x, y) -> Boolean.compare(looksLikeObd(y), looksLikeObd(x)));
        String[] names = new String[list.size()];
        for (int i = 0; i < names.length; i++) {
            BluetoothDevice d = list.get(i);
            String n = d.getName() != null ? d.getName() : "Без имени";
            names[i] = n + "\n" + d.getAddress();
        }
        new AlertDialog.Builder(this)
                .setTitle("Выберите адаптер")
                .setItems(names, (dlg, i) -> connect(list.get(i)))
                .show();
    }

    private boolean looksLikeObd(BluetoothDevice d) {
        String n = d.getName();
        if (n == null) return false;
        n = n.toUpperCase(Locale.ROOT);
        return n.contains("OBD") || n.contains("ELM") || n.contains("VLINK") || n.contains("STN");
    }

    private void connect(BluetoothDevice dev) {
        liveRunning = false;
        lastVin = "";
        lastDtcText = "";
        lastLiveText = "";
        deviceName = dev.getName() != null ? dev.getName() : dev.getAddress();
        setStatus("Подключаюсь к " + deviceName + "…");
        show("Подключаюсь. Это может занять до 15 секунд…");
        worker.submit(() -> {
            try {
                if (obd != null) obd.close();
                ObdConnection c = new ObdConnection();
                c.connect(dev);
                obd = c;
                runOnUiThread(() -> setConnectedUi(true));
                if (c.carResponding) {
                    show("Готово. Адаптер подключён, машина отвечает ("
                            + (c.can ? "шина CAN" : "старый протокол") + ").\n\n"
                            + "Теперь можно читать ошибки и данные.");
                } else {
                    show("Адаптер подключён, но машина не отвечает.\n"
                            + "Включите зажигание (можно не заводить), проверьте, что адаптер плотно вставлен, и подключитесь снова.");
                }
            } catch (Exception e) {
                AppLog.logError("подключение", e);
                runOnUiThread(() -> setConnectedUi(false));
                show("Не удалось подключиться: " + e.getMessage()
                        + "\n\nПроверьте: адаптер вставлен, зажигание включено, адаптер связан с телефоном в настройках Bluetooth.");
            }
        });
    }

    private void runTask(ObdTask task) {
        liveRunning = false;
        worker.submit(() -> {
            if (obd == null) {
                show("Сначала подключитесь к адаптеру.");
                return;
            }
            try {
                task.run();
            } catch (IOException e) {
                AppLog.logError("связь", e);
                runOnUiThread(() -> setConnectedUi(false));
                show("Связь потеряна: " + e.getMessage() + "\nПодключитесь заново.");
            } catch (Exception e) {
                AppLog.logError("задача", e);
                show("Ошибка: " + e + "\n\nПричина записана в журнал — нажмите «Отправить журнал».");
            }
        });
    }

    // ---------- ошибки ----------

    private void readDtc() {
        show("Читаю ошибки…");
        runTask(() -> {
            StringBuilder sb = new StringBuilder();
            if (lastVin.isEmpty()) {
                lastVin = ObdParser.parseVin(obd.send("0902", 6000));
            }
            String vin = lastVin;
            if (!vin.isEmpty()) sb.append("VIN: ").append(vin).append("\n");
            boolean mil = false;
            String r = obd.send("0101", 4000);
            List<Integer> st = ObdParser.findData(r, 1, 0x01);
            if (st != null && !st.isEmpty()) {
                mil = (st.get(0) & 0x80) != 0;
                sb.append("Лампа Check Engine: ").append(mil ? "ГОРИТ" : "не горит").append("\n");
                sb.append("Ошибок по данным блока: ").append(st.get(0) & 0x7F).append("\n");
            }
            sb.append("\n");
            List<List<String>> found = new ArrayList<>();
            String[][] sections = {
                    {"03", "СОХРАНЁННЫЕ ОШИБКИ"},
                    {"07", "ОЖИДАЮЩИЕ (появлялись, но ещё не подтверждены)"},
                    {"0A", "ПОСТОЯННЫЕ (сброс их не стирает)"}
            };
            for (String[] s : sections) {
                int mode = Integer.parseInt(s[0], 16);
                String raw = obd.send(s[0], 6000);
                Set<String> codes = new java.util.LinkedHashSet<>();
                for (List<Integer> data : ObdParser.findAllData(raw, mode, -1)) {
                    codes.addAll(ObdParser.parseDtcs(data, obd.can));
                }
                found.add(new ArrayList<>(codes));
                AppLog.log("Ошибки режим " + s[0] + ": " + codes);
                sb.append(s[1]).append(":\n");
                if (codes.isEmpty()) {
                    sb.append("  — нет —\n");
                } else {
                    for (String c : codes) {
                        sb.append("  ").append(c).append(" — ").append(DtcDatabase.describe(c)).append("\n");
                    }
                }
                sb.append("\n");
            }
            HistoryStore.Entry cur = new HistoryStore.Entry(System.currentTimeMillis(), vin, mil,
                    found.get(0), found.get(1), found.get(2));
            HistoryStore.Entry prev = HistoryStore.lastFor(HistoryStore.readAll(getFilesDir()), vin);
            if (prev != null) {
                sb.append(HistoryStore.diffText(prev, cur));
            } else {
                sb.append("Это первая проверка этой машины в приложении.\n\n");
            }
            if (vin.isEmpty()) {
                sb.append("VIN не определён — сравнение и история могут относиться к другой машине.\n");
            }
            HistoryStore.append(getFilesDir(), cur);
            lastDtcText = sb.toString();
            show(lastDtcText);
        });
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle("Стереть ошибки?")
                .setMessage("Это нельзя отменить. Сохранённые ошибки и данные «замороженного кадра» пропадут, лампа погаснет.\n\n"
                        + "Если вы осматриваете машину перед покупкой — сначала прочитайте ошибки и сохраните отчёт: "
                        + "после стирания проблемы не видны, пока не вернутся.")
                .setPositiveButton("Стереть", (d, w) -> clearDtc())
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void clearDtc() {
        show("Стираю ошибки…");
        runTask(() -> {
            AppLog.log("Пользователь стирает ошибки");
            String raw = obd.send("04", 6000);
            if (ObdParser.findData(raw, 4, -1) != null) {
                show("Ошибки стёрты. Если проблема осталась, лампа и ошибки вернутся.");
            } else {
                show("Машина не подтвердила стирание. Попробуйте при включённом зажигании и заглушенном двигателе.");
            }
        });
    }

    // ---------- VIN ----------

    private void readVin() {
        show("Читаю VIN…");
        runTask(() -> {
            String raw = obd.send("0902", 6000);
            String vin = ObdParser.parseVin(raw);
            if (vin.isEmpty()) {
                show("Машина не отдаёт VIN через OBD (так бывает на старых авто).");
            } else {
                lastVin = vin;
                show("VIN: " + vin);
            }
        });
    }

    // ---------- живые данные ----------

    private void toggleLive() {
        if (liveRunning) {
            liveRunning = false;
            return;
        }
        liveRunning = true;
        runOnUiThread(() -> btnLive.setText("Остановить"));
        worker.submit(() -> {
            Set<Integer> unsupported = new HashSet<>();
            boolean first = true;
            try {
                while (liveRunning && obd != null) {
                    StringBuilder sb = new StringBuilder("ЖИВЫЕ ДАННЫЕ (обновляются)\n\n");
                    obd.logTraffic = first;
                    for (int pid : ObdParser.LIVE_PIDS) {
                        if (!liveRunning) break;
                        if (unsupported.contains(pid)) continue;
                        String raw = obd.send(String.format(Locale.ROOT, "01%02X", pid), 2000);
                        List<Integer> d = ObdParser.findData(raw, 1, pid);
                        String line = d == null ? null : ObdParser.describePid(pid, d);
                        if (line == null) {
                            if (first) unsupported.add(pid);
                        } else {
                            sb.append(line).append("\n");
                        }
                    }
                    first = false;
                    if (liveRunning) {
                        lastLiveText = sb.toString();
                        show(lastLiveText);
                    }
                }
            } catch (IOException e) {
                AppLog.logError("живые данные", e);
                runOnUiThread(() -> setConnectedUi(false));
                show("Связь потеряна: " + e.getMessage() + "\nПодключитесь заново.");
            } finally {
                if (obd != null) obd.logTraffic = true;
                liveRunning = false;
                runOnUiThread(() -> btnLive.setText("Живые данные"));
            }
        });
    }

    // ---------- отчёт ----------

    private void shareReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("ОТЧЁТ OBD\n");
        sb.append(new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(new Date())).append("\n");
        if (!lastVin.isEmpty()) sb.append("VIN: ").append(lastVin).append("\n");
        sb.append("\n");
        sb.append(lastDtcText.isEmpty() ? "Ошибки: ещё не читались\n\n" : lastDtcText + "\n");
        if (!lastLiveText.isEmpty()) sb.append(lastLiveText);
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_SUBJECT, "Отчёт OBD");
        i.putExtra(Intent.EXTRA_TEXT, sb.toString());
        startActivity(Intent.createChooser(i, "Отправить отчёт"));
    }

    // ---------- история и журнал ----------

    private void showHistory() {
        liveRunning = false;
        List<HistoryStore.Entry> all = HistoryStore.readAll(getFilesDir());
        if (all.isEmpty()) {
            show("История пуста. Она появится после первой проверки ошибок.");
            return;
        }
        StringBuilder sb = new StringBuilder("ИСТОРИЯ ПРОВЕРОК (новые сверху)\n\n");
        int shown = 0;
        for (int i = all.size() - 1; i >= 0 && shown < 30; i--, shown++) {
            sb.append(HistoryStore.describe(all.get(i))).append("\n");
        }
        show(sb.toString());
    }

    private void shareLog() {
        String text = "ЖУРНАЛ OBD СКАНЕРА\n\n" + AppLog.read(60000);
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_SUBJECT, "Журнал OBD Сканер");
        i.putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(i, "Отправить журнал"));
    }

    @Override
    protected void onDestroy() {
        liveRunning = false;
        if (obd != null) obd.close();
        worker.shutdownNow();
        super.onDestroy();
    }
}
