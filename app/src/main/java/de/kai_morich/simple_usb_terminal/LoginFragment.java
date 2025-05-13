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
import android.widget.EditText;
import android.widget.Toast;

import androidx.fragment.app.Fragment;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

public class LoginFragment extends Fragment {
    private OnLoginSuccessListener listener;
    private Button btnLogin;
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean isVerifying = false;

    public interface OnLoginSuccessListener {
        void onLoginSuccess();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_login, container, false);
        EditText etUsername = view.findViewById(R.id.et_username);
        EditText etPassword = view.findViewById(R.id.et_password);
        btnLogin = view.findViewById(R.id.btn_login);

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
                    options.setAutomaticReconnect(true); // 启用自动重连
                    
                    // 使用单例管理器连接
                    MqttManager.getInstance().connect(serverUri, options);
                    
                    mainHandler.post(() -> {
                        SharedPreferences prefs = requireActivity().getSharedPreferences("user_prefs", Context.MODE_PRIVATE);
                        prefs.edit().putString("login_account", username).apply();
                        if (listener != null) {
                            listener.onLoginSuccess(); // 通知登录成功
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

        return view;
    }

    private void handleLoginSuccess(String username) {
        mainHandler.post(() -> {
            SharedPreferences prefs = requireActivity().getSharedPreferences("user_prefs", Context.MODE_PRIVATE);
            prefs.edit().putString("login_account", username).apply();
            if (listener != null) {
                listener.onLoginSuccess();
            }
            btnLogin.setEnabled(true);
        });
    }

    private void handleLoginFailure(MqttException e) {
        mainHandler.post(() -> {
            String errorMessage = "验证失败";
            int reasonCode = e.getReasonCode();

            // 根据MQTT协议返回码判断错误类型
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
            
            btnLogin.setEnabled(true);
        });
    }

    private void showToast(String text) {
        mainHandler.post(() -> 
            Toast.makeText(getActivity(), text, Toast.LENGTH_SHORT).show());
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // 取消可能正在进行的验证
        isVerifying = false;
    }

    public void setOnLoginSuccessListener(OnLoginSuccessListener listener) {
        this.listener = listener;
    }
}