package com.nekocyte.companion;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

public class RegisterActivity extends BaseActivity {
    private EditText username;
    private EditText nickname;
    private EditText password;
    private TextView register;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_register);
        username = findViewById(R.id.username);
        nickname = findViewById(R.id.nickname);
        password = findViewById(R.id.password);
        register = findViewById(R.id.register);
        register.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doRegister();
            }
        });
        findViewById(R.id.go_login).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
    }

    private void doRegister() {
        final String u = username.getText().toString().trim();
        final String n = nickname.getText().toString().trim();
        final String p = password.getText().toString();
        if (!u.matches("[A-Za-z0-9_]{3,32}")) {
            Ui.toast(this, "用户名需要 3-32 位字母/数字/下划线");
            return;
        }
        if (p.length() < 6) {
            Ui.toast(this, "密码至少 6 位");
            return;
        }
        register.setEnabled(false);
        register.setText("创建中…");
        Ui.hideKeyboard(this);
        Api.register(u, p, n.isEmpty() ? u : n, new Api.Cb() {
            @Override
            public void ok(org.json.JSONObject data) {
                String token = data.optString("token", "");
                org.json.JSONObject user = data.optJSONObject("user");
                if (token.isEmpty() || user == null) {
                    register.setEnabled(true);
                    register.setText("注册并开启");
                    Ui.toast(RegisterActivity.this, "注册返回数据异常");
                    return;
                }
                Session.saveAuth(token, user);
                startActivity(new Intent(RegisterActivity.this, MainActivity.class));
                finish();
            }

            @Override
            public void fail(String message) {
                register.setEnabled(true);
                register.setText("注册并开启");
                Ui.toast(RegisterActivity.this, message);
            }
        });
    }
}
