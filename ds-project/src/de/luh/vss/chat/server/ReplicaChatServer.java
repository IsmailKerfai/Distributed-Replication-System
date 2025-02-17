package de.luh.vss.chat.server;

import de.luh.vss.chat.common.Message;
import de.luh.vss.chat.common.MessageType;
import de.luh.vss.chat.common.User;

import java.io.*;
import java.net.Socket;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class ReplicaChatServer {

    private final int replicaPort;
    private Socket primarySocket;
    private DataOutputStream primaryOut;
    private DataInputStream primaryIn;

    // UserId -> User
    private final Map<User.UserId, User> users = Collections.synchronizedMap(new HashMap<>());

    private final long HEARTBEAT_TIMEOUT = 10000; // 10 seconds
    private volatile long lastHeartbeat = System.currentTimeMillis();
    private final long HEARTBEAT_CHECK_INTERVAL = 5000; // 5 seconds

    public ReplicaChatServer(int replicaPort) {
        this.replicaPort = replicaPort;
    }

    public void start() {
        new Thread(this::monitorHeartbeats).start();


        while (true) {
            try {
                connectToPrimary();
                listenForStateSync();
            } catch (Exception e) {
                System.err.println("Connection to PrimaryChatServer lost. Retrying in 5 seconds...");
                closeConnection();
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    System.err.println("ReplicaChatServer interrupted during retry wait.");
                    System.exit(-1);
                }
            }
        }
    }

    /**
     * Monitors the receipt of heartbeats and promotes to Primary if timeout exceeds.
     */
    private void monitorHeartbeats() {
        while (true) {
            try {
                Thread.sleep(HEARTBEAT_CHECK_INTERVAL);
                if (System.currentTimeMillis() - lastHeartbeat > HEARTBEAT_TIMEOUT) {
                    System.out.println("Heartbeat timeout. Promoting to Primary.");
                    promoteToPrimary();
                    break;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("Heartbeat monitor interrupted.");
                break;
            }
        }
    }

    /**
     * Attempts to connect to the PrimaryChatServer's replica port.
     */
    private void connectToPrimary() {
        while (true) {
            try {
                primarySocket = new Socket("localhost", replicaPort);
                primaryOut = new DataOutputStream(new BufferedOutputStream(primarySocket.getOutputStream()));
                primaryIn = new DataInputStream(new BufferedInputStream(primarySocket.getInputStream()));
                System.out.println("Connected to PrimaryChatServer on replica port " + replicaPort);
                break;
            } catch (IOException e) {
                System.err.println("Failed to connect to PrimaryChatServer on port " + replicaPort + ". Retrying in 5 seconds...");
                try {
                    Thread.sleep(5000); // Wait before retrying
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    System.err.println("ReplicaChatServer interrupted during connection retry.");
                    System.exit(-1);
                }
            }
        }
    }

    /**
     * Listens for state synchronization messages and heartbeats from the primary server.
     */
    private void listenForStateSync() throws IOException, ClassNotFoundException {
        while (true) {
            int messageType = primaryIn.readInt();
            MessageType mt = MessageType.fromInt(messageType);
            if (mt == null) {
                System.out.println("Unknown message type from primary: " + messageType);
                continue;
            }

            switch (mt) {
                case STATE_SYNC:
                    int length = primaryIn.readInt();
                    byte[] data = new byte[length];
                    primaryIn.readFully(data);

                    // Deserialize the users map
                    ByteArrayInputStream byteIn = new ByteArrayInputStream(data);
                    ObjectInputStream objIn = new ObjectInputStream(byteIn);
                    Map<User.UserId, User> updatedUsers = (Map<User.UserId, User>) objIn.readObject();
                    users.clear();
                    users.putAll(updatedUsers);
                    System.out.println("State synchronized from primary. Total users: " + users.size());
                    break;
                case HEARTBEAT:
                    lastHeartbeat = System.currentTimeMillis();
                    break;
                default:
                    System.out.println("Unhandled message type: " + mt);
            }
        }
    }

    /**
     * Promotes the Replica to Primary.
     */
    private void promoteToPrimary() {
        System.out.println("Promoting Replica to Primary...");
        try {
            closeConnection();

            PrimaryChatServer promotedPrimary = new PrimaryChatServer(replicaPort, replicaPort);
            promotedPrimary.users.putAll(this.users); // Transfer current state

            new Thread(promotedPrimary::startAsPrimary).start();

            System.out.println("Replica successfully promoted to Primary.");
        } catch (Exception e) {
            System.err.println("Error promoting Replica to Primary.");
            e.printStackTrace();
        }
    }

    /**
     * Closes the current connection to the Primary server.
     */
    private void closeConnection() {
        try {
            if (primarySocket != null && !primarySocket.isClosed()) {
                primarySocket.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            primaryOut = null;
            primaryIn = null;
            primarySocket = null;
            System.out.println("Connection to PrimaryChatServer closed.");
        }
    }

    public static void main(String[] args) {

        int replicaPort = 6000;

        ReplicaChatServer replica = new ReplicaChatServer(replicaPort);
        replica.start();
    }
}
