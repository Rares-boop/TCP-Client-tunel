package com.example.tcpclient;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.snackbar.Snackbar;
import com.google.gson.Gson;
import com.google.gson.JsonElement;

import chat.ChatDtos;
import chat.NetworkPacket;
import chat.PacketType;
import chat.User;

public class RegisterActivity extends AppCompatActivity {
    private ConfigReader config;
    private SharedPreferences preferences;
    private final Gson gson = new Gson();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_register);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        config = new ConfigReader(this);

        // FALLBACK: Daca SecureStorage nu merge, folosim normal
        try {
            preferences = SecureStorage.getEncryptedPrefs(getApplicationContext());
        } catch (Exception e) {
            preferences = getSharedPreferences("ChatPrefs", MODE_PRIVATE);
        }
    }

    public void handleAccount(View view) {
        EditText usernameField = findViewById(R.id.editTextText);
        EditText passwordField = findViewById(R.id.editTextTextPassword2);
        EditText confirmedPasswordField = findViewById(R.id.editTextTextPassword3);
        CheckBox checkBox = findViewById(R.id.checkBoxKeepSignedInRegister);

        String username = usernameField.getText().toString().trim();
        String password = passwordField.getText().toString().trim();
        String confirmedPassword = confirmedPasswordField.getText().toString().trim();

        if (username.isEmpty() || password.isEmpty() || confirmedPassword.isEmpty()) {
            Toast.makeText(this, "Completeaza toate campurile!", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!password.equals(confirmedPassword)) {
            Toast.makeText(this, "Parolele nu coincid!", Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(this, "Inregistrare...", Toast.LENGTH_SHORT).show();
        view.setEnabled(false); // Dezactivam butonul

        new Thread(() -> {
            try {
                // 1. CURATENIE (Important!)
                TcpConnection.close();

                // 2. CONECTARE + HANDSHAKE (Folosind ConfigReader pt Ngrok)
                TcpConnection.connect(config.getServerIp(), config.getServerPort());

                // 3. PREGATIM DATELE
                ChatDtos.AuthDto registerData = new ChatDtos.AuthDto(username, password);
                NetworkPacket request = new NetworkPacket(PacketType.REGISTER_REQUEST, 0, registerData);

                // 4. TRIMITEM (Se va cripta automat cu AES Tunel)
                TcpConnection.sendPacket(request);

                // 5. CITIM RASPUNSUL (Sincron)
                NetworkPacket responsePacket = TcpConnection.readNextPacket();

                runOnUiThread(() -> {
                    view.setEnabled(true); // Reactivam butonul

                    if (responsePacket != null && responsePacket.getType() == PacketType.REGISTER_RESPONSE) {
                        handleRegisterResponse(responsePacket, username, password, checkBox.isChecked());
                    } else {
                        showSnackbar("Eroare server: Raspuns invalid.");
                    }
                });

            } catch (Exception e) {
                e.printStackTrace();
                TcpConnection.close();
                runOnUiThread(() -> {
                    view.setEnabled(true);
                    showSnackbar("Eroare Conexiune: " + e.getMessage());
                });
            }
        }).start();
    }

    private void handleRegisterResponse(NetworkPacket packet, String userStr, String passStr, boolean save) {
        try {
            JsonElement payload = packet.getPayload();

            // Verificam daca e User (Json Object) sau Eroare (String)
            if (payload.isJsonObject()) {
                User user = gson.fromJson(payload, User.class);

                if (user != null && user.getUsername() != null) {
                    // SUCCES -> Salvam User Global
                    TcpConnection.setCurrentUser(user);
                    TcpConnection.setCurrentUserId(user.getId());

                    // Salvam preferintele daca e bifat
                    SharedPreferences.Editor editor = preferences.edit();
                    if (save) {
                        editor.putString("username", userStr);
                        editor.putString("password", passStr);
                    } else {
                        editor.remove("username");
                        editor.remove("password");
                    }
                    editor.apply();

                    Toast.makeText(this, "Cont creat cu succes!", Toast.LENGTH_SHORT).show();

                    // Mergem la Main
                    Intent intent = new Intent(RegisterActivity.this, MainActivity.class);
                    startActivity(intent);
                    finish();
                }
            } else {
                // EROARE (ex: "Username already exists")
                String errorMsg = gson.fromJson(payload, String.class);
                showSnackbar(errorMsg);

                // Curatam campurile de parola
                ((EditText)findViewById(R.id.editTextTextPassword2)).setText("");
                ((EditText)findViewById(R.id.editTextTextPassword3)).setText("");
            }
        } catch (Exception e) {
            showSnackbar("Eroare procesare raspuns server.");
        }
    }

    private void showSnackbar(String message) {
        Snackbar.make(
                findViewById(android.R.id.content),
                message,
                Snackbar.LENGTH_LONG
        ).setBackgroundTint(Color.RED).setTextColor(Color.WHITE).show();
    }
}