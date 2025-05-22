package de.kai_morich.simple_usb_terminal;

import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.content.pm.ActivityInfo;
import android.util.Log;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.ui.PlayerView;

import android.net.Uri;
import android.content.SharedPreferences;
import android.content.Context;
import android.widget.Toast;

public class BluetoothJoystickActivity extends AppCompatActivity {

    // 控件声明
    private JoystickView leftJoystick;
    private JoystickView rightJoystick;
    private Button btnSwitch1, btnSwitch2, btnSwitch3, btnSwitch4;
    
    // 状态变量
    private boolean btn1State = false;
    private int btn2State = 2;
    private boolean btn3State = false;
    private int btn4State = 0;
    
    private Handler handler = new Handler();
    private Runnable dataUpdateRunnable;

    // 播放器相关
    private PlayerView playerView;
    private PlayerViewModel playerViewModel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 全屏设置
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        setContentView(R.layout.activity_fullscreen_hello);
        
        // 初始化控件
        initViews();
        
        // 播放器初始化
        initPlayer();

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
    }

    private void initPlayer() {
        playerViewModel = new ViewModelProvider(this).get(PlayerViewModel.class);
        playerView = findViewById(R.id.player_view);
        playerView.setPlayer(playerViewModel.getPlayer());
    
        // 从Intent中获取SRT URL，如果没有则使用默认值
        String srtUrl = getIntent().getStringExtra("SRT_URL");
        if (srtUrl == null || srtUrl.isEmpty()) {
            srtUrl = "srt://";
        }
        Uri srtUri = Uri.parse(srtUrl);
        playerViewModel.playStream(srtUri);
    }

    private void setupButtonListeners() {
        // 左侧按钮组
        btnSwitch1.setOnClickListener(v -> toggleButtonState(btnSwitch1, true));
        btnSwitch2.setOnClickListener(v -> cycleButtonState(btnSwitch2, true));
        
        // 右侧按钮组
        btnSwitch3.setOnClickListener(v -> toggleButtonState(btnSwitch3, false));
        btnSwitch4.setOnClickListener(v -> cycleButtonState(btnSwitch4, false));
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
        playerViewModel.getPlayer().play();
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
        playerViewModel.getPlayer().pause();
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