package de.kai_morich.simple_usb_terminal;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

public class MainActivity extends AppCompatActivity implements 
        FragmentManager.OnBackStackChangedListener,
        LoginFragment.OnLoginSuccessListener {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 初始化 Toolbar
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportFragmentManager().addOnBackStackChangedListener(this);

        // 每次启动都强制显示登录界面
        if (savedInstanceState == null) {
            showLoginFragment();
        }
    }

    // 显示登录 Fragment
    private void showLoginFragment() {
        LoginFragment loginFragment = new LoginFragment();
        loginFragment.setOnLoginSuccessListener(this);
        replaceFragment(loginFragment, "login");
    }

    // 显示设备列表 Fragment
    private void showDevicesFragment() {
        DevicesFragment devicesFragment = new DevicesFragment();
        replaceFragment(devicesFragment, "devices");
    }

    // 替换 Fragment 的通用方法
    private void replaceFragment(Fragment fragment, String tag) {
        FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
        transaction.replace(R.id.fragment, fragment, tag);
        if (!tag.equals("login")) {
            transaction.addToBackStack(null); // 允许返回导航
        }
        transaction.commit();
    }

    // 登录成功回调
    @Override
    public void onLoginSuccess() {
        showDevicesFragment();
        Toast.makeText(this, "登录成功", Toast.LENGTH_SHORT).show();
    }

    // 返回栈变化监听
    @Override
    public void onBackStackChanged() {
        boolean hasBackStack = getSupportFragmentManager().getBackStackEntryCount() > 0;
        getSupportActionBar().setDisplayHomeAsUpEnabled(hasBackStack);
    }

    // 处理导航返回按钮
    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    // 重写返回键逻辑
    @Override
    public void onBackPressed() {
        Fragment currentFragment = getSupportFragmentManager().findFragmentById(R.id.fragment);
        
        if (currentFragment instanceof DevicesFragment) {
            // 从设备列表返回登录界面
            showLoginFragment();
        } else {
            super.onBackPressed();
        }
    }

    // USB 设备检测（原功能保留）
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if ("android.hardware.usb.action.USB_DEVICE_ATTACHED".equals(intent.getAction())) {
            TerminalFragment terminal = (TerminalFragment) getSupportFragmentManager().findFragmentByTag("terminal");
            if (terminal != null) {
                terminal.status("USB device detected");
            }
        }
    }
}