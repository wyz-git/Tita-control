package de.kai_morich.simple_usb_terminal;
 
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.content.pm.ActivityInfo;
import android.util.Log;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
 
import android.content.SharedPreferences;
import android.content.Context;
import android.widget.Toast;
import android.widget.EditText;
import java.util.*; // 导入 java.util 下所有类
import android.widget.ProgressBar;  // 添加这一行
// 在现有导入区域添加
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference; // 如果使用AtomicReference也需要这个
// 在现有导入区域添加
import java.net.NetworkInterface;
import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.SocketException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.io.IOException;
 
public class BluetoothJoystickActivity extends AppCompatActivity {
 
    // 控件声明
    private JoystickView leftJoystick;
    private JoystickView rightJoystick;
    private Button btnSwitch1, btnSwitch2, btnSwitch3, btnSwitch4;
    private WebView webView;
    private EditText urlEditText;
 
    // 状态变量
    private boolean btn1State = false;
    private int btn2State = 2;
    private boolean btn3State = false;
    private int btn4State = 0;
    
    private Handler handler = new Handler();
    private Runnable dataUpdateRunnable;
 
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
 
        // 全屏设置
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
 
        setContentView(R.layout.bluetooth_joystick_activity);
        
        // 初始化控件
        initViews();
        
        // 初始化WebView
        initWebView();
 
