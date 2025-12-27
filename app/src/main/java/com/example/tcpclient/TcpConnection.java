package com.example.tcpclient;

import android.util.Base64;
import android.util.Log;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.security.PublicKey;
import java.security.Security;

import javax.crypto.SecretKey;

import chat.CryptoHelper;
import chat.NetworkPacket;
import chat.PacketType;
import chat.User;

public class TcpConnection {
    public static Socket socket;
    private static ObjectOutputStream out;
    private static ObjectInputStream in;

    private static User currentUser;
    private static int currentUserId;

    private static SecretKey sessionKey = null;

    static {
        Security.removeProvider("BC");
        Security.addProvider(new BouncyCastleProvider());
    }

    public interface PacketListener {
        void onPacketReceived(NetworkPacket packet);
    }

    private static PacketListener currentListener;
    private static Thread readingThread;
    private static volatile boolean isReading = false;

    public static void setPacketListener(PacketListener listener) {
        currentListener = listener;
        Log.d("TCP", "Listener setat: " + (listener == null ? "NULL" : listener.getClass().getSimpleName()));
    }

    public static void startReading() {
        if (isReading) return;
        isReading = true;

        readingThread = new Thread(() -> {
            Log.d("TCP", "🚀 Listener Thread PORNIT.");
            try {
                while (isReading && socket != null && !socket.isClosed()) {
                    NetworkPacket packet = readNextPacket();

                    if (packet == null) {
                        Log.e("TCP", "Pachet NULL. Conexiune moarta.");
                        close();
                        break;
                    }

                    if (currentListener != null) {
                        currentListener.onPacketReceived(packet);
                    } else {
                        Log.w("TCP", "⚠️ Pachet ignorat (niciun listener activ): " + packet.getType());
                    }
                }
            } catch (Exception e) {
                Log.e("TCP", "Eroare Reading Thread: " + e.getMessage());
                close();
            }
        });
        readingThread.start();
    }

    public static void stopReading() {
        isReading = false;
    }

    public static void connect(String host, int port) throws Exception {
        socket = new Socket(host, port);
        out = new ObjectOutputStream(socket.getOutputStream());
        out.flush();
        in = new ObjectInputStream(socket.getInputStream());

        if (!performHandshake()) {
            close();
            throw new Exception("Handshake Server Esuat!");
        }
    }

    private static boolean performHandshake() {
        try {
            Log.d("TCP", "⏳ Start Handshake...");

            String jsonHello = (String) in.readObject();
            NetworkPacket helloPacket = NetworkPacket.fromJson(jsonHello);

            if (helloPacket.getType() == PacketType.KYBER_SERVER_HELLO) {
                String serverPubBase64 = helloPacket.getPayload().getAsString();
                byte[] serverPubBytes = Base64.decode(serverPubBase64, Base64.NO_WRAP);

                PublicKey serverKyberPub = CryptoHelper.decodeKyberPublicKey(serverPubBytes);
                CryptoHelper.KEMResult result = CryptoHelper.encapsulate(serverKyberPub);

                sessionKey = result.aesKey;

                byte[] wrappedBytes = result.wrappedKey;
                String wrappedBase64 = Base64.encodeToString(wrappedBytes, Base64.NO_WRAP);
                NetworkPacket finishPacket = new NetworkPacket(PacketType.KYBER_CLIENT_FINISH, 0, wrappedBase64);

                out.writeObject(finishPacket.toJson());
                out.flush();

                Log.d("TCP", "✅ Handshake OK! Tunel AES activ.");
                return true;
            }
            return false;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private static boolean isExemptFromTunnel(PacketType type) {
        return type == PacketType.SEND_MESSAGE ||
                type == PacketType.RECEIVE_MESSAGE ||
                type == PacketType.GET_MESSAGES_RESPONSE ||
                type == PacketType.EDIT_MESSAGE_BROADCAST ||
                type == PacketType.DELETE_MESSAGE_BROADCAST;
    }

    public static void sendPacket(NetworkPacket packet) {
        new Thread(() -> {
            try {
                if (socket != null && !socket.isClosed()) {

                    // LOGICA HIBRIDA:
                    if (sessionKey != null && !isExemptFromTunnel(packet.getType())) {
                        String clearJson = packet.toJson();
                        byte[] encryptedBytes = CryptoHelper.encryptAndPack(sessionKey, clearJson);
                        String encryptedBase64 = Base64.encodeToString(encryptedBytes, Base64.NO_WRAP);

                        NetworkPacket envelope = new NetworkPacket(PacketType.SECURE_ENVELOPE, currentUserId, encryptedBase64);

                        synchronized (out) {
                            out.writeObject(envelope.toJson());
                            out.flush();
                        }
                    } else {
                        synchronized (out) {
                            out.writeObject(packet.toJson());
                            out.flush();
                        }
                    }
                }
            } catch (Exception e) {
                Log.e("TCP", "Send Error: " + e.getMessage());
            }
        }).start();
    }

    public static NetworkPacket readNextPacket() throws Exception {
        String jsonRaw = (String) in.readObject();
        NetworkPacket packet = NetworkPacket.fromJson(jsonRaw);


        if (sessionKey != null && packet.getType() == PacketType.SECURE_ENVELOPE) {
            try {
                String encryptedPayload = packet.getPayload().getAsString();
                byte[] packedBytes = Base64.decode(encryptedPayload, Base64.NO_WRAP);

                String originalJson = CryptoHelper.unpackAndDecrypt(sessionKey, packedBytes);
                packet = NetworkPacket.fromJson(originalJson);
            } catch (Exception e) {
                Log.e("TCP", "Eroare decriptare Tunel!");
                throw e;
            }
        }
        else if (isExemptFromTunnel(packet.getType())) {
             Log.d("TCP", "📨 Pachet primit direct: " + packet.getType());
        }

        return packet;
    }

    public static void close() {
        try {
            isReading = false;
            sessionKey = null;
            if (out != null) out.close();
            if (in != null) in.close();
            if (socket != null) socket.close();
            Log.d("TCP", "Socket inchis.");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void setCurrentUser(User user) {
        currentUser = user;
    }

    public static User getCurrentUser() {
        return currentUser;
    }

    public static void setCurrentUserId(int id) {
        currentUserId = id;
    }

    public static int getCurrentUserId() {
        return currentUserId;
    }
}

