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
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "BioWave";
    private static final long SCAN_PERIOD = 10000;
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

    private LineChart ecgChart, ppgChart;
    private LineDataSet ecgDataSet, ppgDataSet;
    private LineData ecgData, ppgData;
    private int ecgIndex = 0, ppgIndex = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ecgChart = findViewById(R.id.ecgChart);
        ppgChart = findViewById(R.id.ppgChart);
        setupChart(ecgChart, "ECG Signal");
        setupChart(ppgChart, "PPG Signal");

        deviceList = findViewById(R.id.deviceList);
        scanButton = findViewById(R.id.scanButton);

        handler = new Handler();
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        if (bluetoothAdapter == null) {
            deviceList.setText("Bluetooth not supported on this device.");
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

    // -------------------------------------------------------------
    // BLE PERMISSIONS
    // -------------------------------------------------------------
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
                Toast.makeText(this, "Bluetooth permissions are required for scanning.", Toast.LENGTH_LONG).show();
            }
        }
    }

    // -------------------------------------------------------------
    // CHART SETUP
    // -------------------------------------------------------------
    private void setupChart(LineChart chart, String label) {
        LineDataSet set = new LineDataSet(null, label);
        set.setLineWidth(1.5f);
        set.setDrawCircles(false);
        set.setColor(label.contains("ECG") ? 0xFFE53935 : 0xFF43A047);
        set.setMode(LineDataSet.Mode.LINEAR);

        LineData data = new LineData(set);
        chart.setData(data);

        XAxis xAxis = chart.getXAxis();
        xAxis.setDrawLabels(false);
        xAxis.setDrawGridLines(false);

        YAxis left = chart.getAxisLeft();
        left.setDrawGridLines(false);
        chart.getAxisRight().setEnabled(false);

        Legend legend = chart.getLegend();
        legend.setEnabled(true);

        chart.getDescription().setEnabled(false);
        chart.invalidate();

        if (label.contains("ECG")) {
            ecgDataSet = set;
            ecgData = data;
        } else {
            ppgDataSet = set;
            ppgData = data;
        }
    }

    private void addEntry(LineChart chart, LineDataSet dataSet, float value, int index) {
        dataSet.addEntry(new Entry(index, value));
        if (dataSet.getEntryCount() > 200) {
            dataSet.removeFirst();
        }
        chart.getData().notifyDataChanged();
        chart.notifyDataSetChanged();
        chart.setVisibleXRangeMaximum(200);
        chart.moveViewToX(dataSet.getEntryCount());
    }

    // -------------------------------------------------------------
    // BLE SCANNING & CONNECTION
    // -------------------------------------------------------------
    private void setupScanButton() {
        scanButton.setEnabled(true);
        scanButton.setText("START SCAN");
        scanButton.setOnClickListener(v -> {
            if (!bluetoothAdapter.isEnabled()) {
                deviceList.setText("Please enable Bluetooth first.");
                return;
            }

            if (isScanning) {
                scanLeDevice(false);
            } else {
                deviceList.setText("Scanning for " + TARGET_DEVICE_NAME + "...");
                scanLeDevice(true);
            }
        });
    }

    private void scanLeDevice(final boolean enable) {
        if (bluetoothLeScanner == null) return;

        if (enable) {
            if (!checkScanPermission()) {
                deviceList.setText("Error: Missing permission.");
                return;
            }

            isScanning = true;
            handler.postDelayed(this::stopScanning, SCAN_PERIOD);
            bluetoothLeScanner.startScan(leScanCallback);
            scanButton.setText("STOP SCAN");
        } else {
            stopScanning();
        }
    }

    private boolean checkScanPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
        } else {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }
    }

    private void stopScanning() {
        if (!isScanning) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        bluetoothLeScanner.stopScan(leScanCallback);
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
                scanLeDevice(false);
                runOnUiThread(() -> deviceList.setText("Found " + TARGET_DEVICE_NAME + ". Connecting..."));

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    runOnUiThread(() -> deviceList.setText("Connection failed: Missing permission."));
                    return;
                }

                bluetoothGatt = device.connectGatt(MainActivity.this, false, gattCallback);
            }
        }
    };

    // -------------------------------------------------------------
    // BLE DATA HANDLING
    // -------------------------------------------------------------
    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothGatt.STATE_CONNECTED) {
                runOnUiThread(() -> deviceList.setText("Connected. Discovering services..."));
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    return;
                }
                gatt.discoverServices();
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
                    BluetoothGattCharacteristic characteristic = service.getCharacteristic(CHARACTERISTIC_UUID);
                    if (characteristic != null) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                                ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                            return;
                        }

                        gatt.setCharacteristicNotification(characteristic, true);
                        BluetoothGattDescriptor descriptor = characteristic.getDescriptor(
                                UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));
                        if (descriptor != null) {
                            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                            gatt.writeDescriptor(descriptor);
                            runOnUiThread(() -> deviceList.setText("Listening for data..."));
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
                        String fullMessage = bleBuffer.substring(0, index);
                        bleBuffer.delete(0, index + 1);
                        fullMessage = fullMessage.replaceAll("[\\r\\x00-\\x1F\\x7F]", "").trim();

                        if (fullMessage.startsWith("E:") && fullMessage.contains(";P:")) {
                            try {
                                String[] parts = fullMessage.split(";");
                                float ecg = Float.parseFloat(parts[0].substring(2));
                                float ppg = Float.parseFloat(parts[1].substring(2)) * -1;

                                addEntry(ecgChart, ecgDataSet, ecg, ecgIndex++);
                                addEntry(ppgChart, ppgDataSet, ppg, ppgIndex++);
                            } catch (Exception e) {
                                Log.e(TAG, "Parse error: " + fullMessage);
                            }
                        }
                    }
                });
            }
        }
    };

    // -------------------------------------------------------------
    // CLEANUP
    // -------------------------------------------------------------
    private void closeGatt() {
        if (bluetoothGatt == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        bluetoothGatt.close();
        bluetoothGatt = null;
    }

    @Override
    protected void onPause() {
        super.onPause();
        scanLeDevice(false);
        closeGatt();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        closeGatt();
    }
}
