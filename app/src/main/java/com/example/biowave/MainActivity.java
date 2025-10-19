package com.example.biowave;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.Legend;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "BioWave";
    private static final long SCAN_PERIOD = 30000;
    private static final int PERMISSION_REQUEST_CODE = 1;
    private static final String TARGET_DEVICE_NAME = "DSD TECH";
    private static final UUID SERVICE_UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb");
    private static final UUID CHARACTERISTIC_UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb");

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bluetoothLeScanner;
    private BluetoothGatt bluetoothGatt;
    private Handler handler;
    private boolean isScanning = false;
    private TextView deviceList;
    private Button scanButton;

    // Charts
    private LineChart ecgChart, ppgChart;
    private LineDataSet ecgDataSet, ppgDataSet;
    private LineData ecgLineData, ppgLineData;

    // Data
    private int sampleIndex = 0;
    private float amplitudeScale = 1.0f;
    private static final float ECG_DEFAULT_MIN = -3f;
    private static final float ECG_DEFAULT_MAX = 3f;
    private static final float PPG_DEFAULT_MIN = -5000f;
    private static final float PPG_DEFAULT_MAX = 5000f;
    private float visibleWindow = 700f;

    private TextView spo2TextView, hrTextView, tempTextView;
    private Switch autoYECGSwitch, autoYPPGSwitch;
    private boolean autoYECGEnabled = true;
    private boolean autoYPPGEnabled = true;

    private final StringBuilder bleBuffer = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // === Chart setup ===
        ecgChart = findViewById(R.id.ecgChart);
        ppgChart = findViewById(R.id.ppgChart);
        setupChart(ecgChart, "ECG", 0xFFE53935);
        setupChart(ppgChart, "PPG", 0xFF43A047);

        // === UI Elements ===
        deviceList = findViewById(R.id.deviceList);
        scanButton = findViewById(R.id.scanButton);
        Button increaseButton = findViewById(R.id.increaseButton);
        Button decreaseButton = findViewById(R.id.decreaseButton);
        spo2TextView = findViewById(R.id.spo2TextView);
        hrTextView = findViewById(R.id.hrTextView);
        tempTextView = findViewById(R.id.tempTextView);

        // switches (two independent)
        autoYECGSwitch = findViewById(R.id.autoYECGSwitch);
        autoYPPGSwitch = findViewById(R.id.autoYPPGSwitch);



        // default values for measurement text
        spo2TextView.setText("98 %");
        hrTextView.setText("76 bpm");
        tempTextView.setText("36.7 °C");

        // switches listeners
        autoYECGSwitch.setChecked(true);
        autoYECGSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            autoYECGEnabled = isChecked;
            String mode = isChecked ? "ECG Auto Y: ON" : "ECG Auto Y: OFF (Fixed 0..3)";
            Toast.makeText(this, mode, Toast.LENGTH_SHORT).show();
            // reset or immediately adjust axis when toggled
            if (!isChecked) resetYAxis(ecgChart, true);
        });

        autoYPPGSwitch.setChecked(true);
        autoYPPGSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            autoYPPGEnabled = isChecked;
            String mode = isChecked ? "PPG Auto Y: ON" : "PPG Auto Y: OFF (Fixed ±6000)";
            Toast.makeText(this, mode, Toast.LENGTH_SHORT).show();
            if (!isChecked) resetYAxis(ppgChart, false);
        });

        // amplitude control
        increaseButton.setOnClickListener(v -> {
            amplitudeScale *= 1.2f;
            Toast.makeText(this, "Amplitude: x" + String.format("%.2f", amplitudeScale), Toast.LENGTH_SHORT).show();
        });

        decreaseButton.setOnClickListener(v -> {
            amplitudeScale /= 1.2f;
            if (amplitudeScale < 0.1f) amplitudeScale = 0.1f;
            Toast.makeText(this, "Amplitude: x" + String.format("%.2f", amplitudeScale), Toast.LENGTH_SHORT).show();
        });

        // === Bluetooth setup ===
        handler = new Handler();
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        if (bluetoothAdapter == null) {
            deviceList.setText("Bluetooth not supported.");
            scanButton.setEnabled(false);
            return;
        }

        bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();

        if (checkBlePermissions()) setupScanButton();
        else requestBlePermissions();
    }

    private boolean checkBlePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        } else {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }
    }

    private void requestBlePermissions() {
        String[] permissions;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions = new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT};
        } else {
            permissions = new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
        }
        ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults)
                if (result != PackageManager.PERMISSION_GRANTED) allGranted = false;
            if (allGranted) {
                deviceList.setText("Permissions granted. Ready to scan.");
                setupScanButton();
            } else {
                deviceList.setText("Permissions denied. Cannot scan.");
                scanButton.setEnabled(false);
            }
        }
    }

    private void setupChart(LineChart chart, String label, int color) {
        LineDataSet dataSet = new LineDataSet(null, label);
        dataSet.setColor(color);
        dataSet.setDrawCircles(false);
        dataSet.setLineWidth(1.5f);

        LineData lineData = new LineData(dataSet);
        chart.setData(lineData);

        chart.setTouchEnabled(true);
        chart.setDragEnabled(true);
        chart.setScaleEnabled(true);
        chart.setPinchZoom(true);
        chart.setHardwareAccelerationEnabled(true);
        chart.getDescription().setEnabled(false);
        chart.setDrawGridBackground(false);
        chart.setDrawBorders(false);
        // Remove all padding/offsets to stick charts together
        chart.setExtraOffsets(0, 0, 0, 0);
        chart.setViewPortOffsets(0, 0, 0, 0);


        XAxis xAxis = chart.getXAxis();
        xAxis.setDrawGridLines(true);
        xAxis.setGridColor(0x22000000);
//        xAxis.setLabelCount(20, true);
        xAxis.setDrawLabels(false);

        YAxis left = chart.getAxisLeft();
        left.setDrawGridLines(true);
        left.setGridColor(0x22000000);
//        left.setLabelCount(6, true);
        left.setDrawLabels(true);


        // set default axis limits per chart type
        if (label.equals("ECG")) {
            left.setAxisMinimum(ECG_DEFAULT_MIN);
            left.setAxisMaximum(ECG_DEFAULT_MAX);
        } else {
            left.setAxisMinimum(PPG_DEFAULT_MIN);
            left.setAxisMaximum(PPG_DEFAULT_MAX);
        }

        chart.getAxisRight().setEnabled(false);

        Legend legend = chart.getLegend();
        legend.setEnabled(true);
        legend.setTextSize(12f);

        chart.invalidate();

        if (label.equals("ECG")) {
            ecgDataSet = dataSet;
            ecgLineData = lineData;
        } else {
            ppgDataSet = dataSet;
            ppgLineData = lineData;
        }
    }

    private void setupScanButton() {
        scanButton.setEnabled(true);
        scanButton.setText("START SCAN");
        scanButton.setOnClickListener(v -> {
            if (!bluetoothAdapter.isEnabled()) {
                Toast.makeText(this, "Enable Bluetooth.", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!checkBlePermissions()) {
                Toast.makeText(this, "Permission missing.", Toast.LENGTH_SHORT).show();
                requestBlePermissions();
                return;
            }
            if (isScanning) stopScanning();
            else scanLeDevice(true);
        });
    }

    private void scanLeDevice(final boolean enable) {
        if (bluetoothLeScanner == null) {
            deviceList.setText("BLE scanner not available.");
            return;
        }

        if (enable) {
            deviceList.setText("Scanning: " + TARGET_DEVICE_NAME);
            isScanning = true;
            handler.postDelayed(this::stopScanning, SCAN_PERIOD);
            try {
                bluetoothLeScanner.startScan(leScanCallback);
                scanButton.setText("STOP SCAN");
            } catch (SecurityException e) {
                Log.e(TAG, "Scan start failed", e);
            }
        } else stopScanning();
    }

    private void stopScanning() {
        if (!isScanning) return;
        try {
            bluetoothLeScanner.stopScan(leScanCallback);
        } catch (SecurityException e) {
            Log.e(TAG, "Stop scan failed", e);
        }
        isScanning = false;
        handler.removeCallbacksAndMessages(null);
        scanButton.setText("START SCAN");
    }

    private final ScanCallback leScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            String name = device.getName();
            if (name != null && name.equals(TARGET_DEVICE_NAME)) {
                stopScanning();
                runOnUiThread(() -> deviceList.setText("Found device: connecting..."));
                try {
                    bluetoothGatt = device.connectGatt(MainActivity.this, false, gattCallback);
                } catch (SecurityException e) {
                    runOnUiThread(() -> deviceList.setText("Connect failed: permission missing"));
                }
            }
        }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothGatt.STATE_CONNECTED) {
                runOnUiThread(() -> deviceList.setText("Connected. Discovering services..."));
                try {
                    gatt.discoverServices();
                } catch (SecurityException e) {
                    Log.e(TAG, "Service discovery failed", e);
                }
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                runOnUiThread(() -> deviceList.setText("Disconnected."));
                closeGatt();
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                BluetoothGattService service = gatt.getService(SERVICE_UUID);
                if (service != null) {
                    BluetoothGattCharacteristic ch = service.getCharacteristic(CHARACTERISTIC_UUID);
                    if (ch != null) {
                        try {
                            gatt.setCharacteristicNotification(ch, true);
                            BluetoothGattDescriptor descriptor = ch.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));
                            if (descriptor != null) {
                                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                                gatt.writeDescriptor(descriptor);
                                runOnUiThread(() -> deviceList.setText("Connected"));
                            }
                        } catch (SecurityException e) {
                            Log.e(TAG, "Notification setup failed", e);
                        }
                    }
                }
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            if (CHARACTERISTIC_UUID.equals(characteristic.getUuid())) {
                byte[] data = characteristic.getValue();
                String chunk = new String(data, StandardCharsets.UTF_8);
                runOnUiThread(() -> {
                    bleBuffer.append(chunk);
                    int index;
                    while ((index = bleBuffer.indexOf("\n")) != -1) {
                        String msg = bleBuffer.substring(0, index);
                        bleBuffer.delete(0, index + 1);
                        msg = msg.replaceAll("[\\r\\x00-\\x1F\\x7F]", "").trim();
                        if (msg.startsWith("E:") && msg.contains(";P:")) {
                            try {
                                String[] parts = msg.split(";");
                                float ecg = Float.parseFloat(parts[0].substring(2));
                                float ppg = Float.parseFloat(parts[1].substring(2));
                                float spo2 = Float.NaN;
                                if (parts.length >= 3 && parts[2].startsWith("S:"))
                                    spo2 = Float.parseFloat(parts[2].substring(2));
                                addEntry(-ecg, -ppg, spo2);
                            } catch (Exception e) {
                                Log.e(TAG, "Parse error: " + msg);
                            }
                        }
                    }
                });
            }
        }
    };

    private void addEntry(float ecg, float ppg, float spo2) {
        // add points
        ecgDataSet.addEntry(new Entry(sampleIndex, ecg * amplitudeScale));
        ppgDataSet.addEntry(new Entry(sampleIndex, ppg * amplitudeScale));
        sampleIndex++;

        // trim oldest
        if (ecgDataSet.getEntryCount() > visibleWindow) {
            ecgDataSet.removeFirst();
        }
        if (ppgDataSet.getEntryCount() > visibleWindow) {
            ppgDataSet.removeFirst();
        }

        // notify datasets & charts
        ecgLineData.notifyDataChanged();
        ppgLineData.notifyDataChanged();
        ecgChart.notifyDataSetChanged();
        ppgChart.notifyDataSetChanged();

        ecgChart.setVisibleXRangeMaximum(visibleWindow);
        ppgChart.setVisibleXRangeMaximum(visibleWindow);
        ecgChart.moveViewToX(sampleIndex);
        ppgChart.moveViewToX(sampleIndex);

        // independent auto Y control
        if (autoYECGEnabled) adjustYAxis(ecgChart, ecgDataSet, true);
        else resetYAxis(ecgChart, true);

        if (autoYPPGEnabled) adjustYAxis(ppgChart, ppgDataSet, false);
        else resetYAxis(ppgChart, false);

        if (!Float.isNaN(spo2)) spo2TextView.setText(String.format("%.1f %%", spo2));
    }

    private void adjustYAxis(LineChart chart, LineDataSet set, boolean isEcg) {
        float maxY = Float.NEGATIVE_INFINITY, minY = Float.POSITIVE_INFINITY;
        float highestVisibleX = sampleIndex;
        float lowestVisibleX = highestVisibleX - visibleWindow;
        for (Entry e : set.getValues()) {
            if (e.getX() >= lowestVisibleX && e.getX() <= highestVisibleX) {
                if (e.getY() > maxY) maxY = e.getY();
                if (e.getY() < minY) minY = e.getY();
            }
        }

        // if no entries in visible window, keep defaults
        if (maxY == Float.NEGATIVE_INFINITY || minY == Float.POSITIVE_INFINITY) {
            resetYAxis(chart, isEcg);
            return;
        }

        float margin = 0.1f; // 10% padding
        YAxis leftAxis = chart.getAxisLeft();

        if (isEcg) {
            // ECG: keep default 0..3 unless data exceeds those bounds
            float upper = Math.max(maxY * (1 + margin), ECG_DEFAULT_MAX);
            // ensure lower not above 0
            float lowerCandidate = minY * (1 - margin);
            float lower = Math.min(lowerCandidate, ECG_DEFAULT_MIN);
            // if data entirely above 0, lowerCandidate might be > 0; keep at least 0
            lower = Math.min(lower, ECG_DEFAULT_MIN);
            leftAxis.setAxisMaximum(upper);
            leftAxis.setAxisMinimum(lower);
        } else {
            // PPG: symmetric auto-scaling (allow negative/positive)
            float upper = maxY * (1 + margin);
            float lower = minY * (1 - margin);
            // if upper==lower (flat line), provide tiny range
            if (upper == lower) {
                upper += 1f;
                lower -= 1f;
            }
            leftAxis.setAxisMaximum(upper);
            leftAxis.setAxisMinimum(lower);
        }
    }

    private void resetYAxis(LineChart chart, boolean isEcg) {
        YAxis leftAxis = chart.getAxisLeft();
        if (isEcg) {
            leftAxis.setAxisMaximum(ECG_DEFAULT_MAX);
            leftAxis.setAxisMinimum(ECG_DEFAULT_MIN);
        } else {
            leftAxis.setAxisMaximum(PPG_DEFAULT_MAX);
            leftAxis.setAxisMinimum(PPG_DEFAULT_MIN);
        }
    }

    private void closeGatt() {
        if (bluetoothGatt != null) {
            try {
                bluetoothGatt.close();
            } catch (SecurityException ignored) {}
            bluetoothGatt = null;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopScanning();
        closeGatt();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        closeGatt();
    }
}
