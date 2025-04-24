import java.io.*;
import java.net.Socket;

public class ChatClient {
    private static final int port = 5001;
    private static final String ipadd = "localhost";

    public static void main(String[] args) {
        try {
            Socket socket = new Socket(ipadd, port);
            System.out.println("Client Connected at server Handshaking port " + socket.getPort());
            System.out.println("Client's communication port " + socket.getLocalPort());
            System.out.println("Client is Connected");

            System.out.println(" 1. Already a User?");
            System.out.println(" 2. New User");

            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            BufferedReader readFromSys = new BufferedReader(new InputStreamReader(System.in));
            DataInputStream input = new DataInputStream(socket.getInputStream());

            String choice = readFromSys.readLine();
            int userId;

            if (choice.equals("1")) {
                System.out.print("Enter your userid: ");
                String userIdInput = readFromSys.readLine();
                try {
                    userId = Integer.parseInt(userIdInput);

                    output.writeUTF("EXISTING_USER:" + userId);
                    output.flush();

                    String response = input.readUTF();

                    if (response.startsWith("USER_VERIFIED:")) {
                        String[] parts = response.split(":");
                        userId = Integer.parseInt(parts[1]);
                        System.out.println("User verified. Welcome back, User " + userId + "!");
                    } else if (response.equals("USER_NOT_FOUND")) {
                        System.out.println("User ID not found. Please try again or create a new account.");
                        socket.close();
                        return;
                    } else if (response.equals("INVALID_ID_FORMAT")) {
                        System.out.println("Invalid user ID format. Please try again.");
                        socket.close();
                        return;
                    } else {
                        System.out.println("Unexpected server response: " + response);
                        socket.close();
                        return;
                    }
                } catch (NumberFormatException e) {
                    System.out.println("Invalid user ID. Please enter a number.");
                    socket.close();
                    return;
                }
            } else if (choice.equals("2")) {
                output.writeUTF("NEW_USER_REQUEST");
                output.flush();

                String response = input.readUTF();

                String[] parts = response.split(":");
                if (parts.length == 2 && parts[0].equals("NEW_USER_ID")) {
                    userId = Integer.parseInt(parts[1]);
                    System.out.println("Your assigned userid is: " + userId);
                    System.out.println("Please remember this ID to access your chats in the future.");
                } else {
                    System.out.println("Invalid server response: " + response);
                    socket.close();
                    return;
                }
            } else {
                System.out.println("Invalid choice, exiting.");
                socket.close();
                return;
            }

            String welcomeMessage = input.readUTF();
            System.out.println(welcomeMessage);

            if (welcomeMessage.contains("unreplied message(s)")) {
                String unrepliedMessages = input.readUTF();
                System.out.println(unrepliedMessages);
            }

            System.out.println("Start conversation....");
            System.out.println("Type 'stops' to end the conversation.");
            
            Thread serverListener = new Thread(() -> {
                try {
                    while (true) {
                        String serverMessage = input.readUTF();
                        System.out.println("\nServer replied: " + serverMessage);
                        System.out.print("Send to Server: ");
                    }
                } catch (IOException e) {
                    System.out.println("\nConnection to server lost: " + e.getMessage());
                    System.out.println("Please restart the client to reconnect.");
                    System.exit(0);
                }
            });
            serverListener.setDaemon(true);
            serverListener.start();

            String userInput = "";
            while (!userInput.equalsIgnoreCase("stops")) {
                userInput = readFromSys.readLine();

                if (userInput.equalsIgnoreCase("stops")) {
                    output.writeUTF("stops");
                    output.flush();
                    break;
                } else {
                    output.writeUTF(userId + ": " + userInput);
                    output.flush();
                }
            }

            System.out.println("Disconnecting from server...");
            System.out.println("Your chats have been saved. You can access them with your user ID: " + userId);

            try {
                input.close();
                output.close();
                socket.close();
            } catch (IOException e) {
                System.out.println(e.getMessage());
                e.printStackTrace();
            }
        } catch (Exception e) {
            System.out.println("Connection Failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
}