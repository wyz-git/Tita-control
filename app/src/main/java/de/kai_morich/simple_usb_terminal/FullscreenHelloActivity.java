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

public class FullscreenHelloActivity extends AppCompatActivity {

    private JoystickView leftJoystick;
    private JoystickView rightJoystick;
    private Button btnSwitch1;
    private Button btnSwitch2;
    private boolean btn1State = false;
    private boolean btn2State = false;
    private Handler handler = new Handler();
    private Runnable dataUpdateRunnable;

    // Player and ViewModel
    private PlayerView playerView;
    private PlayerViewModel playerViewModel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        setContentView(R.layout.activity_fullscreen_hello);

        // 初始化控件
        leftJoystick = findViewById(R.id.left_joystick);
        rightJoystick = findViewById(R.id.right_joystick);
        btnSwitch1 = findViewById(R.id.btn_switch1);
        btnSwitch2 = findViewById(R.id.btn_switch2);

        // 初始化 ViewModel
        playerViewModel = new ViewModelProvider(this).get(PlayerViewModel.class);

        // 初始化播放器
        playerView = findViewById(R.id.player_view);
        playerView.setPlayer(playerViewModel.getPlayer());

        // 设置 SRT 流
        String srtUrl = "srt://119.23.220.15:8890?streamid=read:live";
        playerViewModel.setMediaItem(srtUrl);

        setupButtonListeners();
        initDataLogger();
    }

    private void setupButtonListeners() {
        btnSwitch1.setOnClickListener(v -> {
            btn1State = !btn1State;
            updateButtonAppearance(btnSwitch1, btn1State);
        });

        btnSwitch2.setOnClickListener(v -> {
            btn2State = !btn2State;
            updateButtonAppearance(btnSwitch2, btn2State);
        });
    }

    private void initDataLogger() {
        dataUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                logControlData();
                handler.postDelayed(this, 50);
            }
        };
    }

    private void logControlData() {
        float leftX = leftJoystick.getNormalizedX();
        float leftY = leftJoystick.getNormalizedY();
        float rightX = rightJoystick.getNormalizedX();
        float rightY = rightJoystick.getNormalizedY();

        // Log.d("ControlData", String.format(
        //     "Joys: L[%.2f,%.2f] R[%.2f,%.2f] | BTN: [%d,%d]",
        //     leftX, leftY, rightX, rightY,
        //     btn1State ? 1 : 0, btn2State ? 1 : 0
        // ));
    }

    private void updateButtonAppearance(Button btn, boolean state) {
        btn.setText(state ? "1" : "0");
        btn.setBackgroundColor(state ? 0xFF4CAF50 : 0xFF9E9E9E);
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
        playerViewModel.getPlayer().pause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(dataUpdateRunnable);
    }

    // Fullscreen control
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