package de.kai_morich.simple_usb_terminal;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import androidx.fragment.app.Fragment;
import android.content.SharedPreferences;
import android.content.Context;

public class LoginFragment extends Fragment {
    private OnLoginSuccessListener listener;

    public interface OnLoginSuccessListener {
        void onLoginSuccess();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_login, container, false);
        EditText etUsername = view.findViewById(R.id.et_username);
        EditText etPassword = view.findViewById(R.id.et_password);
        Button btnLogin = view.findViewById(R.id.btn_login);

        btnLogin.setOnClickListener(v -> {
            String username = etUsername.getText().toString().trim();
            String password = etPassword.getText().toString().trim();

            // 验证账号密码
            boolean isValid = 
                (username.equals("tita3037207") && password.equals("tita3037207")) ||
                (username.equals("tita3037208") && password.equals("tita3037208"));

            if (isValid) {
                // 存储登录账号
                SharedPreferences sharedPreferences = getActivity().getSharedPreferences("user_prefs", Context.MODE_PRIVATE);
                SharedPreferences.Editor editor = sharedPreferences.edit();
                editor.putString("login_account", username);
                editor.apply();

                if (listener != null) {
                    listener.onLoginSuccess();
                }
            } else {
                Toast.makeText(getActivity(), "账号或密码错误", Toast.LENGTH_SHORT).show();
            }
        });

        return view;
    }

    public void setOnLoginSuccessListener(OnLoginSuccessListener listener) {
        this.listener = listener;
    }
}