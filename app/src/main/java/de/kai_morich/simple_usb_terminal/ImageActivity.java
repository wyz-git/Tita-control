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

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.nio.charset.StandardCharsets;
import android.content.SharedPreferences;
import android.content.Context;

public class ImageActivity extends AppCompatActivity {

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

    // MQTT配置
    private static final String MQTT_SERVER = "tcp://119.23.220.15:1883";
    private static String MQTT_TOPIC = "Virtual10";
    private MqttClient mqttClient;

    // 颜色常量
    private static final int COLOR_GREEN = 0xFF4CAF50;
    private static final int COLOR_BLUE = 0xFF2196F3;
    private static final int COLOR_ORANGE = 0xFFFF9800;
    private static final int COLOR_GRAY = 0xFF9E9E9E;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 全屏设置
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        setContentView(R.layout.activity_image);
        
        initPlayer();
    }

    private String getLoginAccount() {
        SharedPreferences sharedPreferences = getSharedPreferences("user_prefs", Context.MODE_PRIVATE);
        return sharedPreferences.getString("login_account", "default_account");
    }

    private String getMqttTopic() {
        String loginAccount = getLoginAccount();
        return loginAccount + "/virtual";
    }

    private void initPlayer() {
        playerViewModel = new ViewModelProvider(this).get(PlayerViewModel.class);
        playerView = findViewById(R.id.player_view);
        playerView.setPlayer(playerViewModel.getPlayer());
        
        // SRT流配置
        String srtUrl = "srt://119.23.220.15:8890?streamid=read:live";
        playerViewModel.setMediaItem(srtUrl);
    }


    @Override
    protected void onResume() {
        super.onResume();
        handler.post(dataUpdateRunnable);
        playerViewModel.getPlayer().play();
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(dataUpdateRunnable);
        // playerViewModel.getPlayer().pause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
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