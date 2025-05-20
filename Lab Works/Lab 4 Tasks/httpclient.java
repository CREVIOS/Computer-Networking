import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Scanner;

import org.json.JSONArray;
import org.json.JSONObject;

public class httpclient {
    private static final String SERVER_URL = "http://localhost:8080";
    private static final int BUFFER_SIZE = 4096;

    private String authToken = null;
    private String username = null;
    private String password = null;
    private String currentPath = "";

    private final Scanner scanner = new Scanner(System.in);

    public static void main(String[] args) {
        new httpclient().start();
    }

    public void start() {
        System.out.println("====================================");
        System.out.println("HTTP-based FTP Client");
        System.out.println("====================================");

        login();

        while (true) {
            System.out.print("\nftp(" + (currentPath.isEmpty() ? "/" : currentPath) + ")> ");
            String line = scanner.nextLine().trim();
            if (line.isEmpty())
                continue;

            String[] parts = line.split("\\s+", 2);
            String cmd = parts[0].toLowerCase();
            String arg = parts.length > 1 ? parts[1] : "";

            try {
                switch (cmd) {
                    case "ls":
                    case "dir":
                        listFiles();
                        break;
                    case "cd":
                        changeDirectory(arg);
                        break;
                    case "get":
                    case "download":
                        downloadFile(arg);
                        break;
                    case "put":
                    case "upload":
                        uploadFile(arg);
                        break;
                    case "mkdir":
                        createDirectory(arg);
                        break;
                    case "rm":
                    case "delete":
                        deleteFile(arg);
                        break;
                    case "adduser":
                        addUser(arg);
                        break;
                    case "help":
                        showHelp();
                        break;
                    case "exit":
                    case "quit":
                        System.out.println("Goodbye!");
                        return;
                    default:
                        System.out.println("Unknown command. Type 'help' for a list.");
                }
            } catch (Exception e) {
                System.out.println("Error: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    private void login() {
        while (authToken == null) {
            System.out.print("Username: ");
            username = scanner.nextLine().trim();
            System.out.print("Password: ");
            password = scanner.nextLine().trim();

            try {
                URL url = new URL(SERVER_URL + "/login");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                String basic = username + ":" + password;
                String enc = Base64.getEncoder().encodeToString(basic.getBytes(StandardCharsets.UTF_8));
                conn.setRequestProperty("Authorization", "Basic " + enc);
                conn.setDoOutput(true);
                conn.connect();

                if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                    String resp = readFullResponse(conn);
                    JSONObject j = new JSONObject(resp);
                    if (j.has("token")) {
                        authToken = j.getString("token");
                        System.out.println("Login successful!");
                    } else {
                        System.out.println("No token in response, login failed.");
                    }
                } else {
                    System.out.println("Login failed (" + conn.getResponseCode() + "). Try again.");
                }
                conn.disconnect();
            } catch (IOException ex) {
                System.out.println("Login error: " + ex.getMessage());
            }
        }
    }

    private void addUser(String arg) throws IOException {
        if (!username.equals("admin")) {
            System.out.println("Only admin can add users.");
            return;
        }

        if (arg.isEmpty()) {
            System.out.println("Usage: adduser <username>:<password>");
            return;
        }

        String[] parts = arg.split(":", 2);
        if (parts.length != 2) {
            System.out.println("Usage: adduser <username>:<password>");
            return;
        }

        String newUsername = parts[0];
        String newPassword = parts[1];

        URL url = new URL(SERVER_URL + "/adduser");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        setAuthHeader(conn);

        JSONObject req = new JSONObject();
        req.put("username", newUsername);
        req.put("password", newPassword);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(req.toString().getBytes(StandardCharsets.UTF_8));
        }

        if (conn.getResponseCode() == HttpURLConnection.HTTP_CREATED ||
                conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
            System.out.println(readFullResponse(conn));
        } else {
            handleErrorResponse(conn);
        }
        conn.disconnect();
    }

    private void listFiles() throws IOException {
        String qs = currentPath.isEmpty()
                ? ""
                : "?path=" + URLEncoder.encode(currentPath, StandardCharsets.UTF_8.name());
        URL url = new URL(SERVER_URL + "/list" + qs);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        setAuthHeader(conn);

        int code = conn.getResponseCode();
        if (code == HttpURLConnection.HTTP_OK) {
            String resp = readFullResponse(conn);
            showListing(resp);
        } else {
            handleErrorResponse(conn);
        }
        conn.disconnect();
    }

    private void changeDirectory(String path) throws IOException {
        if (path.isEmpty()) {
            System.out.println("Usage: cd <directory>");
            return;
        }
        String target;
        if (path.equals("..")) {
            int i = currentPath.lastIndexOf("/");
            target = (i >= 0) ? currentPath.substring(0, i) : "";
        } else if (path.startsWith("/")) {
            target = path.substring(1);
        } else {
            target = currentPath.isEmpty() ? path : currentPath + "/" + path;
        }

        URL url = new URL(SERVER_URL + "/cd?path=" + URLEncoder.encode(target, StandardCharsets.UTF_8.name()));
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        setAuthHeader(conn);

        if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
            String resp = readFullResponse(conn);
            currentPath = target;
            showListing(resp);
            System.out.println("Current directory: " + currentPath);
        } else {
            handleErrorResponse(conn);
        }
        conn.disconnect();
    }

