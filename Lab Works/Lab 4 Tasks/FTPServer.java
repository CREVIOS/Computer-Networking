import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;

class A extends Thread {
    private Socket clientSocket;
    private PrintWriter output;
    private BufferedOutputStream fileOutput;
    private BufferedReader clientInput;
    private String path;
    private static int id;
    private FileInputStream fis;

    A(Socket socket, int id, String path) {
        this.clientSocket = socket;
        this.id = id;
        this.setName("Client " + this.id + ": ");
        this.path = path;
    }

    public void run() {
        try {
            System.out.println("Client Connected: " + clientSocket.getInetAddress().getHostAddress());
            
            output = new PrintWriter(clientSocket.getOutputStream(), true);
            fileOutput = new BufferedOutputStream(clientSocket.getOutputStream());
            clientInput = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
            
            String clientMessage = "";
            
            while (true) {
                output.println("\n1. List of files");
                output.println("2. Download");
                output.println("3. Exit");
                output.println();
                
                clientMessage = clientInput.readLine();
                if (clientMessage == null) {
                    System.out.println(Thread.currentThread().getName() + "Client disconnected");
                    break;
                }
                
                System.out.println(Thread.currentThread().getName() + " says: " + clientMessage);
                
                if (clientMessage.equals("1")) {
                    File folder = new File(path);
                    File[] files = folder.listFiles();
                    
                    if (files != null) {
                        for (File file : files) {
                            if (file.isFile()) {
                                output.println(file.getName());
                            }
                        }
                    } else {
                        output.println("There are no files.");
                    }
                    output.println();
                    
                } else if (clientMessage.equals("2")) {
                    output.println("Enter the file name you want to download:");
                    output.println();
                    
                    clientMessage = clientInput.readLine();
                    System.out.println(Thread.currentThread().getName() + " wants to download: " + clientMessage);
                    
                    String absPath = path + File.separator + clientMessage;
                    File file = new File(absPath);
                    
                    if (file.exists()) {
                        long fileSizeKB = (file.length() + 1023) / 1024;
                        output.println("File exists.");
                        output.println(fileSizeKB + " KB");
                        output.println();
                        
                        Thread.sleep(500);
                        
                        try {
                            fis = new FileInputStream(absPath);
                            byte[] buffer = new byte[4096];
                            int length;
                            
                            System.out.println("Starting file transfer: " + clientMessage + 
                                              " (" + file.length() + " bytes)");
                            
                            while ((length = fis.read(buffer)) > 0) {
                                fileOutput.write(buffer, 0, length);
                            }
                            
                            fileOutput.flush();
                            fis.close();
                            
                            System.out.println("File transfer completed: " + clientMessage);
                            
                            Thread.sleep(1000);
                            
                        } catch (IOException e) {
                            System.out.println("File transfer error: " + e.getMessage());
                            e.printStackTrace();
                        } finally {
                            if (fis != null) {
                                fis.close();
                            }
                        }
                        
                    } else {
                        output.println("File does not exist.");
                        output.println();
                    }
                    
                } else if (clientMessage.equals("3")) {
                    System.out.println(Thread.currentThread().getName() + " is disconnecting");
                    break;
                }
                
                Thread.sleep(100);
            }
            
        } catch (Exception e) {
            System.out.println("Connection error: " + e.getMessage());
            e.printStackTrace();
            
        } finally {
            try {
                if (output != null) output.close();
                if (fileOutput != null) fileOutput.close();
                if (clientInput != null) clientInput.close();
                if (clientSocket != null) clientSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            System.out.println(Thread.currentThread().getName() + " connection closed");
        }
    }
}

public class FTPServer {
    private static final int PORT = 5001;
    private static int id = 0;
    public static String absolutePath;
    
    public static void main(String[] args) {
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            String path = "Server_Files";
            absolutePath = System.getProperty("user.dir") + File.separator + path;

            File file = new File(absolutePath);
            if (!file.exists()) {
                file.mkdirs();
                System.out.println("Created server files directory at: " + absolutePath);
            }

            System.out.println("Server started at port: " + serverSocket.getLocalPort());
            System.out.println("File directory: " + absolutePath);
            System.out.println("Waiting for client connections...");

            while (true) {
                Socket clientSocket = serverSocket.accept();
                A newClient = new A(clientSocket, ++id, absolutePath);
                newClient.start();
            }

        } catch (IOException e) {
            System.out.println("Server error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}