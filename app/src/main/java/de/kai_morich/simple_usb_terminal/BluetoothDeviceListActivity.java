package de.kai_morich.simple_usb_terminal;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.util.HashSet;
import java.util.Set;

public class BluetoothDeviceListActivity extends AppCompatActivity {
    private static final String TAG = "BluetoothDebug";
    private static final int REQUEST_ENABLE_BT = 1;
    private static final int PERMISSION_REQUEST_CODE = 2;
    private static final long SCAN_TIMEOUT = 120000;

    private BluetoothAdapter bluetoothAdapter;
    private ArrayAdapter<String> deviceListAdapter;
    private boolean isReceiverRegistered = false;
    private final Handler scanHandler = new Handler(Looper.getMainLooper());
    private final Set<String> discoveredDevices = new HashSet<>();

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Log.d(TAG, "收到广播: " + action);
            
            if (action == null) return;
            
            switch (action) {
                case BluetoothAdapter.ACTION_DISCOVERY_STARTED:
                    handleDiscoveryStarted();
                    break;
                case BluetoothDevice.ACTION_FOUND:
                    handleDeviceFound(intent);
                    break;
                case BluetoothAdapter.ACTION_DISCOVERY_FINISHED:
                    handleScanComplete();
                    break;
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bluetooth_list);

        ListView listView = findViewById(R.id.device_list);
        deviceListAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1);
        listView.setAdapter(deviceListAdapter);
        listView.setEmptyView(findViewById(R.id.empty_view));

        listView.setOnItemClickListener((parent, view, position, id) -> {
            String info = deviceListAdapter.getItem(position);
            if (info == null || !info.contains("\n")) {
                showToast("无效设备信息");
                return;
            }
            connectToDevice(info.split("\n")[1]);
        });

        initBluetoothAdapter();
    }

    private void initBluetoothAdapter() {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            showErrorAndExit("设备不支持蓝牙");
            return;
        }
        checkBluetoothState();
    }

    private void checkBluetoothState() {
        if (!bluetoothAdapter.isEnabled()) {
            startActivityForResult(
                new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),
                REQUEST_ENABLE_BT
            );
        } else {
            checkPermissionsAndStartDiscovery();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ENABLE_BT) {
            if (resultCode == RESULT_OK) {
                checkPermissionsAndStartDiscovery();
            } else {
                showErrorAndExit("需要启用蓝牙功能");
            }
        }
    }

    private void checkPermissionsAndStartDiscovery() {
        if (checkRequiredPermissions()) {
            startDeviceDiscovery();
        } else {
            requestPermissions(getRequiredPermissions(), PERMISSION_REQUEST_CODE);
        }
    }

    private boolean checkRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        } else {
            return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }
    }

    private String[] getRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION
            };
        } else {
            return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startDeviceDiscovery();
            } else {
                showErrorAndExit("需要权限才能扫描设备");
            }
        }
    }

    private void startDeviceDiscovery() {
        discoveredDevices.clear();
        deviceListAdapter.clear();
        addPairedDevices();

        if (bluetoothAdapter.isDiscovering()) {
            bluetoothAdapter.cancelDiscovery();
        }

        if (!bluetoothAdapter.startDiscovery()) {
            showToast("扫描启动失败");
            return;
        }

        registerBluetoothReceiver();
        setupScanTimeout();
        showToast("正在扫描...");
    }

    private void addPairedDevices() {
        for (BluetoothDevice device : bluetoothAdapter.getBondedDevices()) {
            addDeviceEntry(device);
        }
    }

    private void handleDeviceFound(Intent intent) {
        BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        if (device == null || device.getAddress() == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            if (device.getType() == BluetoothDevice.DEVICE_TYPE_LE) {
                return;
            }
        }

        addDeviceEntry(device);
    }

    private void addDeviceEntry(BluetoothDevice device) {
        String address = device.getAddress();
        if (!isValidAddress(address) || discoveredDevices.contains(address)) return;

        String name = device.getName() != null ? device.getName() : "Unknown Device";
        discoveredDevices.add(address);
        deviceListAdapter.add(name + "\n" + address);
    }

    private void handleDiscoveryStarted() {
        runOnUiThread(() -> {
            findViewById(R.id.progress_bar).setVisibility(View.VISIBLE);
            ((TextView) findViewById(R.id.empty_view)).setText("正在扫描设备...");
        });
    }

    private void handleScanComplete() {
        runOnUiThread(() -> {
            findViewById(R.id.progress_bar).setVisibility(View.GONE);
            showToast(deviceListAdapter.isEmpty() ? "未发现设备" : "发现" + deviceListAdapter.getCount() + "个设备");
        });
    }

    private void registerBluetoothReceiver() {
        if (!isReceiverRegistered) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(BluetoothDevice.ACTION_FOUND);
            filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
            filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
            registerReceiver(receiver, filter);
            isReceiverRegistered = true;
        }
    }

    private void unregisterBluetoothReceiver() {
        if (isReceiverRegistered) {
            unregisterReceiver(receiver);
            isReceiverRegistered = false;
        }
    }

    private void setupScanTimeout() {
        scanHandler.postDelayed(() -> {
            if (bluetoothAdapter.isDiscovering()) {
                cancelDiscovery();
                showToast("扫描超时");
            }
        }, SCAN_TIMEOUT);
    }

    private void cancelDiscovery() {
        if (bluetoothAdapter.isDiscovering()) {
            bluetoothAdapter.cancelDiscovery();
        }
    }

    private void connectToDevice(String address) {
        try {
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
            showToast("正在连接: " + address);
            finish();
        } catch (IllegalArgumentException e) {
            showToast("无效的蓝牙地址");
        }
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
    protected void onResume() {
        super.onResume();
        registerBluetoothReceiver();
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterBluetoothReceiver();
        cancelDiscovery();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        scanHandler.removeCallbacksAndMessages(null);
        cancelDiscovery();
    }
}