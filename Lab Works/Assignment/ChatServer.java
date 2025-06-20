import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

class Message implements Serializable {
    private static final long serialVersionUID = 1L;
    private int id;
    private String content;
    private boolean replied;
    private String reply;
    private long timestamp;

    public Message(int id, String content) {
        this.id = id;
        this.content = content;
        this.replied = false;
        this.reply = "";
        this.timestamp = System.currentTimeMillis();
    }

    public int getId() {
        return id;
    }

    public String getContent() {
        return content;
    }

    public boolean isReplied() {
        return replied;
    }

    public void setReplied(boolean replied) {
        this.replied = replied;
    }

    public String getReply() {
        return reply;
    }

    public void setReply(String reply) {
        this.reply = reply;
        this.replied = true;
    }

    public long getTimestamp() {
        return timestamp;
    }
}

class ClientData implements Serializable {
    private static final long serialVersionUID = 1L;
    private int userId;
    private List<Message> messages;
    private int messageCounter;

    public ClientData(int userId) {
        this.userId = userId;
        this.messages = new ArrayList<>();
        this.messageCounter = 1;
    }

    public int getUserId() {
        return userId;
    }

    public List<Message> getMessages() {
        return messages;
    }

    public int getMessageCounter() {
        return messageCounter;
    }

    public void setMessageCounter(int counter) {
        this.messageCounter = counter;
    }
}

class DatabaseManager {
    private static final String DB_FILE = "chatdb.dat";
    private static final Object fileLock = new Object();
    private static Map<Integer, ClientData> cachedClientData = null;
    private static long lastModifiedTime = 0;

    public static Map<Integer, ClientData> loadAllClientData() {
        synchronized (fileLock) {
            File dbFile = new File(DB_FILE);

            if (!dbFile.exists()) {
                if (cachedClientData == null) {
                    cachedClientData = new HashMap<>();
                }
                return new HashMap<>(cachedClientData);
            }

            long currentModified = dbFile.lastModified();
            if (cachedClientData != null && currentModified == lastModifiedTime) {
                return new HashMap<>(cachedClientData);
            }

            try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(dbFile))) {
                @SuppressWarnings("unchecked")
                Map<Integer, ClientData> data = (Map<Integer, ClientData>) ois.readObject();
                cachedClientData = new HashMap<>(data);
                lastModifiedTime = currentModified;
                return new HashMap<>(data);
            } catch (IOException | ClassNotFoundException e) {
                System.out.println("Error loading chat database: " + e.getMessage());
                if (cachedClientData != null) {
                    return new HashMap<>(cachedClientData);
                }
                return new HashMap<>();
            }
        }
    }

    public static void saveAllClientData(Map<Integer, ClientData> allClientData) {
        synchronized (fileLock) {
            File dbFile = new File(DB_FILE);
            File backupFile = new File(DB_FILE + ".bak");

            if (dbFile.exists()) {
                try {
                    try (FileInputStream fis = new FileInputStream(dbFile);
                            FileOutputStream fos = new FileOutputStream(backupFile)) {
                        byte[] buffer = new byte[1024];
                        int length;
                        while ((length = fis.read(buffer)) > 0) {
                            fos.write(buffer, 0, length);
                        }
                    }
                } catch (IOException e) {
                    System.out.println("Warning: Could not create backup file: " + e.getMessage());
                }
            }

            try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(dbFile))) {
                oos.writeObject(allClientData);
                cachedClientData = new HashMap<>(allClientData);
                lastModifiedTime = dbFile.lastModified();
            } catch (IOException e) {
                System.out.println("Error saving chat database: " + e.getMessage());
            }
        }
    }

    public static void updateClientData(ClientData clientData) {
        Map<Integer, ClientData> allClientData = loadAllClientData();
        allClientData.put(clientData.getUserId(), clientData);
        saveAllClientData(allClientData);
    }

    public static ClientData getClientData(int userId) {
        Map<Integer, ClientData> allClientData = loadAllClientData();
        ClientData clientData = allClientData.get(userId);
        if (clientData == null) {
            clientData = new ClientData(userId);
            allClientData.put(userId, clientData);
            saveAllClientData(allClientData);
        }
        return clientData;
    }

    public static int getHighestUserId() {
        Map<Integer, ClientData> allClientData = loadAllClientData();
        int highestId = 0;
        for (Integer userId : allClientData.keySet()) {
            if (userId > highestId) {
                highestId = userId;
            }
        }
        return highestId;
    }
}

