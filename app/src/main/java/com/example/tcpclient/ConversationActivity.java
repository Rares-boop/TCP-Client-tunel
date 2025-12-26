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

import chat.ChatDtos;
import chat.Message;
import chat.NetworkPacket;
import chat.PacketType;

public class ConversationActivity extends AppCompatActivity {
    // Folosim volatile pt thread safety
    public volatile List<Message> messages = new ArrayList<>();
    RecyclerView recyclerView;
    MessageAdapter messageAdapter;

    private int currentChatId = -1;
    private int partnerId = -1;
    private final Gson gson = new Gson();

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

        // INIT DATA
        Intent intent = getIntent();
        String chatName = intent.getStringExtra("CHAT_NAME");
        currentChatId = intent.getIntExtra("CHAT_ID", -1);
        partnerId = intent.getIntExtra("PARTNER_ID", -1);

        TextView txtChatName = findViewById(R.id.txtChatName);
        if(chatName != null) txtChatName.setText(chatName);

        // RECYCLER SETUP
        recyclerView = findViewById(R.id.recyclerViewMessages);
        messageAdapter = new MessageAdapter(this, messages, TcpConnection.getCurrentUserId(), this::handleLongMessageClick);

        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        layoutManager.setStackFromEnd(true);
        recyclerView.setLayoutManager(layoutManager);
        recyclerView.setAdapter(messageAdapter);

        View btnBack = findViewById(R.id.btnBackArrow);
        btnBack.setOnClickListener(v -> handleBackPress());
    }

    // =================================================================
    // 1. LIFECYCLE & LISTENER
    // =================================================================

    @Override
    protected void onResume() {
        super.onResume();
        Log.d("CHAT_UI", "🔵 onResume: Pornesc ascultarea...");

        // Setam listener-ul sa pointeze catre aceasta activitate
        TcpConnection.setPacketListener(this::handlePacketOnUI);

        sendEnterChatRequest();
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d("CHAT_UI", "⚫ onPause: Opresc ascultarea UI.");
        TcpConnection.setPacketListener(null);
        sendExitChatRequest();
    }

    private void handlePacketOnUI(NetworkPacket packet) {
        // Fortam rularea pe UI Thread ca sa poata atinge RecyclerView
        runOnUiThread(() -> handlePacket(packet));
    }

    private void handlePacket(NetworkPacket packet) {
        try {
            switch (packet.getType()) {
                case GET_MESSAGES_RESPONSE:
                    Log.d("CHAT_UI", "📜 Istoric primit!");
                    Type listType = new TypeToken<List<Message>>(){}.getType();
                    List<Message> history = gson.fromJson(packet.getPayload(), listType);

                    messages.clear();
                    if (history != null) {
                        messages.addAll(history);
                    }
                    // Aici folosim notifyDataSetChanged ca e lista noua
                    messageAdapter.notifyDataSetChanged();
                    scrollToBottom();
                    break;

                case RECEIVE_MESSAGE:
                    // AICI ERA PROBLEMA PROBABIL
                    Log.d("CHAT_UI", "✅ [UI] MESAJ NOU PRIMIT in timp real!");

                    Message msg = gson.fromJson(packet.getPayload(), Message.class);
                    if (msg != null) {
                        messages.add(msg);
                        // Folosim notifyDataSetChanged pt siguranta maxima la inceput
                        messageAdapter.notifyDataSetChanged();
                        scrollToBottom();
                    } else {
                        Log.e("CHAT_UI", "❌ Mesajul primit e NULL!");
                    }
                    break;

                case EDIT_MESSAGE_BROADCAST:
                    ChatDtos.EditMessageDto editDto = gson.fromJson(packet.getPayload(), ChatDtos.EditMessageDto.class);
                    for (int i = 0; i < messages.size(); i++) {
                        if (messages.get(i).getId() == editDto.messageId) {
                            messages.get(i).setContent(editDto.newContent);
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
                            messageAdapter.notifyDataSetChanged(); // Mai sigur la stergere
                            break;
                        }
                    }
                    break;

                case EXIT_CHAT_RESPONSE:
                    finish();
                    break;

                case ENTER_CHAT_RESPONSE:
                    Log.d("CHAT_UI", "✅ Server a confirmat intrarea in chat.");
                    break;
            }
        } catch (Exception e) {
            Log.e("CHAT_UI", "❌ Eroare procesare pachet: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // =================================================================
    // 2. TRIMITERE
    // =================================================================

    public void handleMessage(View view) {
        EditText messageBox = findViewById(R.id.editTextMessage);
        String text = messageBox.getText().toString().trim();

        if (text.isEmpty()) return;

        Log.d("CHAT_UI", "📤 Trimit mesaj: " + text);

        byte[] contentToSend = text.getBytes();
        Message msg = new Message(0, contentToSend, 0, TcpConnection.getCurrentUserId(), currentChatId);

        NetworkPacket packet = new NetworkPacket(PacketType.SEND_MESSAGE, TcpConnection.getCurrentUserId(), msg);
        TcpConnection.sendPacket(packet);

        messageBox.setText("");
    }

    private void performEdit(int messageId, String newText) {
        byte[] finalContent = newText.getBytes();
        ChatDtos.EditMessageDto dto = new ChatDtos.EditMessageDto(messageId, finalContent);
        NetworkPacket packet = new NetworkPacket(PacketType.EDIT_MESSAGE_REQUEST, TcpConnection.getCurrentUserId(), dto);
        TcpConnection.sendPacket(packet);
    }

    private void performDelete(int messageId) {
        NetworkPacket packet = new NetworkPacket(PacketType.DELETE_MESSAGE_REQUEST, TcpConnection.getCurrentUserId(), messageId);
        TcpConnection.sendPacket(packet);
    }

    // =================================================================
    // 3. HELPERE
    // =================================================================

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

