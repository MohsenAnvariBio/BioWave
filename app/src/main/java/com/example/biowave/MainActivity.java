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
import java.util.ArrayList;
import java.util.List;
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

    private StringBuilder bleBuffer = new StringBuilder();

    private LineChart chart;
    private LineDataSet ecgDataSet, ppgDataSet;
    private LineData lineData;
    private int sampleIndex = 0;

    // ✅ Amplitude scaling factor
    private float amplitudeScale = 1.0f;

    // ✅ Y-axis auto-range control
    private int outOfRangeCount = 0;
    private static final float DEFAULT_Y_LIMIT = 3000f;

    // ✅ Buffer for maximum calculation
    private final List<Float> signalBuffer = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        chart = findViewById(R.id.combinedChart);
        setupChart(chart);

        deviceList = findViewById(R.id.deviceList);
        scanButton = findViewById(R.id.scanButton);

        Button increaseButton = findViewById(R.id.increaseButton);
        Button decreaseButton = findViewById(R.id.decreaseButton);

        increaseButton.setOnClickListener(v -> {
            amplitudeScale *= 1.2f;
            Toast.makeText(this, "Amplitude: x" + String.format("%.2f", amplitudeScale), Toast.LENGTH_SHORT).show();
        });

        decreaseButton.setOnClickListener(v -> {
            amplitudeScale /= 1.2f;
            if (amplitudeScale < 0.1f) amplitudeScale = 0.1f;
            Toast.makeText(this, "Amplitude: x" + String.format("%.2f", amplitudeScale), Toast.LENGTH_SHORT).show();
        });

        handler = new Handler();
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        if (bluetoothAdapter == null) {
            deviceList.setText("Bluetooth not supported.");
            scanButton.setEnabled(false);
            return;
        }

        bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();

        if (checkBlePermissions()) {
            setupScanButton();
        } else {
            requestBlePermissions();
        }
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
            permissions = new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
            };
        } else {
            permissions = new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION
            };
        }
        ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (allGranted) {
                deviceList.setText("Permissions granted. Ready to scan.");
                setupScanButton();
            } else {
                deviceList.setText("Permissions denied. Cannot scan.");
                scanButton.setEnabled(false);
            }
        }
    }

    private void setupChart(LineChart chart) {
        ecgDataSet = new LineDataSet(null, "ECG");
        ecgDataSet.setColor(0xFFE53935);
        ecgDataSet.setDrawCircles(false);
        ecgDataSet.setLineWidth(1.5f);

        ppgDataSet = new LineDataSet(null, "PPG");
        ppgDataSet.setColor(0xFF43A047);
        ppgDataSet.setDrawCircles(false);
        ppgDataSet.setLineWidth(1.5f);

        lineData = new LineData(ecgDataSet, ppgDataSet);
        chart.setData(lineData);

        // Chart interaction
        chart.setTouchEnabled(true);
        chart.setDragEnabled(true);
        chart.setScaleEnabled(true);
        chart.setPinchZoom(true);
        chart.setHardwareAccelerationEnabled(true);
        chart.setDragDecelerationEnabled(true);
        chart.setDragDecelerationFrictionCoef(0.9f);
        chart.getDescription().setEnabled(false);

        // Optional: light background to highlight grid
        chart.setDrawGridBackground(false);

        // X Axis
        XAxis xAxis = chart.getXAxis();
        xAxis.setDrawGridLines(true);
        xAxis.setGridColor(0x22000000);
        xAxis.setGridLineWidth(0.8f);
        xAxis.setLabelCount(20, true);
        xAxis.setDrawLabels(false);
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);

        // Y Axis (left)
        YAxis left = chart.getAxisLeft();
        left.setDrawGridLines(true);
        left.setGridColor(0x22000000);
        left.setGridLineWidth(0.8f);
        left.setLabelCount(20, true);
        left.setAxisMinimum(-DEFAULT_Y_LIMIT);
        left.setAxisMaximum(DEFAULT_Y_LIMIT);
        // Disable right axis
        chart.getAxisRight().setEnabled(false);

        // Legend
        Legend legend = chart.getLegend();
        legend.setEnabled(true);
        legend.setTextSize(14f);

        chart.invalidate();
    }


    private void addEntry(float ecg, float ppg) {
        ecgDataSet.addEntry(new Entry(sampleIndex, ecg * amplitudeScale));
        ppgDataSet.addEntry(new Entry(sampleIndex, ppg * amplitudeScale));
        sampleIndex++;

        // Limit number of points
        if (ecgDataSet.getEntryCount() > 3000) {
            ecgDataSet.removeFirst();
            ppgDataSet.removeFirst();
        }

        lineData.notifyDataChanged();
        chart.notifyDataSetChanged();
        chart.setVisibleXRangeMaximum(300);

        float absEcg = Math.abs(ecg * amplitudeScale);
        float absPpg = Math.abs(ppg * amplitudeScale);
        float maxValue = Math.max(absEcg, absPpg);

        // Find max Y value currently in the visible chart window
        float visibleMaxY = 0f;
        for (Entry e : ecgDataSet.getValues()) {
            float absY = Math.abs(e.getY());
            if (absY > visibleMaxY) visibleMaxY = absY;
        }
        for (Entry e : ppgDataSet.getValues()) {
            float absY = Math.abs(e.getY());
            if (absY > visibleMaxY) visibleMaxY = absY;
        }

        YAxis leftAxis = chart.getAxisLeft();

        // Dynamic zoom logic with anti-flicker
        if (maxValue > DEFAULT_Y_LIMIT) {
            outOfRangeCount++;
            if (outOfRangeCount >= 10) {
                float newMax = (float) (Math.ceil(visibleMaxY / 1000f) * 1000f);
                leftAxis.setAxisMaximum(newMax);
                leftAxis.setAxisMinimum(-newMax);
                leftAxis.setLabelCount(6, true);
                outOfRangeCount = 0;
            }
        } else {
            outOfRangeCount = 0;
            if (leftAxis.getAxisMaximum() != DEFAULT_Y_LIMIT || leftAxis.getAxisMinimum() != -DEFAULT_Y_LIMIT) {
                leftAxis.setAxisMaximum(DEFAULT_Y_LIMIT);
                leftAxis.setAxisMinimum(-DEFAULT_Y_LIMIT);
                leftAxis.setLabelCount(6, true);
            }
        }

        chart.moveViewToX(sampleIndex);
    }

    private void setupScanButton() {
        scanButton.setEnabled(true);
        scanButton.setText("START SCAN");
        scanButton.setOnClickListener(v -> {
            if (!bluetoothAdapter.isEnabled()) {
                Toast.makeText(this, "Enable Bluetooth first.", Toast.LENGTH_SHORT).show();
                return;
            }

            if (!checkBlePermissions()) {
                Toast.makeText(this, "Permission missing.", Toast.LENGTH_SHORT).show();
                requestBlePermissions();
                return;
            }

            if (isScanning) {
                stopScanning();
            } else {
                scanLeDevice(true);
            }
        });
    }

    private void scanLeDevice(final boolean enable) {
        if (bluetoothLeScanner == null) {
            deviceList.setText("BLE scanner not available.");
            return;
        }

        if (enable) {
            deviceList.setText("Scanning for " + TARGET_DEVICE_NAME + "...");
            isScanning = true;
            handler.postDelayed(this::stopScanning, SCAN_PERIOD);
            try {
                bluetoothLeScanner.startScan(leScanCallback);
                scanButton.setText("STOP SCAN");
            } catch (SecurityException e) {
                Log.e(TAG, "Scan start failed: missing permission", e);
                Toast.makeText(this, "Scan failed: permission missing", Toast.LENGTH_SHORT).show();
            }
        } else {
            stopScanning();
        }
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
                                addEntry(ecg, ppg);
                            } catch (Exception e) {
                                Log.e(TAG, "Parse error: " + msg);
                            }
                        }
                    }
                });
            }
        }
    };

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