class ClientHandler extends Thread {
    private Socket clientSocket;
    private DataInputStream input;
    private DataOutputStream output;
    private int userId;
    private List<Message> messages;
    private int messageCounter;
    private ClientData clientData;
    private volatile boolean running = true;
    private static Map<Integer, ClientHandler> clientMap = new ConcurrentHashMap<>();

    ClientHandler(Socket socket, int id) {
        this(socket, id, null, null);
    }

    ClientHandler(Socket socket, int id, DataInputStream existingInput, DataOutputStream existingOutput) {
        this.clientSocket = socket;
        this.userId = id;
        this.clientData = DatabaseManager.getClientData(id);
        this.messages = Collections.synchronizedList(new ArrayList<>(clientData.getMessages()));
        this.messageCounter = clientData.getMessageCounter();

        if (existingInput != null) {
            this.input = existingInput;
        }
        if (existingOutput != null) {
            this.output = existingOutput;
        }

        this.setName("Client_" + this.userId);
        ClientHandler oldHandler = clientMap.put(this.userId, this);
        if (oldHandler != null) {
            oldHandler.running = false;
            try {
                oldHandler.clientSocket.close();
            } catch (IOException e) {
                System.out.println(e.getMessage());
                e.printStackTrace();
            }
        }
    }

    public static Map<Integer, ClientHandler> getClientMap() {
        return clientMap;
    }

    public int getUserId() {
        return userId;
    }

    public int getUnrepliedCount() {
        int count = 0;
        synchronized (messages) {
            for (Message msg : messages) {
                if (!msg.isReplied()) {
                    count++;
                }
            }
        }
        return count;
    }

    public List<Message> getUnrepliedMessages() {
        List<Message> unreplied = new ArrayList<>();
        synchronized (messages) {
            for (Message msg : messages) {
                if (!msg.isReplied()) {
                    unreplied.add(msg);
                }
            }
        }
        return unreplied;
    }

    public void sendReply(int messageId, String replyText) {
        try {
            Message message = null;

            synchronized (messages) {
                for (Message msg : messages) {
                    if (msg.getId() == messageId) {
                        message = msg;
                        break;
                    }
                }
            }

            if (message != null) {
                message.setReply(replyText);
                String formattedReply = "Reply to [" + message.getContent() + "]: " + replyText;
                output.writeUTF(formattedReply);
                output.flush();
                saveChatsToDatabase();
            }
        } catch (IOException e) {
            System.out.println("Error sending reply: " + e.getMessage());
            System.out.flush();
        }
    }

    private void saveChatsToDatabase() {
        synchronized (messages) {
            clientData.getMessages().clear();
            clientData.getMessages().addAll(messages);
            clientData.setMessageCounter(messageCounter);
        }
        DatabaseManager.updateClientData(clientData);
    }

