package de.luh.vss.chat.server;

import de.luh.vss.chat.common.Message;
import de.luh.vss.chat.common.User;
import de.luh.vss.chat.common.User.UserId;

import java.io.*;
import java.net.*;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class PrimaryChatServer {

    private final int port;
    private final int replicaPort;
    private ServerSocket serverSocket;
    private ServerSocket replicaServerSocket;
    private Socket replicaSocket;
    private DataOutputStream replicaOut;
    private DataInputStream replicaIn;

    // UserId -> User
    protected final Map<UserId, User> users = Collections.synchronizedMap(new HashMap<>());

    private final long HEARTBEAT_INTERVAL = 3000; // 3 seconds

    public PrimaryChatServer(int port, int replicaPort) {
        this.port = port;
        this.replicaPort = replicaPort;
    }

    public void start() {
        try {
            replicaServerSocket = new ServerSocket(replicaPort);
            System.out.println("PrimaryChatServer listening for replicas on port " + replicaPort);
            new Thread(this::acceptReplicaConnections).start();

            serverSocket = new ServerSocket(port);
            System.out.println("PrimaryChatServer started on client port " + port);

            new Thread(this::heartbeatSender).start();

            while (true) {
                Socket clientSocket = serverSocket.accept();
                new Thread(() -> handleClient(clientSocket)).start();
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Continuously accepts replica connections.
     * Only allows one replica connection at a time.
     */
    private void acceptReplicaConnections() {
        while (true) {
            try {
                System.out.println("Waiting for replica to connect on port " + replicaPort);
                Socket newReplicaSocket = replicaServerSocket.accept();
                synchronized (this) {
                    if (replicaSocket != null && !replicaSocket.isClosed()) {
                        System.out.println("A replica is already connected. Rejecting new replica connection.");
                        newReplicaSocket.close();
                        continue;
                    }
                    replicaSocket = newReplicaSocket;
                    replicaOut = new DataOutputStream(new BufferedOutputStream(replicaSocket.getOutputStream()));
                    replicaIn = new DataInputStream(new BufferedInputStream(replicaSocket.getInputStream()));
                    System.out.println("Replica connected from " + replicaSocket.getRemoteSocketAddress());
                }

                // Start a thread to monitor replica connection
                new Thread(this::listenToReplica).start();

            } catch (IOException e) {
                System.err.println("Error accepting replica connection.");
                e.printStackTrace();
            }
        }
    }

    /**
     * Monitors the replica connection for disconnections.
     */
    private void listenToReplica() {
        try {
            while (true) {
                // Listen for messages from the replica if needed
                if (replicaIn.read() == -1) { // End of stream indicates disconnection
                    System.out.println("Replica disconnected.");
                    closeReplicaConnection();
                    break;
                }
            }
        } catch (IOException e) {
            System.out.println("Replica disconnected unexpectedly.");
            closeReplicaConnection();
        }
    }

    /**
     * Periodically sends heartbeat messages to the connected replica.
     */
    private void heartbeatSender() {
        while (true) {
            try {
                Thread.sleep(HEARTBEAT_INTERVAL);
                synchronized (this) {
                    if (replicaOut != null) {
                        Message.Heartbeat heartbeat = new Message.Heartbeat();
                        heartbeat.toStream(replicaOut);
                        replicaOut.flush();
                        // Optional: Log heartbeat sending
                        // System.out.println("Sent HEARTBEAT to replica.");
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("Heartbeat sender interrupted.");
                break;
            } catch (IOException e) {
                System.err.println("Failed to send HEARTBEAT to replica.");
                e.printStackTrace();
            }
        }
    }

    /**
     * Handles client connections.
     */
    private void handleClient(Socket clientSocket) {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(clientSocket.getInputStream()));
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(clientSocket.getOutputStream()))) {

            while (true) {
                Message message = Message.parse(in);
                switch (message.getMessageType()) {
                    case REGISTER_REQUEST:
                        handleRegisterRequest((Message.RegisterRequest) message, out);
                        break;
                    case CHAT_MESSAGE:
                        handleChatMessage((Message.ChatMessage) message);
                        break;
                    default:
                        sendError(out, "Unknown message type.");
                }
            }

        } catch (IOException | ReflectiveOperationException e) {
            System.out.println("Client disconnected: " + clientSocket.getRemoteSocketAddress());
        }
    }

    /**
     * Handles registration requests from clients.
     */
    private void handleRegisterRequest(Message.RegisterRequest request, DataOutputStream out) throws IOException {
        UserId userId = request.getUserId();
        InetAddress address = request.getAddress();
        int port = request.getPort();

        InetSocketAddress endpoint = new InetSocketAddress(address, port);
        User user = new User(userId, endpoint);
        users.put(userId, user);
        System.out.println("Registered User: " + userId.id());

        Message.RegisterResponse response = new Message.RegisterResponse();
        response.toStream(out);
        out.flush();

        synchronizeState();
    }

    /**
     * Handles chat messages from clients.
     */
    private void handleChatMessage(Message.ChatMessage chatMessage) {
        UserId recipientId = chatMessage.getRecipient();
        String msg = chatMessage.getMessage();
        User recipient = users.get(recipientId);
        if (recipient != null) {
            // Here, it shoud be an implementation of sending the message to the recipient
            // For simplicity, we'll just print it
            System.out.println("Message to User " + recipientId.id() + ": " + msg);
        } else {
            System.out.println("User " + recipientId.id() + " not found.");
        }

        synchronizeState();
    }

    /**
     * Sends an error response to the client.
     */
    private void sendError(DataOutputStream out, String errorMsg) throws IOException {
        Message.ErrorResponse error = new Message.ErrorResponse(errorMsg);
        error.toStream(out);
        out.flush();
    }

    /**
     * Synchronizes the current state with the connected replica.
     */
    private synchronized void synchronizeState() {
        if (replicaOut == null) {
            System.out.println("No replica connected. Skipping state synchronization.");
            return;
        }

        try {
            // Serialize the users map
            ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
            ObjectOutputStream objOut = new ObjectOutputStream(byteOut);
            objOut.writeObject(users);
            objOut.flush();
            byte[] serializedData = byteOut.toByteArray();

            // Send STATE_SYNC message
            Message.StateSync stateSync = new Message.StateSync(serializedData);
            stateSync.toStream(replicaOut);
            replicaOut.flush();
            System.out.println("State synchronized with replica.");

        } catch (IOException e) {
            System.err.println("Failed to synchronize state with replica.");
            e.printStackTrace();
        }
    }

    /**
     * Closes the current replica connection, allowing new replicas to connect.
     */
    private synchronized void closeReplicaConnection() {
        try {
            if (replicaSocket != null && !replicaSocket.isClosed()) {
                replicaSocket.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            replicaOut = null;
            replicaIn = null;
            replicaSocket = null;
            System.out.println("Replica connection closed. Ready to accept new replica.");
        }
    }

    /**
     * Starts the server as Primary, similar to the standard start method.
     * This allows a Replica to promote itself to Primary.
     */
    public void startAsPrimary() {
        try {
            // Initialize replica ServerSocket and start listening in a separate thread
            replicaServerSocket = new ServerSocket(replicaPort);
            System.out.println("Promoted PrimaryChatServer listening for replicas on port " + replicaPort);
            new Thread(this::acceptReplicaConnections).start();

            // Start client server
            serverSocket = new ServerSocket(port);
            System.out.println("Promoted PrimaryChatServer started on client port " + port);

            // Start a thread to send heartbeats if replica is connected
            new Thread(this::heartbeatSender).start();

            while (true) {
                Socket clientSocket = serverSocket.accept();
                new Thread(() -> handleClient(clientSocket)).start();
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {

        int primaryPort = 5000;
        int replicaPort = 6000;

        PrimaryChatServer server = new PrimaryChatServer(primaryPort, replicaPort);
        server.start();
    }
}
