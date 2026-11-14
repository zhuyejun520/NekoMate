package com.nekocyte.companion;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

public class LoginActivity extends BaseActivity {
    private EditText server;
    private EditText username;
    private EditText password;
    private TextView login;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);
        if (Session.loggedIn()) {
            startActivity(new Intent(this, MainActivity.class));
            finish();
            return;
        }
        server = findViewById(R.id.server);
        username = findViewById(R.id.username);
        password = findViewById(R.id.password);
        login = findViewById(R.id.login);
        server.setText(Session.server());
        if (getIntent() != null && getIntent().hasExtra("server")) {
            server.setText(getIntent().getStringExtra("server"));
        }
        login.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doLogin();
            }
        });
        findViewById(R.id.go_register).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Session.setServer(server.getText().toString());
                startActivity(new Intent(LoginActivity.this, RegisterActivity.class));
            }
        });
    }

    private void doLogin() {
        final String s = server.getText().toString().trim();
        final String u = username.getText().toString().trim();
        final String p = password.getText().toString();
        if (s.isEmpty()) {
            Ui.toast(this, "请填写服务器地址");
            return;
        }
        if (u.isEmpty() || p.isEmpty()) {
            Ui.toast(this, "请填写用户名和密码");
            return;
        }
        Session.setServer(s);
        login.setEnabled(false);
        login.setText("登录中…");
        Ui.hideKeyboard(this);
        Api.login(u, p, new Api.Cb() {
            @Override
            public void ok(org.json.JSONObject data) {
                String token = data.optString("token", "");
                org.json.JSONObject user = data.optJSONObject("user");
                if (token.isEmpty() || user == null) {
                    login.setEnabled(true);
                    login.setText("进入夜音");
                    Ui.toast(LoginActivity.this, "登录返回数据异常");
                    return;
                }
                Session.saveAuth(token, user);
                startActivity(new Intent(LoginActivity.this, MainActivity.class));
                finish();
            }

            @Override
            public void fail(String message) {
                login.setEnabled(true);
                login.setText("进入夜音");
                Ui.toast(LoginActivity.this, message);
            }
        });
    }
}
