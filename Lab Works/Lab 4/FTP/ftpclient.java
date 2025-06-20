import java.io.*;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.net.ConnectException;
import java.util.Scanner;

public class ftpclient {
    private static final String DOWNLOAD_DIR = "Client_Downloaded_Files";
    private static final int CONNECTION_TIMEOUT = 10000;

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        String ipadd = "172.20.10.5";

        int port = 5001;

        try {
            System.out.println("Attempting to connect to " + ipadd + ":" + port + "...");
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(ipadd, port), CONNECTION_TIMEOUT);
            System.out.println("Client is Connected");

            BufferedReader serverInput = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            PrintWriter output = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader userInput = new BufferedReader(new InputStreamReader(System.in));
            InputStream dataInput = socket.getInputStream();

            String downloadPath = create_directory();

            while (true) {
                try {
                    String serverMenu = readMultipleLines(serverInput);
                    System.out.println("Server: " + serverMenu);

                    System.out.print("Enter your choice: ");
                    String userChoice = userInput.readLine();
                    output.println(userChoice);

                    if (userChoice.equals("3")) {
                        System.out.println("Exiting...");
                        break;
                    }

                    if (userChoice.equals("1")) {
                        String fileList = readMultipleLines(serverInput);
                        System.out.println("Available files:\n" + fileList);
                    } else if (userChoice.equals("2")) {
                        String filePrompt = readMultipleLines(serverInput);
                        System.out.println("Server: " + filePrompt);

                        System.out.print("Enter filename: ");
                        String filename = userInput.readLine();
                        output.println(filename);

                        String fileResponse = readMultipleLines(serverInput);
                        System.out.println("Server: " + fileResponse);

                        if (fileResponse.contains("File exists")) {
                            String[] parts = fileResponse.split("\n");
                            if (parts.length >= 2) {
                                String sizeInfo = parts[1].trim();
                                try {
                                    long fileSizeKB = Long.parseLong(sizeInfo.split("\\s+")[0]);
                                    long fileSizeBytes = fileSizeKB * 1024;

                                    System.out
                                            .println("File size: " + fileSizeKB + " KB (" + fileSizeBytes + " bytes)");

                                    String downloadedFilePath = downloadPath + File.separator + filename;
                                    FileOutputStream fileOutputStream = new FileOutputStream(downloadedFilePath);

                                    byte[] buffer = new byte[4096];
                                    int bytesRead;
                                    long totalBytesRead = 0;
                                    System.out.println("Downloading file...");

                                    long startTime = System.currentTimeMillis();
                                    long timeout = 30000;

                                    while (serverInput.ready()) {
                                        serverInput.read();
                                    }

                                    while (totalBytesRead < fileSizeBytes || dataInput.available() > 0) {
                                        if (dataInput.available() <= 0) {
                                            int waitCount = 0;
                                            while (dataInput.available() <= 0 && waitCount++ < 50) {
                                                if (totalBytesRead >= fileSizeBytes) {
                                                    break;
                                                }

                                                if (waitCount == 50 &&
                                                        System.currentTimeMillis() - startTime > timeout) {
                                                    break;
                                                }
                                                Thread.sleep(100);
                                            }
                                            if (dataInput.available() <= 0 && totalBytesRead >= fileSizeBytes * 0.999) {
                                                break;
                                            }
                                            if (waitCount >= 50)
                                                continue;
                                        }

                                        bytesRead = dataInput.read(buffer, 0,
                                                Math.min(buffer.length, dataInput.available()));
                                        if (bytesRead == -1) {
                                            break;
                                        }

                                        fileOutputStream.write(buffer, 0, bytesRead);
                                        totalBytesRead += bytesRead;

                                        double progress = (double) totalBytesRead / fileSizeBytes * 100;
                                        if (progress > 100)
                                            progress = 100;
                                        System.out.printf("\rProgress: %.2f%%", progress);
                                    }

                                    fileOutputStream.close();
                                    System.out.println("\nFile downloaded successfully as: " + downloadedFilePath);

                                    Thread.sleep(500);
                                    while (dataInput.available() > 0) {
                                        dataInput.skip(dataInput.available());
                                    }
                                } catch (NumberFormatException e) {
                                    System.out.println("Failed to parse file size: " + e.getMessage());
                                }
                            } else {
                                System.out.println("Invalid file size information received.");
                            }
                        } else {
                            System.out.println("The requested file doesn't exist on the server.");
                        }
                    }
                } catch (IOException e) {
                    System.out.println("Communication error: " + e.getMessage());
                    if (socket.isClosed()) {
                        System.out.println("Connection closed.");
                        break;
                    }
                }
            }

            serverInput.close();
            output.close();
            userInput.close();
            socket.close();
            System.out.println("Connection closed.");

        } catch (ConnectException e) {
            System.out.println("Connection Failed: Could not connect to " + ipadd + ":" + port);
        } catch (IOException e) {
            System.out.println("Connection Failed: " + e.getMessage());
            e.printStackTrace();
        } catch (InterruptedException e) {
            System.out.println("Download interrupted: " + e.getMessage());
            e.printStackTrace();
        } finally {
            scanner.close();
        }
    }

    private static String readMultipleLines(BufferedReader reader) throws IOException {
        StringBuilder response = new StringBuilder();
        String line;

        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        while (reader.ready() && (line = reader.readLine()) != null) {
            response.append(line).append("\n");
        }

        if (response.length() == 0) {
            try {
                Thread.sleep(300);
                while (reader.ready() && (line = reader.readLine()) != null) {
                    response.append(line).append("\n");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        return response.toString().trim();
    }

    public static String create_directory() {
        String currentDirectory = System.getProperty("user.dir");
        String downloadPath = currentDirectory + File.separator + DOWNLOAD_DIR;
        File directory = new File(downloadPath);

        if (!directory.exists()) {
            boolean directoryCreated = directory.mkdirs();
            if (directoryCreated) {
                System.out.println("Directory created successfully at: " + downloadPath);
            } else {
                System.out.println("Failed to create directory at: " + downloadPath);
            }
        } else {
            System.out.println("Using existing download directory: " + downloadPath);
        }

        return downloadPath;
    }
}