        setupButtonListeners();
        initDataLogger();
    }
 
    private void initViews() {
        leftJoystick = findViewById(R.id.left_joystick);
        rightJoystick = findViewById(R.id.right_joystick);
        btnSwitch1 = findViewById(R.id.btn_switch1);
        btnSwitch2 = findViewById(R.id.btn_switch2);
        btnSwitch3 = findViewById(R.id.btn_switch3);
        btnSwitch4 = findViewById(R.id.btn_switch4);
        webView = findViewById(R.id.web_view);
        urlEditText = findViewById(R.id.url_edit_text);
    }
 
    // private void initWebView() {
    //     // 启用JavaScript（如果需要）
    //     webView.getSettings().setJavaScriptEnabled(true);
 
    //     // 设置WebViewClient以处理页面加载
    //     webView.setWebViewClient(new WebViewClient());
 
    //     // 从Intent中获取初始URL，如果没有则使用默认值
    //     String initialUrl = getIntent().getStringExtra("INITIAL_URL");
    //     if (initialUrl == null || initialUrl.isEmpty()) {
    //         initialUrl = "https://www.example.com";
    //     }
 
    //     // 加载初始URL
    //     webView.loadUrl(initialUrl);
    // }
 

    private void initWebView() {
        // 显示扫描进度提示
        ProgressBar progressBar = findViewById(R.id.progressBar);
        progressBar.setVisibility(View.VISIBLE);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.setWebViewClient(new WebViewClient());
        // 在后台线程执行网络扫描
        new Thread(() -> {
            Log.d("NetworkScan", "开始网络扫描...");
            String targetIP = scanNetwork(8889);
            Log.d("NetworkScan", "网络扫描完成。目标IP: " + (targetIP != null ? targetIP : "null"));

            runOnUiThread(() -> {
                progressBar.setVisibility(View.GONE);
                if (targetIP != null) {
                    Log.d("NetworkScan", "在IP地址找到设备: " + targetIP);
                    webView.loadUrl("http://" + targetIP + ":8889/tita");
                } else {
                    // 处理未找到设备的情况
                    Log.e("NetworkScan", "未找到目标设备");
                    Toast.makeText(BluetoothJoystickActivity.this, "未找到目标设备", Toast.LENGTH_SHORT).show();
                    webView.loadUrl("about:blank");
                }
            });
        }).start();
    }

    private String scanNetwork(int targetPort) {
        // 获取本机IP地址
        String localIP = getLocalIPAddress();
        if (localIP == null) return null;

        // 生成待扫描的IP列表（示例：192.168.1.1 - 192.168.1.254）
        List<String> ipList = generateIPRange(localIP);

        // 使用多线程加速扫描
        ExecutorService executor = Executors.newFixedThreadPool(20);
        AtomicReference<String> foundIP = new AtomicReference<>();

        for (String ip : ipList) {
            executor.execute(() -> {
                if (foundIP.get() == null && isPortOpen(ip, targetPort)) {
                    foundIP.set(ip);
                    executor.shutdownNow(); // 发现后立即停止扫描
                }
            });
        }

        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return foundIP.get();
    }

    // 获取本机IPv4地址
    private String getLocalIPAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (networkInterface.isLoopback() || !networkInterface.isUp()) continue;

                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (SocketException e) {
            e.printStackTrace();
        }
        return null;
    }

    // 生成IP地址范围（简化版，假设子网掩码为24位）
    private List<String> generateIPRange(String localIP) {
        List<String> ipList = new ArrayList<>();
        String[] octets = localIP.split("\\.");
        if (octets.length != 4) return ipList;

        // 生成最后一位从1到254的IP地址
        for (int i = 1; i <= 254; i++) {
            ipList.add(octets[0] + "." + octets[1] + "." + octets[2] + "." + i);
        }
        return ipList;
    }

    // 检测指定端口是否开放
    private boolean isPortOpen(String ip, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(ip, port), 300); // 300ms超时
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void setupButtonListeners() {
        // 左侧按钮组
        btnSwitch1.setOnClickListener(v -> toggleButtonState(btnSwitch1, true));
        btnSwitch2.setOnClickListener(v -> cycleButtonState(btnSwitch2, true));
        
        // 右侧按钮组
        btnSwitch3.setOnClickListener(v -> toggleButtonState(btnSwitch3, false));
        btnSwitch4.setOnClickListener(v -> cycleButtonState(btnSwitch4, false));
 
        // 设置一个按钮用于加载URL
        Button loadUrlButton = findViewById(R.id.btn_load_url);
        loadUrlButton.setOnClickListener(v -> {
            String url = urlEditText.getText().toString();
            if (!url.isEmpty()) {
                webView.loadUrl(url);
            } else {
                Toast.makeText(BluetoothJoystickActivity.this, "Please enter a URL", Toast.LENGTH_SHORT).show();
            }
        });
    }

    // 二态按钮切换
    private void toggleButtonState(Button btn, boolean isLeftGroup) {
        if (btn == btnSwitch1) {
            btn1State = !btn1State;
            updateButtonAppearance(btn, btn1State);
        } else if (btn == btnSwitch3) {
            btn3State = !btn3State;
            updateButtonAppearance(btn, btn3State);
        }
        sendControlData(); // 按钮状态变化时立即发送数据
    }

    // 三态按钮循环
    private void cycleButtonState(Button btn, boolean isLeftGroup) {
        if (btn == btnSwitch2) {
            btn2State = (btn2State + 1) % 3;
            updateButtonAppearance(btn, btn2State);
        } else if (btn == btnSwitch4) {
            btn4State = (btn4State + 1) % 3;
            updateButtonAppearance(btn, btn4State);
        }
        sendControlData(); // 按钮状态变化时立即发送数据
    }

    private void updateButtonAppearance(Button btn, Object state) {
        if (btn == btnSwitch2 || btn == btnSwitch4) {
            // 处理三态按钮
            int value = (int) state;
            btn.setText(String.valueOf(value));
            int color;
            switch (value) {
                case 1: color = 0xFF2196F3; break;
                case 2: color = 0xFFFF9800; break;
                default: color = 0xFF9E9E9E; break;
            }
            btn.setBackgroundColor(color);
        } else {
            // 处理二态按钮
            boolean boolState = (boolean) state;
            btn.setText(boolState ? "1" : "0");
            btn.setBackgroundColor(boolState ? 0xFF4CAF50 : 0xFF9E9E9E);
        }
    }

    private void initDataLogger() {
        dataUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                sendControlData();
                handler.postDelayed(this, 50); // 50ms间隔
            }
        };
    }

    private void sendControlData() {
        // 检查蓝牙连接状态
        if (BluetoothDeviceListActivity.getBluetoothGatt() == null) {
            Log.d("Bluetooth", "No active Bluetooth connection");
            return;
        }

        // 构建控制指令
        String command = String.format("CTRL:%.2f,%.2f,%.2f,%.2f,%d,%d,%d,%d",
            leftJoystick.getNormalizedX(),
            leftJoystick.getNormalizedY(),
            rightJoystick.getNormalizedX(),
            rightJoystick.getNormalizedY(),
            btn1State ? 1 : 0,
            btn2State,
            btn3State ? 1 : 0,
            btn4State
        );

        // 通过蓝牙发送指令
        boolean success = BluetoothDataActivity.sendBluetoothData(this, command);
        if (!success) {
            Log.e("Bluetooth", "Failed to send control data");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(dataUpdateRunnable);
        // 恢复按钮状态
        updateButtonAppearance(btnSwitch1, btn1State);
        updateButtonAppearance(btnSwitch2, btn2State);
        updateButtonAppearance(btnSwitch3, btn3State);
        updateButtonAppearance(btnSwitch4, btn4State);
    }
 
    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(dataUpdateRunnable);
    }
 
    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
    }
 
    // 全屏控制
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUI();
        }
    }
 
    private void hideSystemUI() {
        View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN);
    }
}