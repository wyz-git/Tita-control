package de.kai_morich.simple_usb_terminal;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import javax.crypto.spec.SecretKeySpec;
import javax.crypto.Cipher;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

public class LoginFragment extends Fragment {
    private OnLoginSuccessListener listener;
    private Button btnSkip;
    private Button btnLogin;
    private CheckBox cbRemember;
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean isVerifying = false;
    private SharedPreferences prefs;

    public interface OnLoginSuccessListener {
        void onLoginSuccess();
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_login, container, false);
        EditText etUsername = view.findViewById(R.id.et_username);
        EditText etPassword = view.findViewById(R.id.et_password);
        btnLogin = view.findViewById(R.id.btn_login);
        cbRemember = view.findViewById(R.id.cb_remember);
        // 初始化跳过按钮
        btnSkip = view.findViewById(R.id.btn_skip);

        prefs = requireActivity().getSharedPreferences("user_prefs", Context.MODE_PRIVATE);

        // 自动填充保存的凭证
        String savedUser = prefs.getString("login_account", "");
        String savedPass = prefs.getString("saved_password", "");
        boolean remember = prefs.getBoolean("remember_password", false);

        etUsername.setText(savedUser);
        if (remember && !savedPass.isEmpty()) {
            try {
                etPassword.setText(SimpleCrypto.decrypt(savedPass));
            } catch (Exception e) {
                etPassword.setText("");
                prefs.edit().remove("saved_password").apply();
            }
        }
        cbRemember.setChecked(remember);
        
        btnLogin.setOnClickListener(v -> {
            if (isVerifying) return;

            String username = etUsername.getText().toString().trim();
            String password = etPassword.getText().toString().trim();

            if (username.isEmpty() || password.isEmpty()) {
                showToast("请输入用户名和密码");
                return;
            }

            isVerifying = true;
            btnLogin.setEnabled(false);
            showToast("正在验证...");

            new Thread(() -> {
                try {
                    String serverUri = "tcp://119.23.220.15:1883";
                    
                    MqttConnectOptions options = new MqttConnectOptions();
                    options.setUserName(username);
                    options.setPassword(password.toCharArray());
                    options.setConnectionTimeout(10);
                    options.setAutomaticReconnect(true);

                    MqttManager.getInstance().connect(serverUri, options);
                    
                    mainHandler.post(() -> {
                        saveCredentials(username, password);
                        if (listener != null) {
                            listener.onLoginSuccess();
                        }
                    });
                } catch (MqttException e) {
                    handleLoginFailure(e);
                } finally {
                    isVerifying = false;
                    mainHandler.post(() -> btnLogin.setEnabled(true));
                }
            }).start();
        });

        // 跳过按钮点击事件
        btnSkip.setOnClickListener(v -> {
            if (listener != null) {
                // 直接触发登录成功回调，不进行任何连接操作
                SharedPreferences.Editor editor = prefs.edit();
                // 精准删除登录相关字段
                editor.remove("login_account")
                    .remove("saved_password")
                    .remove("remember_password")
                    .apply();
                
                // 可选：同时重置UI状态
                mainHandler.post(() -> {
                    etUsername.setText("");
                    etPassword.setText("");
                    cbRemember.setChecked(false);
                });
                listener.onLoginSuccess();
            }
        });
        return view;
    }

    private void saveCredentials(String username, String password) {
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString("login_account", username);
        
        if (cbRemember.isChecked()) {
            try {
                editor.putString("saved_password", SimpleCrypto.encrypt(password));
                editor.putBoolean("remember_password", true);
            } catch (Exception e) {
                e.printStackTrace();
                editor.remove("saved_password");
            }
        } else {
            editor.remove("saved_password")
                  .putBoolean("remember_password", false);
        }
        editor.apply();
    }

    private void handleLoginFailure(MqttException e) {
        mainHandler.post(() -> {
            String errorMessage = "验证失败";
            int reasonCode = e.getReasonCode();

            switch (reasonCode) {
                case MqttException.REASON_CODE_FAILED_AUTHENTICATION:
                    errorMessage = "账号或密码错误（代码 4）";
                    break;
                case MqttException.REASON_CODE_NOT_AUTHORIZED:
                    errorMessage = "没有访问权限（代码 5）";
                    break;
                case MqttException.REASON_CODE_CONNECTION_LOST:
                    errorMessage = "网络连接中断（代码 6）";
                    break;
                case MqttException.REASON_CODE_SERVER_CONNECT_ERROR:
                    errorMessage = "无法连接服务器（代码 1）";
                    break;
                case MqttException.REASON_CODE_CLIENT_TIMEOUT:
                    errorMessage = "连接超时（代码 32000）";
                    break;
                default:
                    if (e.getMessage().contains("Connection refused")) {
                        errorMessage = "服务器拒绝连接";
                    }
                    break;
            }

            Toast.makeText(getActivity(), 
                errorMessage + "\n错误详情: " + e.getMessage(), 
                Toast.LENGTH_LONG).show();
        });
    }

    private void showToast(String text) {
        mainHandler.post(() -> 
            Toast.makeText(getActivity(), text, Toast.LENGTH_SHORT).show());
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isVerifying = false;
    }

    public void setOnLoginSuccessListener(OnLoginSuccessListener listener) {
        this.listener = listener;
    }

    // 加密工具类
    public static class SimpleCrypto {
        private static final String ALGORITHM = "AES";
        private static final byte[] KEY = "MySuperSecretKey".getBytes();

        public static String encrypt(String value) throws Exception {
            SecretKeySpec key = new SecretKeySpec(KEY, ALGORITHM);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key);
            return android.util.Base64.encodeToString(cipher.doFinal(value.getBytes()), android.util.Base64.DEFAULT);
        }

        public static String decrypt(String value) throws Exception {
            SecretKeySpec key = new SecretKeySpec(KEY, ALGORITHM);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key);
            return new String(cipher.doFinal(android.util.Base64.decode(value, android.util.Base64.DEFAULT)));
        }
    }
}