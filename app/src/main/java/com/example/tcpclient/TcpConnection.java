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

    // User Logat - Variabile statice
    private static User currentUser;
    private static int currentUserId;

    // Cheia TUNELULUI (Kyber/AES cu Serverul)
    private static SecretKey sessionKey = null;

    static {
        Security.removeProvider("BC");
        Security.addProvider(new BouncyCastleProvider());
    }

    // =================================================================
    // 1. SISTEMUL DE ASCULTARE (OBSERVER PATTERN)
    // =================================================================

    public interface PacketListener {
        void onPacketReceived(NetworkPacket packet);
    }

    private static PacketListener currentListener;
    private static Thread readingThread;
    private static volatile boolean isReading = false;

    // Activitatile apeleaza asta in onResume()
    public static void setPacketListener(PacketListener listener) {
        currentListener = listener;
        Log.d("TCP", "Listener setat: " + (listener == null ? "NULL" : listener.getClass().getSimpleName()));
    }

    // Pornim o singura data, dupa Login (in MainActivity)
    public static void startReading() {
        if (isReading) return;
        isReading = true;

        readingThread = new Thread(() -> {
            Log.d("TCP", "🚀 Listener Thread PORNIT.");
            try {
                while (isReading && socket != null && !socket.isClosed()) {
                    // Blocant: Citeste pachete de la server
                    NetworkPacket packet = readNextPacket();

                    if (packet == null) {
                        Log.e("TCP", "Pachet NULL. Conexiune moarta.");
                        close();
                        break;
                    }

                    // Trimite la UI (Activity curent)
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

    // =================================================================
    // 2. CONECTARE & HANDSHAKE
    // =================================================================

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

            // 1. Primim Hello de la Server (Public Key)
            String jsonHello = (String) in.readObject();
            NetworkPacket helloPacket = NetworkPacket.fromJson(jsonHello);

            if (helloPacket.getType() == PacketType.KYBER_SERVER_HELLO) {
                String serverPubBase64 = helloPacket.getPayload().getAsString();
                byte[] serverPubBytes = Base64.decode(serverPubBase64, Base64.NO_WRAP);

                // 2. Encapsulam cheia AES
                PublicKey serverKyberPub = CryptoHelper.decodeKyberPublicKey(serverPubBytes);
                CryptoHelper.KEMResult result = CryptoHelper.encapsulate(serverKyberPub);

                // Salvam cheia TUNELULUI
                sessionKey = result.aesKey;

                // 3. Trimitem inapoi (Finish)
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

    // =================================================================
    // 3. TRIMITERE & CITIRE INTELIGENTA
    // =================================================================

    // Lista alba: Pachete care TREC LIBER prin server (sunt deja securizate E2E de client)
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
                        // A. TUNEL: Pachet normal (Login, Create Chat) -> CRIPTAM CU AES SERVER
                        String clearJson = packet.toJson();
                        byte[] encryptedBytes = CryptoHelper.encryptAndPack(sessionKey, clearJson);
                        String encryptedBase64 = Base64.encodeToString(encryptedBytes, Base64.NO_WRAP);

                        NetworkPacket envelope = new NetworkPacket(PacketType.SECURE_ENVELOPE, currentUserId, encryptedBase64);

                        synchronized (out) {
                            out.writeObject(envelope.toJson());
                            out.flush();
                        }
                    } else {
                        // B. PASS-THROUGH: Pachet E2E (Mesaje) -> TRIMITEM DIRECT (JSON)
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

    // ATENTIE: Am facut metoda asta PUBLICA pentru LoginActivity!
    public static NetworkPacket readNextPacket() throws Exception {
        String jsonRaw = (String) in.readObject();
        NetworkPacket packet = NetworkPacket.fromJson(jsonRaw);

        // LOGICA REVERSA HIBRIDA:

        // A. Daca e PLIC (Secure Envelope) -> DECRIPTAM TUNELUL
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
        // B. Daca e MESAJ E2E -> Vine direct
        else if (isExemptFromTunnel(packet.getType())) {
            // Log.d("TCP", "📨 Pachet E2E Primit Direct: " + packet.getType());
        }

        return packet;
    }

    // =================================================================
    // 4. CLEANUP & GETTERS/SETTERS (Astea lipseau)
    // =================================================================

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

    // --- GETTERS & SETTERS PENTRU USER ---

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

