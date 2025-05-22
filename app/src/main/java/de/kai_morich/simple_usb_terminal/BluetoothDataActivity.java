package de.kai_morich.simple_usb_terminal;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import android.content.Context;
import android.content.Intent;

import androidx.appcompat.app.AppCompatActivity;

import java.util.UUID;

public class BluetoothDataActivity extends AppCompatActivity {
    private static final String TAG = "BLE_Data";
    // 修改为 BlueZ 注册的 UUID
    private static final UUID SERVICE_UUID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"); 

    private static final UUID CHARACTERISTIC_UUID = UUID.fromString("bbbbbbbb-cccc-dddd-eeee-ffffffffffff");

    private Button joystickButton; 
    private TextView statusText;
    private EditText inputText;
    private Button sendButton;
    private Button disconnectButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        try {
            setContentView(R.layout.activity_bluetooth_data);
            
            // 初始化视图
            initViews();
            
            // 设置监听器
            setupListeners();
            
            // 显示连接状态
            updateConnectionStatus();

            printAllServices();

        } catch (Exception e) {
            Log.e(TAG, "Activity初始化失败", e);
            Toast.makeText(this, "界面加载失败", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private void printAllServices() {
        BluetoothGatt gatt = BluetoothDeviceListActivity.getBluetoothGatt();
        if (gatt != null) {
            Log.d(TAG, "===== 可用服务列表 =====");
            for (BluetoothGattService service : gatt.getServices()) {
                Log.d(TAG, "服务UUID: " + service.getUuid().toString());
                
                // 打印每个服务的特征值
                for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
                    Log.d(TAG, "  特征UUID: " + characteristic.getUuid());
                }
            }
        }
    }

    private void initViews() {
        statusText = findViewById(R.id.status_text);
        inputText = findViewById(R.id.input_text);
        sendButton = findViewById(R.id.send_button);
        disconnectButton = findViewById(R.id.disconnect_button);
        joystickButton = findViewById(R.id.joystick_button);
        if (statusText == null || inputText == null || 
            sendButton == null || disconnectButton == null ||
            joystickButton == null) { // 检查新按钮
            throw new RuntimeException("必要的视图未找到");
        }
    }

    private void setupListeners() {
        sendButton.setOnClickListener(v -> {
            String message = inputText.getText().toString();
            if (!message.isEmpty()) {
                sendData(message);
            }
        });
        
        disconnectButton.setOnClickListener(v -> {
            disconnectDevice();
            finish();
        });
        joystickButton.setOnClickListener(v -> {
            // 检查蓝牙连接状态
            if (BluetoothDeviceListActivity.getBluetoothGatt() == null) {
                Toast.makeText(this, "请先连接蓝牙设备", Toast.LENGTH_SHORT).show();
                return;
            }
            
            // 获取输入的SRT URL
            EditText srtUrlInput = findViewById(R.id.srt_url_input);
            String srtUrl = srtUrlInput.getText().toString().trim();
            
            // 如果没有输入则使用默认值
            if (srtUrl.isEmpty()) {
                srtUrl = "srt://";
            }
            
            // 跳转到摇杆控制页面并传递SRT URL
            Intent intent = new Intent(this, BluetoothJoystickActivity.class);
            intent.putExtra("SRT_URL", srtUrl);
            startActivity(intent);
        });
    }
    
    private void updateConnectionStatus() {
        String address = BluetoothDeviceListActivity.getConnectedDeviceAddress();
        if (address != null) {
            statusText.setText("已连接: " + address);
        } else {
            statusText.setText("未连接到设备");
        }
    }

    /**
     * 提供给外部调用的发送数据方法
     * @param context 上下文
     * @param message 要发送的消息
     * @return 是否发送成功
     */
    public static boolean sendBluetoothData(Context context, String message) {
        BluetoothGatt gatt = BluetoothDeviceListActivity.getBluetoothGatt();
        if (gatt == null) {
            Toast.makeText(context, "蓝牙连接不可用", Toast.LENGTH_SHORT).show();
            return false;
        }
        
        BluetoothGattService service = gatt.getService(SERVICE_UUID);
        if (service == null) {
            Toast.makeText(context, "找不到蓝牙服务", Toast.LENGTH_SHORT).show();
            return false;
        }
        
        BluetoothGattCharacteristic characteristic = service.getCharacteristic(CHARACTERISTIC_UUID);
        if (characteristic == null) {
            Toast.makeText(context, "找不到数据特征", Toast.LENGTH_SHORT).show();
            return false;
        }
        
        characteristic.setValue(message.getBytes());
        boolean success = gatt.writeCharacteristic(characteristic);
        if (success) {
            // Toast.makeText(context, "数据已发送", Toast.LENGTH_SHORT).show();
        } else {
            // Toast.makeText(context, "发送失败", Toast.LENGTH_SHORT).show();
        }
        return success;
    }

    private void sendData(String message) {
        BluetoothGatt gatt = BluetoothDeviceListActivity.getBluetoothGatt();
        if (gatt == null) {
            Toast.makeText(this, "蓝牙连接不可用", Toast.LENGTH_SHORT).show();
            return;
        }
        
        BluetoothGattService service = gatt.getService(SERVICE_UUID);
        if (service == null) {
            Toast.makeText(this, "找不到蓝牙服务", Toast.LENGTH_SHORT).show();
            return;
        }
        
        BluetoothGattCharacteristic characteristic = service.getCharacteristic(CHARACTERISTIC_UUID);
        if (characteristic == null) {
            Toast.makeText(this, "找不到数据特征", Toast.LENGTH_SHORT).show();
            return;
        }
        
        characteristic.setValue(message.getBytes());
        if (gatt.writeCharacteristic(characteristic)) {
            // Toast.makeText(this, "数据已发送", Toast.LENGTH_SHORT).show();
        } else {
            // Toast.makeText(this, "发送失败", Toast.LENGTH_SHORT).show();
        }
    }
    
    private void disconnectDevice() {
        BluetoothGatt gatt = BluetoothDeviceListActivity.getBluetoothGatt();
        if (gatt != null) {
            gatt.disconnect();
            gatt.close();
            BluetoothDeviceListActivity.clearGatt();
            Toast.makeText(this, "已断开连接", Toast.LENGTH_SHORT).show();
        }
    }
}