package de.luh.vss.chat.client;

import de.luh.vss.chat.common.Message;
import de.luh.vss.chat.common.MessageType;
import de.luh.vss.chat.common.User;
import de.luh.vss.chat.common.User.UserId;

import java.io.*;
import java.net.InetAddress;
import java.net.Socket;
import java.util.Scanner;

/**
 * ChatClient connects to the PrimaryChatServer, registers the user,
 * sends chat messages, and listens for incoming messages.
 * It includes reconnection logic to handle server failures and failovers.
 */
public class ChatClient {

    private final UserId userId;
    private final InetAddress serverAddress;
    private final int serverPort;

    private Socket socket;
    private DataInputStream in;
    private DataOutputStream out;

    private volatile boolean running = true;

    private final long RECONNECT_INTERVAL = 5000; // 5 seconds

    public ChatClient(UserId userId, InetAddress serverAddress, int serverPort) {
        this.userId = userId;
        this.serverAddress = serverAddress;
        this.serverPort = serverPort;
    }

    /**
     * Starts the ChatClient. It attempts to connect to the server,
     * handles user input, and listens for incoming messages.
     */
    public void start() {
        while (running) {
            try {
                connectToServer();
                // Start threads for handling communication
                new Thread(this::listenForMessages).start();
                handleUserInput();
            } catch (IOException | ReflectiveOperationException e) {
                System.err.println("Disconnected from server. Attempting to reconnect in 5 seconds...");
                closeConnections();
                try {
                    Thread.sleep(RECONNECT_INTERVAL);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    System.err.println("ChatClient interrupted during reconnection wait.");
                    break;
                }
            }
        }
    }

    /**
     * Attempts to connect to the PrimaryChatServer and register the user.
     */
    private void connectToServer() throws IOException, ReflectiveOperationException {
        socket = new Socket(serverAddress, serverPort);
        in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        System.out.println("Connected to server at " + serverAddress.getHostAddress() + ":" + serverPort);

        // Register with the server
        Message.RegisterRequest registerRequest = new Message.RegisterRequest(
                userId, InetAddress.getLocalHost(), socket.getLocalPort());
        registerRequest.toStream(out);
        out.flush();

        // Wait for registration response
        Message response = Message.parse(in);
        if (response.getMessageType() == MessageType.REGISTER_RESPONSE) {
            System.out.println("Registered successfully with the server.");
        } else if (response.getMessageType() == MessageType.ERROR_RESPONSE) {
            System.err.println("Registration failed: " + ((Message.ErrorResponse) response).toString());
            throw new IOException("Registration failed.");
        }

    }

    /**
     * Handles user input for sending chat messages.
     */
    private void handleUserInput() throws IOException {
        Scanner scanner = new Scanner(System.in);
        while (running && !socket.isClosed()) {
            System.out.print("Enter recipient ID and message (e.g., 2 Hello): ");
            String input = scanner.nextLine();
            if (input.equalsIgnoreCase("exit")) {
                running = false;
                closeConnections();
                System.out.println("Exiting chat client.");
                break;
            }
            String[] parts = input.split(" ", 2);
            if (parts.length < 2) {
                System.out.println("Invalid input. Please provide recipient ID and message.");
                continue;
            }
            int recipientId;
            try {
                recipientId = Integer.parseInt(parts[0]);
            } catch (NumberFormatException e) {
                System.out.println("Invalid recipient ID. Please enter a valid integer.");
                continue;
            }
            String message = parts[1];
            Message.ChatMessage chatMessage = new Message.ChatMessage(new User.UserId(recipientId), message);
            chatMessage.toStream(out);
            out.flush();
        }
    }

    /**
     * Listens for incoming messages from the server and displays them.
     */
    private void listenForMessages() {
        try {
            while (running && !socket.isClosed()) {
                Message message = Message.parse(in);
                switch (message.getMessageType()) {
                    case CHAT_MESSAGE:
                        Message.ChatMessage chatMsg = (Message.ChatMessage) message;
                        System.out.println("\nMessage from User " + chatMsg.getRecipient() + ": " + chatMsg.getMessage());
                        System.out.print("Enter recipient ID and message (e.g., 1 Hello): ");
                        break;
                    case HEARTBEAT:
                        break;
                    case ERROR_RESPONSE:
                        Message.ErrorResponse error = (Message.ErrorResponse) message;
                        System.err.println("Error from server: " + error.toString());
                        break;
                    default:
                        System.out.println("Unknown message type received: " + message);
                }
            }
        } catch (IOException | ReflectiveOperationException e) {
            System.err.println("Connection lost.");
            System.out.println("Press enter to check");
        }
    }

    /**
     * Closes all active connections and streams.
     */
    private void closeConnections() {
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
            if (in != null) {
                in.close();
            }
            if (out != null) {
                out.close();
            }
        } catch (IOException e) {
            // Ignore exceptions during close
        }
    }

    /**
     * Entry point for the ChatClient.
     */
    public static void main(String[] args) {
        int userId;
        try {
            userId = 1;
        } catch (NumberFormatException e) {
            System.err.println("Invalid UserID. Please enter a valid integer.");
            return;
        }
        InetAddress serverAddress;
        try {
            serverAddress = InetAddress.getByName("localhost");
        } catch (IOException e) {
            System.err.println("Invalid ServerAddress. Please enter a valid hostname or IP address.");
            return;
        }
        int serverPort;
        try {
            serverPort = 5000;
        } catch (NumberFormatException e) {
            System.err.println("Invalid ServerPort. Please enter a valid integer.");
            return;
        }

        ChatClient client = new ChatClient(new User.UserId(userId), serverAddress, serverPort);
        client.start();
    }
}
