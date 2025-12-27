package com.example.tcpclient;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedDispatcher;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.SecretKey;

import chat.ChatDtos;
import chat.CryptoHelper;
import chat.Message;
import chat.NetworkPacket;
import chat.PacketType;

public class ConversationActivity extends AppCompatActivity {
    public volatile List<Message> messages = new ArrayList<>();
    RecyclerView recyclerView;
    MessageAdapter messageAdapter;

    private int currentChatId = -1;
    private final Gson gson = new Gson();
    private ClientKeyManager keyManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_conversation);

        // UI SETUP
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        View mainView = findViewById(R.id.main);
        ViewCompat.setOnApplyWindowInsetsListener(mainView, (v, insets) -> {
            int imeHeight = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
            v.setPadding(0, 0, 0, imeHeight);
            return insets;
        });

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    this::handleBackPress
            );
        }

        keyManager = new ClientKeyManager(this);

        Intent intent = getIntent();
        String chatName = intent.getStringExtra("CHAT_NAME");
        currentChatId = intent.getIntExtra("CHAT_ID", -1);

        TextView txtChatName = findViewById(R.id.txtChatName);
        if(chatName != null) txtChatName.setText(chatName);

        recyclerView = findViewById(R.id.recyclerViewMessages);
        messageAdapter = new MessageAdapter(this, messages, TcpConnection.getCurrentUserId(), this::handleLongMessageClick);

        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        layoutManager.setStackFromEnd(true);
        recyclerView.setLayoutManager(layoutManager);
        recyclerView.setAdapter(messageAdapter);

        View btnBack = findViewById(R.id.btnBackArrow);
        btnBack.setOnClickListener(v -> handleBackPress());

        checkAndGenerateKey();
    }

    private void checkAndGenerateKey() {
        if (currentChatId == -1) return;

        if (!keyManager.hasKey(currentChatId)) {
            Log.d("CHAT_KEY", "⚠️ Nu am cheie pt Chat " + currentChatId + ". Generez una noua...");

            String newKeyBase64 = keyManager.generateAndSaveKey(currentChatId);

            if (newKeyBase64 != null) {
                ChatDtos.SessionKeyDto dto = new ChatDtos.SessionKeyDto(currentChatId, newKeyBase64);
                NetworkPacket p = new NetworkPacket(PacketType.EXCHANGE_SESSION_KEY, TcpConnection.getCurrentUserId(), dto);
                TcpConnection.sendPacket(p);

                Log.d("CHAT_KEY", "📤 Cheie generata si trimisa la server!");
            }
        } else {
            Log.d("CHAT_KEY", "✅ Avem deja cheie criptata in Keystore.");
        }
    }

    private void decryptMessageInPlace(Message m) {
        SecretKey key = keyManager.getKey(currentChatId);
        if (key == null) return;

        try {
            String clearText = CryptoHelper.unpackAndDecrypt(key, m.getContent());
            m.setContent(clearText.getBytes());
        } catch (Exception e) {
            // Log.e("DECRYPT", "Fail (probabil cheie veche): " + e.getMessage());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        TcpConnection.setPacketListener(this::handlePacketOnUI);
        sendEnterChatRequest();
    }

    @Override
    protected void onPause() {
        super.onPause();
        TcpConnection.setPacketListener(null);
        sendExitChatRequest();
    }

    private void handlePacketOnUI(NetworkPacket packet) {
        runOnUiThread(() -> handlePacket(packet));
    }

    private void handlePacket(NetworkPacket packet) {
        try {
            switch (packet.getType()) {
                case EXCHANGE_SESSION_KEY:
                    ChatDtos.SessionKeyDto keyDto = gson.fromJson(packet.getPayload(), ChatDtos.SessionKeyDto.class);

                    Log.d("KEY_DEBUG", "Am primit o cheie pt ChatID: " + keyDto.chatId + " | ChatCurent: " + currentChatId);

                    if (keyDto.chatId == currentChatId) {
                        keyManager.saveKey(keyDto.chatId, keyDto.aesKeyBase64);

                        Toast.makeText(this, "🔐 Cheie primită! Mesajele se vor decripta.", Toast.LENGTH_SHORT).show();

                        messageAdapter.notifyDataSetChanged();
                    } else {
                        keyManager.saveKey(keyDto.chatId, keyDto.aesKeyBase64);
                        Log.d("KEY_DEBUG", "Cheie salvata in fundal.");
                    }
                    break;

                case GET_MESSAGES_RESPONSE:
                    Log.d("CHAT_UI", "📜 Istoric primit!");
                    Type listType = new TypeToken<List<Message>>(){}.getType();
                    List<Message> history = gson.fromJson(packet.getPayload(), listType);

                    messages.clear();
                    if (history != null) {
                        for(Message m : history) decryptMessageInPlace(m);
                        messages.addAll(history);
                    }
                    messageAdapter.notifyDataSetChanged();
                    scrollToBottom();
                    break;

                case RECEIVE_MESSAGE:
                    Log.d("CHAT_UI", "✅ [UI] MESAJ NOU PRIMIT!");
                    Message msg = gson.fromJson(packet.getPayload(), Message.class);
                    if (msg != null) {
                        decryptMessageInPlace(msg);
                        messages.add(msg);
                        messageAdapter.notifyDataSetChanged();
                        scrollToBottom();
                    }
                    break;

                case EDIT_MESSAGE_BROADCAST:
                    ChatDtos.EditMessageDto editDto = gson.fromJson(packet.getPayload(), ChatDtos.EditMessageDto.class);
                    for (int i = 0; i < messages.size(); i++) {
                        if (messages.get(i).getId() == editDto.messageId) {

                            byte[] finalContent = editDto.newContent;

                            SecretKey key = keyManager.getKey(currentChatId);
                            if (key != null) {
                                try {
                                    String decrypted = CryptoHelper.unpackAndDecrypt(key, editDto.newContent);
                                    finalContent = decrypted.getBytes();
                                } catch (Exception e) {
                                }
                            }

                            messages.get(i).setContent(finalContent);
                            messageAdapter.notifyItemChanged(i);
                            break;
                        }
                    }
                    break;

                case DELETE_MESSAGE_BROADCAST:
                    int deletedId = gson.fromJson(packet.getPayload(), Integer.class);
                    for (int i = 0; i < messages.size(); i++) {
                        if (messages.get(i).getId() == deletedId) {
                            messages.remove(i);
                            messageAdapter.notifyDataSetChanged();
                            break;
                        }
                    }
                    break;

                case EXIT_CHAT_RESPONSE:
                    finish();
                    break;

                case ENTER_CHAT_RESPONSE: break;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void handleMessage(View view) {
        EditText messageBox = findViewById(R.id.editTextMessage);
        String text = messageBox.getText().toString().trim();

        if (text.isEmpty()) return;

        SecretKey key = keyManager.getKey(currentChatId);

        if (key == null) {
            Toast.makeText(this, "⏳ Se negociază criptarea...", Toast.LENGTH_SHORT).show();
            checkAndGenerateKey();
            return;
        }

        try {
            byte[] encryptedContent = CryptoHelper.encryptAndPack(key, text);
            Message msg = new Message(0, encryptedContent, 0, TcpConnection.getCurrentUserId(), currentChatId);
            NetworkPacket packet = new NetworkPacket(PacketType.SEND_MESSAGE, TcpConnection.getCurrentUserId(), msg);
            TcpConnection.sendPacket(packet);

            messageBox.setText("");
        } catch (Exception e) {
            Toast.makeText(this, "Eroare Criptare!", Toast.LENGTH_SHORT).show();
        }
    }

    private void performEdit(int messageId, String newText) {
        SecretKey key = keyManager.getKey(currentChatId);
        if (key == null) return;

        try {
            byte[] encryptedContent = CryptoHelper.encryptAndPack(key, newText);
            ChatDtos.EditMessageDto dto = new ChatDtos.EditMessageDto(messageId, encryptedContent);
            NetworkPacket packet = new NetworkPacket(PacketType.EDIT_MESSAGE_REQUEST, TcpConnection.getCurrentUserId(), dto);
            TcpConnection.sendPacket(packet);
        } catch (Exception e) {
            Toast.makeText(this, "Fail Edit Encrypt", Toast.LENGTH_SHORT).show();
        }
    }

    private void performDelete(int messageId) {
        NetworkPacket packet = new NetworkPacket(PacketType.DELETE_MESSAGE_REQUEST, TcpConnection.getCurrentUserId(), messageId);
        TcpConnection.sendPacket(packet);
    }

    private void sendEnterChatRequest() {
        if (currentChatId != -1) {
            NetworkPacket packet = new NetworkPacket(PacketType.ENTER_CHAT_REQUEST, TcpConnection.getCurrentUserId(), currentChatId);
            TcpConnection.sendPacket(packet);
        }
    }

    private void sendExitChatRequest() {
        NetworkPacket packet = new NetworkPacket(PacketType.EXIT_CHAT_REQUEST, TcpConnection.getCurrentUserId());
        TcpConnection.sendPacket(packet);
    }

    public void handleBackPress() {
        finish();
    }

    private void scrollToBottom() {
        if (!messages.isEmpty()) {
            recyclerView.post(() -> recyclerView.smoothScrollToPosition(messages.size() - 1));
        }
    }

    @SuppressLint({"GestureBackNavigation", "MissingSuperCall"})
    @Override
    public void onBackPressed() {
        handleBackPress();
    }

    public void handleLongMessageClick(Message message) {
        if (message.getSenderId() != TcpConnection.getCurrentUserId()) return;

        android.text.SpannableString btnCancel = new android.text.SpannableString("Cancel");
        btnCancel.setSpan(new android.text.style.ForegroundColorSpan(Color.parseColor("#137fec")), 0, btnCancel.length(), 0);

        String[] options = {"Modify", "Delete"};
        android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, options) {
            @Override
            public View getView(int position, View convertView, android.view.ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                TextView textView = view.findViewById(android.R.id.text1);
                textView.setTextColor(Color.WHITE);
                return view;
            }
        };

        new AlertDialog.Builder(ConversationActivity.this, R.style.DialogSmecher)
                .setTitle("Options")
                .setAdapter(adapter, (dialog, which) -> {
                    if (which == 0) modifyMessage(message);
                    else deleteMessage(message);
                })
                .setNegativeButton(btnCancel, (dialog, which) -> dialog.cancel())
                .show();
    }

    public void modifyMessage(Message message) {
        EditText input = new EditText(this);
        String currentContent = new String(message.getContent());
        input.setTextColor(Color.WHITE);
        input.setText(currentContent);
        input.setSelection(currentContent.length());

        new AlertDialog.Builder(this, R.style.DialogSmecher)
                .setTitle("Modify message")
                .setView(input)
                .setPositiveButton("OK", (dialog, which) -> {
                    String newText = input.getText().toString().trim();
                    if (!newText.isEmpty()) performEdit(message.getId(), newText);
                })
                .setNegativeButton("Cancel", (dialog, which) -> dialog.cancel())
                .show();
    }

    public void deleteMessage(Message message) {
        new AlertDialog.Builder(this, R.style.DialogSmecher)
                .setTitle("Delete Message")
                .setMessage("Are you sure?")
                .setPositiveButton("DELETE", (dialog, which) -> performDelete(message.getId()))
                .setNegativeButton("Cancel", (dialog, which) -> dialog.cancel())
                .show();
    }
}

