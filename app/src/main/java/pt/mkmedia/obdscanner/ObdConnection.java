package pt.mkmedia.obdscanner;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.UUID;

/** Соединение с адаптером OBDLink LX по Bluetooth. */
public class ObdConnection {
    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private BluetoothSocket socket;
    private InputStream in;
    private OutputStream out;

    public boolean can = true;
    public boolean carResponding = false;
    /** Писать ли каждый запрос и ответ в журнал (в живых данных отключаем, чтобы не засорять). */
    public volatile boolean logTraffic = true;

    /** Подключается и настраивает адаптер. */
    public void connect(BluetoothDevice device) throws IOException {
        AppLog.log("Подключение к " + device.getName() + " " + device.getAddress());
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null) adapter.cancelDiscovery();
        socket = device.createRfcommSocketToServiceRecord(SPP);
        socket.connect();
        AppLog.log("Bluetooth-соединение установлено");
        in = socket.getInputStream();
        out = socket.getOutputStream();

        send("ATZ", 4000);
        send("ATE0", 1500);
        send("ATL0", 1500);
        send("ATS0", 1500);
        send("ATH0", 1500);
        send("ATSP0", 1500);

        // Первый запрос — адаптер ищет, на каком языке говорит машина (до ~10 секунд).
        String raw = send("0100", 12000);
        List<Integer> d = ObdParser.findData(raw, 1, 0x00);
        carResponding = d != null;
        if (carResponding) {
            can = ObdParser.isCanProtocol(send("ATDPN", 1500));
        }
        AppLog.log("Машина отвечает: " + carResponding + ", CAN: " + can);
    }

    public synchronized String send(String cmd, int timeoutMs) throws IOException {
        while (in.available() > 0) in.read();
        if (logTraffic) AppLog.log("→ " + cmd);
        out.write((cmd + "\r").getBytes("US-ASCII"));
        out.flush();
        StringBuilder sb = new StringBuilder();
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (in.available() > 0) {
                int c = in.read();
                if (c < 0) throw new IOException("Соединение закрыто");
                if (c == '>') break;
                sb.append((char) c);
            } else {
                try {
                    Thread.sleep(15);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Прервано");
                }
            }
        }
        if (logTraffic) {
            String resp = sb.toString().trim().replace("\r", " | ").replace("\n", " | ");
            AppLog.log("← " + (resp.isEmpty() ? "(нет ответа)" : resp));
        }
        return sb.toString();
    }

    public void close() {
        AppLog.log("Соединение закрыто");
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
    }
}
