package de.kai_morich.simple_usb_terminal;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class BluetoothDeviceListActivity extends AppCompatActivity {
    private static final String TAG = "BLE_Debug";
    private static final int REQUEST_ENABLE_BT = 1;
    private static final int PERMISSION_REQUEST_CODE = 2;
    private static final long SCAN_TIMEOUT = 10000;

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bleScanner;
    private BluetoothGatt bluetoothGatt;
    private final Set<String> discoveredDevices = new HashSet<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ArrayAdapter<String> deviceListAdapter;
    private boolean isScanning = false;
    // 修改为静态变量并提供访问方法
    private static BluetoothGatt sBluetoothGatt;
    private static String sConnectedDeviceAddress;

    public static BluetoothGatt getBluetoothGatt() {
        return sBluetoothGatt;
    }

    public static String getConnectedDeviceAddress() {
        return sConnectedDeviceAddress;
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            super.onScanResult(callbackType, result);
            processScanResult(result);
        }

        @Override
        public void onScanFailed(int errorCode) {
            super.onScanFailed(errorCode);
            Log.e(TAG, "BLE扫描失败，错误码: " + errorCode);
            runOnUiThread(() -> showToast("扫描失败，错误码: " + errorCode));
            stopScan();
        }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                runOnUiThread(() -> {
                    showToast("连接成功");
                    updateStatus("已连接: " + sConnectedDeviceAddress);
                    gatt.discoverServices();
                    // 跳转到数据发送页面
                    Intent intent = new Intent(BluetoothDeviceListActivity.this, 
                            BluetoothDataActivity.class);
                    // intent.putExtra("DEVICE_ADDRESS", sConnectedDeviceAddress);
                    try {
                        startActivity(intent);
                        Log.d(TAG, "跳转成功");
                    } catch (Exception e) {
                        Log.e(TAG, "跳转失败", e);
                    }
                    
                });
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                runOnUiThread(() -> {
                    showToast("连接断开");
                    updateStatus("点击设备重新连接");
                    closeGatt();
                });
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "发现" + gatt.getServices().size() + "个服务");
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 检查是否已有连接
        if (sBluetoothGatt != null && sConnectedDeviceAddress != null) {
            // 直接跳转到数据页面
            Intent intent = new Intent(this, BluetoothDataActivity.class);
            startActivity(intent);
            finish(); // 结束当前Activity
            return;
        }
    
        setContentView(R.layout.activity_bluetooth_list);

        ListView listView = findViewById(R.id.device_list);
        deviceListAdapter = new ArrayAdapter<String>(this, 
                android.R.layout.simple_list_item_2, 
                android.R.id.text1,
                new ArrayList<String>()) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                String item = getItem(position);
                if (item != null) {
                    String[] parts = item.split("\n");
                    TextView text1 = view.findViewById(android.R.id.text1);
                    TextView text2 = view.findViewById(android.R.id.text2);
                    
                    if (parts.length > 0) text1.setText(parts[0]);
                    if (parts.length > 1) text2.setText(parts[1]);
                    if (parts.length > 2) {
                        text2.setText(parts[1] + " " + parts[2]);
                    }
                }
                return view;
            }
        };
        
        listView.setAdapter(deviceListAdapter);
        listView.setEmptyView(findViewById(R.id.empty_view));

        listView.setOnItemClickListener((parent, view, position, id) -> {
            String info = deviceListAdapter.getItem(position);
            if (info == null || !info.contains("\n")) return;
            connectToDevice(info.split("\n")[1]);
        });

        initBluetooth();
    }

    private void initBluetooth() {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            showErrorAndExit("设备不支持蓝牙");
            return;
        }

        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            showErrorAndExit("设备不支持低功耗蓝牙(BLE)");
            return;
        }

        try {
            bleScanner = bluetoothAdapter.getBluetoothLeScanner();
            if (bleScanner == null) {
                throw new NullPointerException("无法获取BLE扫描器");
            }
        } catch (Exception e) {
            showErrorAndExit("BLE功能初始化失败");
            return;
        }

        if (!bluetoothAdapter.isEnabled()) {
            startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_ENABLE_BT);
        } else {
            checkPermissionsAndStartScan();
        }
    }

    // 其他方法保持不变...
    private void checkPermissionsAndStartScan() {
        if (checkBlePermissions()) {
            startBleScan();
        } else {
            requestBlePermissions();
        }
    }

    private boolean checkBlePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                   checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                   checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestBlePermissions() {
        List<String> permissions = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!checkBlePermission(Manifest.permission.BLUETOOTH_SCAN)) {
                permissions.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (!checkBlePermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        }
        if (!checkBlePermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }

        if (!permissions.isEmpty()) {
            requestPermissions(permissions.toArray(new String[0]), PERMISSION_REQUEST_CODE);
        }
    }

    private boolean checkBlePermission(String permission) {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private void startBleScan() {
        try {
            deviceListAdapter.clear();
            discoveredDevices.clear();
            isScanning = true;

            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build();

            bleScanner.startScan(null, settings, scanCallback);
            updateScanState(true);
            handler.postDelayed(this::stopScan, SCAN_TIMEOUT);
            showToast("正在扫描BLE设备...");
        } catch (SecurityException e) {
            showToast("缺少必要的权限");
        } catch (Exception e) {
            showToast("扫描启动失败");
        }
    }

    private void stopScan() {
        if (isScanning && bleScanner != null) {
            try {
                bleScanner.stopScan(scanCallback);
            } catch (Exception e) {
                Log.e(TAG, "停止扫描异常: " + e.getMessage());
            }
            isScanning = false;
            updateScanState(false);
        }
    }

    private void processScanResult(ScanResult result) {
        runOnUiThread(() -> {
            BluetoothDevice device = result.getDevice();
            String address = device.getAddress();
            
            if (!isValidAddress(address) || discoveredDevices.contains(address)) return;

            String deviceName = device.getName() != null ? device.getName() : "Unknown";
            String rssi = "RSSI: " + result.getRssi() + " dBm";
            String services = parseServiceInfo(result);
            
            String displayText = String.format("%s\n%s\n%s | %s", 
                    deviceName, address, rssi, services);

            discoveredDevices.add(address);
            deviceListAdapter.add(displayText);
        });
    }

    private String parseServiceInfo(ScanResult result) {
        if (result.getScanRecord() == null) return "No Services";
        
        List<ParcelUuid> uuids = result.getScanRecord().getServiceUuids();
        if (uuids == null || uuids.isEmpty()) return "No Services";
        
        StringBuilder sb = new StringBuilder();
        for (ParcelUuid uuid : uuids) {
            sb.append(uuid.getUuid().toString().substring(0, 8)).append(" ");
        }
        return sb.toString().trim();
    }

    // 修改所有对 bluetoothGatt 的引用
    private void connectToDevice(String address) {
        if (!isValidAddress(address)) {
            showToast("无效的MAC地址");
            return;
        }

        BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
        sConnectedDeviceAddress = address;

        if (sBluetoothGatt != null) {
            sBluetoothGatt.disconnect();
            sBluetoothGatt.close();
        }

        sBluetoothGatt = device.connectGatt(this, false, gattCallback);
        updateStatus("正在连接: " + address);
    }

    private void closeGatt() {
        if (sBluetoothGatt != null) {
            try {
                sBluetoothGatt.disconnect();
                sBluetoothGatt.close();
            } catch (Exception e) {
                Log.e(TAG, "关闭连接异常: " + e.getMessage());
            }
            sBluetoothGatt = null;
        }
    }

    private void updateScanState(boolean scanning) {
        runOnUiThread(() -> {
            findViewById(R.id.progress_bar).setVisibility(scanning ? View.VISIBLE : View.GONE);
            ((TextView) findViewById(R.id.empty_view)).setText(
                scanning ? "正在扫描BLE设备..." : "点击列表项进行连接");
        });
    }

    private void updateStatus(String message) {
        runOnUiThread(() -> {
            TextView statusView = findViewById(R.id.empty_view);
            statusView.setText(message);
        });
    }

    private boolean isValidAddress(String address) {
        return address.matches("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$");
    }

    private void showToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void showErrorAndExit(String message) {
        new AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton("确定", (dialog, which) -> finish())
            .setCancelable(false)
            .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ENABLE_BT) {
            if (resultCode == RESULT_OK) {
                checkPermissionsAndStartScan();
            } else {
                showToast("需要启用蓝牙才能扫描");
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
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
                startBleScan();
            } else {
                showErrorAndExit("需要所有权限才能扫描设备");
            }
        }
    }

    public static void clearGatt() {
        if (sBluetoothGatt != null) {
            sBluetoothGatt.disconnect();
            sBluetoothGatt.close();
            sBluetoothGatt = null;
        }
        sConnectedDeviceAddress = null;
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopScan();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // closeGatt();
        handler.removeCallbacksAndMessages(null);
    }
}