    public void run() {
        try {
            clientSocket.setSoTimeout(100);

            if (input == null) {
                input = new DataInputStream(this.clientSocket.getInputStream());
            }

            if (output == null) {
                output = new DataOutputStream(this.clientSocket.getOutputStream());
            }

            int unreplied = getUnrepliedCount();
            if (unreplied > 0) {
                output.writeUTF("Welcome back! You have " + unreplied + " unreplied message(s).");
                output.flush();
                
                List<Message> unrepliedMessages = getUnrepliedMessages();
                StringBuilder messageList = new StringBuilder("Your unreplied messages:\n");
                
                for (Message msg : unrepliedMessages) {
                    messageList.append(msg.getId()).append(". ").append(msg.getContent()).append("\n");
                }
                
                output.writeUTF(messageList.toString());
                output.flush();
            } else {
                output.writeUTF("Welcome!");
                output.flush();
            }

            String clientMessage = "";

            while (running && !clientMessage.equalsIgnoreCase("stop")) {
                try {
                    clientMessage = input.readUTF();

                    if (clientMessage.equalsIgnoreCase("stops")) {
                        System.out.println("Client_" + userId + " requested to disconnect.");
                        System.out.flush();
                        break;
                    }

                    String[] parts = clientMessage.split(":", 2);
                    if (parts.length == 2) {
                        String messageContent = parts[1].trim();
                        synchronized (messages) {
                            Message newMessage = new Message(messageCounter++, messageContent);
                            messages.add(newMessage);
                        }
                    } else {
                        synchronized (messages) {
                            Message newMessage = new Message(messageCounter++, clientMessage);
                            messages.add(newMessage);
                        }
                    }

                    saveChatsToDatabase();
                } catch (java.net.SocketTimeoutException e) {
                    continue;
                } catch (java.io.EOFException e) {
                    System.out.println("Client_" + userId + " disconnected unexpectedly.");
                    System.out.flush();
                    break;
                }
            }

        } catch (Exception e) {
            if (running) {
                System.out.println("Error in client handler: " + e.getMessage());
                System.out.flush();
            }
        } finally {
            try {
                saveChatsToDatabase();

                if (input != null) input.close();
                if (output != null) output.close();
                if (clientSocket != null && !clientSocket.isClosed()) {
                    clientSocket.close();
                }
                if (clientMap.get(userId) == this) {
                    clientMap.remove(userId);
                }
            } catch (IOException e) {
                if (running) {
                    System.out.println("Error closing client resources: " + e.getMessage());
                    System.out.flush();
                }
            }
            if (running) {
                System.out.println("Connection closed for Client_" + userId);
                System.out.flush();
            }
        }
    }
}

public class ChatServer {
    private static final int PORT = 5001;
    private static int id = 0;
    private static volatile boolean serverRunning = true;

