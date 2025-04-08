package de.kai_morich.simple_usb_terminal;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.bluetooth.BluetoothProfile;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class BluetoothConnectionActivity extends AppCompatActivity {

    private static final String TAG = "BT_L2CAP";
    private static final int L2CAP_PSM = 0x1001;
    private static final int CONNECTION_TIMEOUT = 15000;
    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private BluetoothSocket mmSocket;
    private BluetoothDevice mmDevice;
    private ProgressBar progressBar;
    private TextView statusText;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bluetooth_connection);

        initializeUI();
        checkAndRequestPermissions();
    }

    private void initializeUI() {
        progressBar = findViewById(R.id.progress_bar);
        statusText = findViewById(R.id.status_text);
        progressBar.setVisibility(View.VISIBLE);
    }

    private void checkAndRequestPermissions() {
        List<String> neededPermissions = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                neededPermissions.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                neededPermissions.add(Manifest.permission.BLUETOOTH_SCAN);
            }
        }

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            neededPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }

        if (!neededPermissions.isEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                neededPermissions.toArray(new String[0]),
                PERMISSION_REQUEST_CODE
            );
        } else {
            initializeConnection();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    showErrorAndFinish("需要所有权限才能连接");
                    return;
                }
            }
            initializeConnection();
        }
    }

    private void initializeConnection() {
        String deviceAddress = getIntent().getStringExtra("DEVICE_ADDRESS");
        if (deviceAddress == null || deviceAddress.isEmpty()) {
            showErrorAndFinish("无效的设备地址");
            return;
        }

        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) {
                showErrorAndFinish("设备不支持蓝牙");
                return;
            }
            
            mmDevice = adapter.getRemoteDevice(deviceAddress);
            statusText.setText("正在连接: " + getDeviceName());
            startConnection();
            
            handler.postDelayed(this::checkConnectionTimeout, CONNECTION_TIMEOUT);
        } catch (IllegalArgumentException e) {
            showErrorAndFinish("无效的蓝牙地址格式");
        }
    }

    private void startConnection() {
        new Thread(new ConnectThread()).start();
    }

    private class ConnectThread implements Runnable {
        @Override
        public void run() {
            try {
                createSocketConnection();
                runOnUiThread(() -> {
                    handler.removeCallbacksAndMessages(null);
                    progressBar.setVisibility(View.GONE);
                    statusText.setText("连接成功");
                    Toast.makeText(BluetoothConnectionActivity.this, 
                        "已连接至 " + getDeviceName(), Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                Log.e(TAG, "连接失败", e);
                showErrorAndFinish(getErrorMessage(e));
            } finally {
                closeSocket();
            }
        }

        private void createSocketConnection() throws IOException {
            BluetoothAdapter.getDefaultAdapter().cancelDiscovery();

            mmSocket = mmDevice.createL2capChannel(L2CAP_PSM);
            mmSocket.connect();
        }

        private BluetoothSocket createLegacyL2capSocket() throws Exception {
            try {
                Method method = mmDevice.getClass().getMethod("createL2capSocket", int.class);
                return (BluetoothSocket) method.invoke(mmDevice, L2CAP_PSM);
            } catch (NoSuchMethodException e) {
                throw new IOException("设备不兼容L2CAP协议");
            }
        }

        private String getErrorMessage(Exception e) {
            if (e instanceof IOException) {
                return "连接失败: " + e.getMessage();
            }
            return "设备不兼容: " + e.getClass().getSimpleName();
        }
    }

    private String getDeviceName() {
        try {
            return (mmDevice != null) ? mmDevice.getName() : "未知设备";
        } catch (SecurityException e) {
            return "需要权限查看设备名称";
        }
    }

    private void checkConnectionTimeout() {
        if (mmSocket == null || !mmSocket.isConnected()) {
            showErrorAndFinish("连接超时");
        }
    }

    private void closeSocket() {
        try {
            if (mmSocket != null) {
                mmSocket.close();
            }
        } catch (IOException e) {
            Log.w(TAG, "关闭Socket时出错", e);
        }
    }

    private void showErrorAndFinish(String message) {
        runOnUiThread(() -> {
            progressBar.setVisibility(View.GONE);
            statusText.setText(message);
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            handler.postDelayed(this::finish, 2000);
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        closeSocket();
    }
}