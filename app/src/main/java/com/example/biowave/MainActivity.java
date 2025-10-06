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
import android.bluetooth.le.ScanSettings;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "BLEScannerDemo3";
    private static final long SCAN_PERIOD = 10000; // 10 sec
    private static final int PERMISSION_REQUEST_CODE = 1;

    // --- GATT and Connection Variables ---
    private static final String TARGET_DEVICE_NAME = "DSD TECH";
    private static final UUID SERVICE_UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb");
    private static final UUID CHARACTERISTIC_UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb");

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bluetoothLeScanner;
    private BluetoothGatt bluetoothGatt;
    private Handler handler;
    private boolean isScanning = false;

    private TextView deviceList; // Shows status (Connected, Scanning, etc.)
    private Button scanButton;

    private ListView dataListView;               // Shows incoming data
    private ArrayAdapter<String> dataAdapter;    // Adapter for ListView
    private ArrayList<String> dataList;          // Storage for data

    private StringBuilder scanResults = new StringBuilder();

    private StringBuilder bleBuffer = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        deviceList = findViewById(R.id.deviceList);
        scanButton = findViewById(R.id.scanButton);
        dataListView = findViewById(R.id.dataListView);

        // Setup ArrayAdapter for incoming data
        dataList = new ArrayList<>();
        dataAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, dataList);
        dataListView.setAdapter(dataAdapter);

        handler = new Handler();
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        if (bluetoothAdapter == null) {
            deviceList.setText("Bluetooth not supported on this device.");
            scanButton.setEnabled(false);
            return;
        }

        bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();

        // Check and request permissions
        if (checkBlePermissions()) {
            setupScanButton();
        } else {
            requestBlePermissions();
        }
    }

    // -------------------------------------------------------------------------
    // PERMISSIONS
    // -------------------------------------------------------------------------

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
        ActivityCompat.requestPermissions(MainActivity.this, permissions, PERMISSION_REQUEST_CODE);
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

    // -------------------------------------------------------------------------
    // SCANNER
    // -------------------------------------------------------------------------

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
                scanResults.setLength(0);
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
            // ignore
        } else if (bluetoothLeScanner != null) {
            bluetoothLeScanner.stopScan(leScanCallback);
        }

        isScanning = false;
        handler.removeCallbacksAndMessages(null);
        scanButton.setText("START SCAN");

        if (!scanResults.toString().contains(TARGET_DEVICE_NAME)) {
            deviceList.setText("No " + TARGET_DEVICE_NAME + " found.");
        }
    }

    private final ScanCallback leScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            String name = getDeviceName(device);

            if (name != null && name.equals(TARGET_DEVICE_NAME)) {
                Log.d(TAG, "Found target device: " + name);

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

    // -------------------------------------------------------------------------
    // GATT CALLBACK
    // -------------------------------------------------------------------------

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothGatt.STATE_CONNECTED) {
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
            } else {
                runOnUiThread(() -> deviceList.setText("Connection failed. Status: " + status));
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

                        // ✅ Check permission before using BluetoothGatt calls
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                                ContextCompat.checkSelfPermission(MainActivity.this,
                                        Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                            runOnUiThread(() -> deviceList.setText("Missing BLUETOOTH_CONNECT permission."));
                            return;
                        }

                        // Enable notifications
                        gatt.setCharacteristicNotification(characteristic, true);

                        // Enable CCCD notifications
                        BluetoothGattDescriptor descriptor = characteristic.getDescriptor(
                                UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));

                        if (descriptor != null) {
                            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                            gatt.writeDescriptor(descriptor);

                            runOnUiThread(() -> deviceList.setText("Listening for data..."));
                        }
                    } else {
                        runOnUiThread(() -> deviceList.setText("Error: Characteristic not found."));
                    }
                } else {
                    runOnUiThread(() -> deviceList.setText("Error: Service not found."));
                }
            } else {
                Log.e(TAG, "onServicesDiscovered received: " + status);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            if (CHARACTERISTIC_UUID.equals(characteristic.getUuid())) {
                byte[] data = characteristic.getValue();

                // Decode bytes as UTF-8 (ASCII compatible)
                String chunk = new String(data, StandardCharsets.UTF_8);

                runOnUiThread(() -> {
                    bleBuffer.append(chunk);

                    int index;
                    // Look for \n (line terminator)
                    while ((index = bleBuffer.indexOf("\n")) != -1) {
                        String fullMessage = bleBuffer.substring(0, index);
                        bleBuffer.delete(0, index + 1);

                        // Clean the message: remove \r and any non-printable characters
                        fullMessage = fullMessage.replaceAll("[\\r\\x00-\\x1F\\x7F]", "").trim();

                        // Skip empty messages
                        if (fullMessage.isEmpty()) {
                            continue;
                        }

                        // Parse individual messages: E<value> or P<value>
                        if (fullMessage.startsWith("E") && fullMessage.length() > 1) {
                            try {
                                String ecgValue = fullMessage.substring(1); // remove 'E'
                                Float.parseFloat(ecgValue); // Validate it's a number
                                dataList.add("ECG: " + ecgValue);
                            } catch (NumberFormatException e) {
                                dataList.add("Invalid ECG: " + fullMessage);
                            }
                        } else if (fullMessage.startsWith("P") && fullMessage.length() > 1) {
                            try {
                                String ppgValue = fullMessage.substring(1); // remove 'P'
                                Float.parseFloat(ppgValue); // Validate it's a number
                                dataList.add("PPG: " + ppgValue);
                            } catch (NumberFormatException e) {
                                dataList.add("Invalid PPG: " + fullMessage);
                            }
                        }

                        // Update list and scroll to last
                        dataAdapter.notifyDataSetChanged();
                        dataListView.smoothScrollToPosition(dataAdapter.getCount() - 1);
                    }
                });
            }
        }

    };

    // -------------------------------------------------------------------------
    // CLEANUP
    // -------------------------------------------------------------------------

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

    private String getDeviceName(BluetoothDevice device) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return "Permission Denied";
        }
        return device.getName();
    }
}