    public static void main(String[] args) {
        id = DatabaseManager.getHighestUserId();
        
        ServerSocket serverSocket = null;
        try {
            serverSocket = new ServerSocket(PORT);
            System.out.println("Server started at port: " + serverSocket.getLocalPort());
            System.out.println("Waiting for client connection...");
            System.out.flush();
            
            final ServerSocket finalServerSocket = serverSocket;
            
            Thread acceptThread = new Thread(() -> {
                try {
                    while (serverRunning) {
                        try {
                            Socket clientSocket = finalServerSocket.accept();
    
                            DataInputStream tempInput = new DataInputStream(clientSocket.getInputStream());
                            DataOutputStream tempOutput = new DataOutputStream(clientSocket.getOutputStream());
    
                            String initialMessage = tempInput.readUTF();
    
                            if (initialMessage.equals("NEW_USER_REQUEST")) {
                                int newId = ++id;
                                tempOutput.writeUTF("NEW_USER_ID:" + newId);
                                tempOutput.flush();
    
                                ClientHandler newClient = new ClientHandler(clientSocket, newId, tempInput, tempOutput);
                                synchronized (System.out) {
                                    System.out.println("\nNew client (Client_" + newId + ") connected.");
                                    System.out.flush();
                                }
                                newClient.start();
                            } else if (initialMessage.startsWith("EXISTING_USER:")) {
                                try {
                                    int existingId = Integer.parseInt(initialMessage.split(":")[1].trim());
    
                                    ClientData existingData = DatabaseManager.getClientData(existingId);
                                    if (existingData != null) {
                                        tempOutput.writeUTF("USER_VERIFIED:" + existingId);
                                        tempOutput.flush();
    
                                        ClientHandler existingClient = new ClientHandler(clientSocket, existingId,
                                                tempInput, tempOutput);
                                        synchronized (System.out) {
                                            System.out.println("\nClient_" + existingId + " connected.");
                                            System.out.flush();
                                        }
                                        existingClient.start();
                                    } else {
                                        tempOutput.writeUTF("USER_NOT_FOUND");
                                        tempOutput.flush();
                                        clientSocket.close();
                                    }
                                } catch (NumberFormatException e) {
                                    tempOutput.writeUTF("INVALID_ID_FORMAT");
                                    tempOutput.flush();
                                    clientSocket.close();
                                }
                            } else {
                                tempOutput.writeUTF("INVALID_REQUEST");
                                tempOutput.flush();
                                clientSocket.close();
                            }
                        } catch (IOException e) {
                            if (serverRunning && e.getMessage() != null) {
                                synchronized (System.out) {
                                    System.out.println("Error accepting client: " + e.getMessage());
                                    System.out.flush();
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    if (serverRunning) {
                        synchronized (System.out) {
                            System.out.println("Server error: " + e.getMessage());
                            e.printStackTrace();
                            System.out.flush();
                        }
                    }
                }
            });
            acceptThread.setDaemon(true);
            acceptThread.start();
            handleServerOperations();
            
        } catch (IOException e) {
            System.out.println("Server error: " + e.getMessage());
            e.printStackTrace();
            System.out.flush();
        } finally {
            serverRunning = false;
            if (serverSocket != null) {
                try {
                    serverSocket.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private static void handleServerOperations() {
        BufferedReader console = new BufferedReader(new InputStreamReader(System.in));

        try {
            while (serverRunning) {
                synchronized (System.out) {
                    System.out.println("\n1. Continue");
                    System.out.println("2. Exit");
                    System.out.flush();
                }

                String choice = console.readLine();
                if (choice == null) {
                    break;
                }

                if (choice.equals("1")) {
                    handleClientSelection(console);
                } else if (choice.equals("2")) {
                    System.out.println("Shutting down server...");
                    System.out.flush();
                    serverRunning = false;
                    System.exit(0);
                } else {
                    System.out.println("Invalid option. Please try again.");
                    System.out.flush();
                }
            }
        } catch (IOException e) {
            System.out.println("Error reading from console: " + e.getMessage());
            System.out.flush();
        }
    }

    private static void handleClientSelection(BufferedReader console) throws IOException {
        Map<Integer, ClientHandler> clients = ClientHandler.getClientMap();

        if (clients.isEmpty()) {
            System.out.println("No clients connected.");
            System.out.flush();
            return;
        }

        System.out.println("Choose Whom to reply:");
        System.out.flush();

        List<Integer> clientIds = new ArrayList<>(clients.keySet());
        Collections.sort(clientIds);

        for (Integer clientId : clientIds) {
            ClientHandler client = clients.get(clientId);
            int unrepliedCount = client.getUnrepliedCount();
            System.out.println(clientId + ". Client_" + clientId + " (" + unrepliedCount + " unreplied messages)");
            System.out.flush();
        }

        System.out.print("Select a Client: ");
        System.out.flush();
        
        String clientChoice = console.readLine();
        if (clientChoice == null) {
            return;
        }

        try {
            int selectedUserId = Integer.parseInt(clientChoice);
            ClientHandler selectedClient = clients.get(selectedUserId);

            if (selectedClient != null) {
                handleMessageSelection(selectedClient, console);
            } else {
                System.out.println("Invalid client selection.");
                System.out.flush();
            }
        } catch (NumberFormatException e) {
            System.out.println("Please enter a valid client number.");
            System.out.flush();
        }
    }

    private static void handleMessageSelection(ClientHandler client, BufferedReader console) throws IOException {
        while (true) {
            List<Message> unrepliedMessages = client.getUnrepliedMessages();
            if (unrepliedMessages.isEmpty()) {
                System.out.println("No unreplied messages from this client.");
                System.out.flush();
                return;
            }
    
            System.out.println("Messages from Client_" + client.getUserId() + " :");
            System.out.flush();
            for (Message msg : unrepliedMessages) {
                System.out.println(msg.getId() + ". " + msg.getContent());
                System.out.flush();
            }
    
            System.out.print("Select message to reply (or type 'back' to return to menu): ");
            System.out.flush();
            String messageChoice = console.readLine();
            if (messageChoice == null) {
                return;
            }
            
            if (messageChoice.equalsIgnoreCase("back")) {
                return;
            }
    
            try {
                int selectedMessageId = Integer.parseInt(messageChoice);
                boolean found = false;
    
                for (Message msg : unrepliedMessages) {
                    if (msg.getId() == selectedMessageId) {
                        found = true;
                        System.out.print("Send a reply: ");
                        System.out.flush();
                        
                        String replyText = console.readLine();
                        if (replyText == null) {
                            return;
                        }
                        client.sendReply(selectedMessageId, replyText);
                        break;
                    }
                }
    
                if (!found) {
                    System.out.println("Invalid message selection. Please try again.");
                    System.out.flush();
                }
            } catch (NumberFormatException e) {
                System.out.println("Please enter a valid message number.");
                System.out.flush();
            }
            
            if (client.getUnrepliedCount() == 0) {
                System.out.println("All messages have been replied to.");
                System.out.flush();
                return;
            }
        }
    }
}