    private void downloadFile(String filename) throws IOException {
        if (filename.isEmpty()) {
            System.out.println("Usage: get <filename>");
            return;
        }
        String full = currentPath.isEmpty() ? filename : currentPath + "/" + filename;
        URL url = new URL(SERVER_URL + "/download?filename=" +
                URLEncoder.encode(full, StandardCharsets.UTF_8.name()));
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        setAuthHeader(conn);

        if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
            int length = Math.max(conn.getContentLength(), 1);
            try (InputStream in = conn.getInputStream();
                    FileOutputStream out = new FileOutputStream(filename)) {
                byte[] buf = new byte[BUFFER_SIZE];
                int read, prog = 0;
                long total = 0;
                System.out.println("Downloading " + filename + "...");
                System.out.print("[");
                while ((read = in.read(buf)) != -1) {
                    out.write(buf, 0, read);
                    total += read;
                    int np = (int) (total * 50 / length);
                    while (prog < np) {
                        System.out.print("=");
                        prog++;
                    }
                }
                while (prog < 50) {
                    System.out.print("=");
                    prog++;
                }
                System.out.println("] 100%");
                System.out.println("Saved to " + new File(filename).getAbsolutePath());
            }
        } else {
            handleErrorResponse(conn);
        }
        conn.disconnect();
    }

    private void uploadFile(String filename) throws IOException {
        if (filename.isEmpty()) {
            System.out.println("Usage: put <local_filename>");
            return;
        }
        File file = new File(filename);
        if (!file.exists() || !file.isFile()) {
            System.out.println("Local file not found: " + filename);
            return;
        }

        String qs = currentPath.isEmpty()
                ? ""
                : "?path=" + URLEncoder.encode(currentPath, StandardCharsets.UTF_8.name());
        URL url = new URL(SERVER_URL + "/upload" + qs);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/octet-stream");
        conn.setRequestProperty("Content-Disposition",
                "attachment; filename=\"" + file.getName() + "\"");
        setAuthHeader(conn);

        long size = file.length();
        try (FileInputStream fis = new FileInputStream(file);
                OutputStream os = conn.getOutputStream()) {
            byte[] buf = new byte[BUFFER_SIZE];
            int read, prog = 0;
            long total = 0;
            System.out.println("Uploading " + filename + "...");
            System.out.print("[");
            while ((read = fis.read(buf)) != -1) {
                os.write(buf, 0, read);
                total += read;
                int np = (int) (total * 50 / size);
                while (prog < np) {
                    System.out.print("=");
                    prog++;
                }
            }
            while (prog < 50) {
                System.out.print("=");
                prog++;
            }
            System.out.println("] 100%");
        }

        if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
            System.out.println(readFullResponse(conn));
        } else {
            handleErrorResponse(conn);
        }
        conn.disconnect();
    }

    private void createDirectory(String name) throws IOException {
        if (name.isEmpty()) {
            System.out.println("Usage: mkdir <dirname>");
            return;
        }
        String path = currentPath.isEmpty() ? name : currentPath + "/" + name;
        URL url = new URL(SERVER_URL + "/mkdir");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        setAuthHeader(conn);

        JSONObject req = new JSONObject();
        req.put("path", path);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(req.toString().getBytes(StandardCharsets.UTF_8));
        }

        if (conn.getResponseCode() == HttpURLConnection.HTTP_CREATED ||
                conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
            System.out.println(readFullResponse(conn));
        } else {
            handleErrorResponse(conn);
        }
        conn.disconnect();
    }

    private void deleteFile(String name) throws IOException {
        if (name.isEmpty()) {
            System.out.println("Usage: rm <name>");
            return;
        }
        String full = currentPath.isEmpty() ? name : currentPath + "/" + name;
        URL url = new URL(SERVER_URL + "/delete?filename=" +
                URLEncoder.encode(full, StandardCharsets.UTF_8.name()));
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("DELETE");
        setAuthHeader(conn);

        if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
            System.out.println(readFullResponse(conn));
        } else {
            handleErrorResponse(conn);
        }
        conn.disconnect();
    }

    private void showHelp() {
        System.out.println("\nCommands:");
        System.out.println("  ls|dir              List files");
        System.out.println("  cd <dir>            Change directory");
        System.out.println("  get|download <file> Download file");
        System.out.println("  put|upload <file>   Upload file");
        System.out.println("  mkdir <dir>         Create directory");
        System.out.println("  rm|delete <name>    Delete file/dir");
        System.out.println("  adduser <user>:<pw> Add new user (admin only)");
        System.out.println("  help                This message");
        System.out.println("  exit|quit           Quit");
    }

    private void setAuthHeader(HttpURLConnection conn) {
        if (authToken != null) {
            conn.setRequestProperty("X-Auth-Token", authToken);
        } else if (username != null && password != null) {
            String basic = username + ":" + password;
            String enc = Base64.getEncoder().encodeToString(basic.getBytes(StandardCharsets.UTF_8));
            conn.setRequestProperty("Authorization", "Basic " + enc);
        }
    }

    private void handleErrorResponse(HttpURLConnection conn) throws IOException {
        int code = conn.getResponseCode();
        InputStream err = conn.getErrorStream();
        String msg = "";
        if (err != null) {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(err, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line);
                }
                msg = sb.toString();
            }
        }
        System.out.println("Error (" + code + "): " + (msg.isEmpty() ? "Unknown error" : msg));
    }

    private String readFullResponse(HttpURLConnection conn) throws IOException {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        }
    }

    private void showListing(String jsonResponse) {
        JSONObject root = new JSONObject(jsonResponse);
        if (!root.has("files")) {
            System.out.println("(no file list in response)");
            return;
        }

        System.out.println("\nDirectory listing:");
        System.out.println("----------------------------------------");
        System.out.printf("%-30s %-12s %s%n", "Name", "Type", "Size");
        System.out.println("----------------------------------------");

        JSONArray files = root.getJSONArray("files");
        for (int i = 0; i < files.length(); i++) {
            JSONObject f = files.getJSONObject(i);
            System.out.printf("%-30s %-12s %s%n",
                    f.optString("name", "(unknown)"),
                    f.optString("type", "file"),
                    f.optString("size", "0"));
        }
        System.out.println("----------------------------------------");
    }
}