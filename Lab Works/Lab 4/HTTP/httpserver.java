import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.Headers;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONObject;
import org.json.JSONArray;

public class httpserver {
    private static final String STORAGE_DIR = "server_files";
    private static final String USERS_FILE = "users.json";
    private static final int PORT = 8080;
    private static final int BUFFER_SIZE = 4096;

    private static final Map<String, String> USERS = new ConcurrentHashMap<>();
    private static final Map<String, String> SESSIONS = new ConcurrentHashMap<>();

    static {
        loadUsers();
    }

    private static void loadUsers() {
        File usersFile = new File(USERS_FILE);
        if (usersFile.exists()) {
            try (FileReader reader = new FileReader(usersFile)) {
                StringBuilder content = new StringBuilder();
                char[] buffer = new char[1024];
                int bytesRead;
                while ((bytesRead = reader.read(buffer)) != -1) {
                    content.append(buffer, 0, bytesRead);
                }

                JSONObject json = new JSONObject(content.toString());
                JSONArray users = json.getJSONArray("users");

                for (int i = 0; i < users.length(); i++) {
                    JSONObject user = users.getJSONObject(i);
                    USERS.put(user.getString("username"), user.getString("password"));
                }
            } catch (Exception e) {
                System.err.println("Error loading users: " + e.getMessage());
                addDefaultUsers();
            }
        } else {
            addDefaultUsers();
        }
    }

    private static void addDefaultUsers() {
        USERS.put("admin", "admin123");
        USERS.put("user", "password123");
        saveUsers();
    }

    private static void saveUsers() {
        try (FileWriter writer = new FileWriter(USERS_FILE)) {
            JSONObject root = new JSONObject();
            JSONArray users = new JSONArray();

            for (Map.Entry<String, String> entry : USERS.entrySet()) {
                JSONObject user = new JSONObject();
                user.put("username", entry.getKey());
                user.put("password", entry.getValue());
                users.put(user);
            }

            root.put("users", users);
            writer.write(root.toString(2));
            writer.flush();
        } catch (IOException e) {
            System.err.println("Error saving users: " + e.getMessage());
        }
    }

    public static void main(String[] args) throws IOException {
        File storageDir = new File(STORAGE_DIR);
        if (!storageDir.exists()) {
            storageDir.mkdirs();
        }

        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);

        server.createContext("/download", new DownloadHandler());
        server.createContext("/upload", new UploadHandler());
        server.createContext("/list", new ListHandler());
        server.createContext("/delete", new DeleteHandler());
        server.createContext("/mkdir", new MkdirHandler());
        server.createContext("/login", new LoginHandler());
        server.createContext("/cd", new ChangeDirectoryHandler());
        server.createContext("/adduser", new AddUserHandler());

        server.setExecutor(Executors.newFixedThreadPool(10));

        server.start();
        System.out.println("FTP-like HTTP server started on port " + PORT);
    }

    static class AddUserHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (!exchange.getRequestMethod().equals("POST")) {
                    sendResponse(exchange, 405, "Method Not Allowed. Use POST to add users.");
                    return;
                }

                if (!isAdmin(exchange)) {
                    sendResponse(exchange, 403, "Forbidden: Only admin can add users");
                    return;
                }

                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8));
                StringBuilder requestBody = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    requestBody.append(line);
                }

                JSONObject requestJson = new JSONObject(requestBody.toString());
                String username = requestJson.getString("username");
                String password = requestJson.getString("password");

                if (username == null || username.isEmpty() || password == null || password.isEmpty()) {
                    sendResponse(exchange, 400, "Bad Request: Missing username or password");
                    return;
                }

                if (USERS.containsKey(username)) {
                    sendResponse(exchange, 409, "Conflict: User already exists");
                    return;
                }

                USERS.put(username, password);
                saveUsers();

                sendResponse(exchange, 201, "User created: " + username);
            } catch (Exception e) {
                e.printStackTrace();
                sendResponse(exchange, 500, "Internal Server Error: " + e.getMessage());
            }
        }

        private boolean isAdmin(HttpExchange exchange) {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (auth != null && auth.startsWith("Basic ")) {
                String credentials = new String(Base64.getDecoder().decode(auth.substring(6)), StandardCharsets.UTF_8);
                String[] parts = credentials.split(":", 2);

                if (parts.length == 2) {
                    String username = parts[0];
                    String password = parts[1];

                    return username.equals("admin") && USERS.get("admin").equals(password);
                }
            }

            String token = exchange.getRequestHeaders().getFirst("X-Auth-Token");
            return token != null && SESSIONS.containsKey(token) && SESSIONS.get(token).equals("admin");
        }
    }

    static class LoginHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (!exchange.getRequestMethod().equals("POST")) {
                    sendResponse(exchange, 405, "Method Not Allowed. Use POST for login.");
                    return;
                }

                String auth = exchange.getRequestHeaders().getFirst("Authorization");
                if (auth != null && auth.startsWith("Basic ")) {
                    String credentials = new String(Base64.getDecoder().decode(auth.substring(6)),
                            StandardCharsets.UTF_8);
                    String[] parts = credentials.split(":", 2);

                    if (parts.length == 2) {
                        String username = parts[0];
                        String password = parts[1];

                        if (USERS.containsKey(username) && USERS.get(username).equals(password)) {
                            String sessionToken = generateSessionToken();
                            SESSIONS.put(sessionToken, username);

                            exchange.getResponseHeaders().set("Content-Type", "application/json");
                            String response = "{\"status\":\"success\",\"token\":\"" + sessionToken + "\"}";
                            sendResponse(exchange, 200, response);
                            return;
                        }
                    }
                }

                exchange.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"FTP Server\"");
                sendResponse(exchange, 401, "Authentication required");

            } catch (Exception e) {
                e.printStackTrace();
                sendResponse(exchange, 500, "Internal Server Error: " + e.getMessage());
            }
        }

        private String generateSessionToken() {
            return "session_" + System.currentTimeMillis() + "_" + Math.random();
        }
    }

    static class ListHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (!exchange.getRequestMethod().equals("GET")) {
                    sendResponse(exchange, 405, "Method Not Allowed. Use GET for listing.");
                    return;
                }

                if (!isAuthenticated(exchange)) {
                    sendResponse(exchange, 401, "Authentication required");
                    return;
                }

                String query = exchange.getRequestURI().getQuery();
                String path = STORAGE_DIR;

                if (query != null) {
                    Map<String, String> params = parseQueryParams(query);
                    String requestedPath = params.get("path");

                    if (requestedPath != null && !requestedPath.isEmpty()) {
                        File requestedDir = new File(STORAGE_DIR + File.separator + requestedPath);
                        if (requestedDir.exists() && requestedDir.isDirectory() &&
                                requestedDir.getCanonicalPath().startsWith(new File(STORAGE_DIR).getCanonicalPath())) {
                            path = requestedDir.getCanonicalPath();
                        } else {
                            sendResponse(exchange, 400, "Invalid directory path");
                            return;
                        }
                    }
                }

                File dir = new File(path);
                File[] files = dir.listFiles();

                StringBuilder responseBuilder = new StringBuilder();
                responseBuilder.append("{\n  \"files\": [\n");

                if (files != null) {
                    for (int i = 0; i < files.length; i++) {
                        File file = files[i];
                        responseBuilder.append("    {\n");
                        responseBuilder.append("      \"name\": \"").append(file.getName()).append("\",\n");
                        responseBuilder.append("      \"type\": \"").append(file.isDirectory() ? "directory" : "file")
                                .append("\",\n");
                        responseBuilder.append("      \"size\": ").append(file.length()).append(",\n");
                        responseBuilder.append("      \"lastModified\": \"").append(
                                LocalDateTime.ofInstant(
                                        java.nio.file.attribute.FileTime.fromMillis(file.lastModified()).toInstant(),
                                        java.time.ZoneId.systemDefault()).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                                .append("\"\n");
                        responseBuilder.append("    }").append(i < files.length - 1 ? "," : "").append("\n");
                    }
                }

                responseBuilder.append("  ],\n");
                responseBuilder.append("  \"currentPath\": \"").append(path.substring(STORAGE_DIR.length()))
                        .append("\"\n");
                responseBuilder.append("}");

                exchange.getResponseHeaders().set("Content-Type", "application/json");
                sendResponse(exchange, 200, responseBuilder.toString());

            } catch (Exception e) {
                e.printStackTrace();
                sendResponse(exchange, 500, "Internal Server Error: " + e.getMessage());
            }
        }
    }

    static class DeleteHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (!exchange.getRequestMethod().equals("DELETE")) {
                    sendResponse(exchange, 405, "Method Not Allowed. Use DELETE for file deletion.");
                    return;
                }

                if (!isAuthenticated(exchange)) {
                    sendResponse(exchange, 401, "Authentication required");
                    return;
                }

                String query = exchange.getRequestURI().getQuery();
                if (query == null) {
                    sendResponse(exchange, 400, "Bad Request: Missing query parameters");
                    return;
                }

                Map<String, String> params = parseQueryParams(query);
                String filename = params.get("filename");

                if (filename == null || filename.isEmpty()) {
                    sendResponse(exchange, 400, "Bad Request: Missing filename parameter");
                    return;
                }

                filename = URLDecoder.decode(filename, StandardCharsets.UTF_8.name());

                File file = new File(STORAGE_DIR + File.separator + filename);
                if (!file.getCanonicalPath().startsWith(new File(STORAGE_DIR).getCanonicalPath())) {
                    sendResponse(exchange, 403, "Forbidden: Access denied");
                    return;
                }

                if (!file.exists()) {
                    sendResponse(exchange, 404, "File Not Found: " + filename);
                    return;
                }

                boolean isDeleted;
                if (file.isDirectory()) {
                    isDeleted = deleteDirectory(file);
                } else {
                    isDeleted = file.delete();
                }

                if (isDeleted) {
                    sendResponse(exchange, 200, "Successfully deleted: " + filename);
                } else {
                    sendResponse(exchange, 500, "Failed to delete: " + filename);
                }

            } catch (Exception e) {
                e.printStackTrace();
                sendResponse(exchange, 500, "Internal Server Error: " + e.getMessage());
            }
        }

        private boolean deleteDirectory(File directory) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        deleteDirectory(file);
                    } else {
                        file.delete();
                    }
                }
            }
            return directory.delete();
        }
    }

    static class MkdirHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (!exchange.getRequestMethod().equals("POST")) {
                    sendResponse(exchange, 405, "Method Not Allowed. Use POST for creating directories.");
                    return;
                }

                if (!isAuthenticated(exchange)) {
                    sendResponse(exchange, 401, "Authentication required");
                    return;
                }

                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8));
                StringBuilder requestBody = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    requestBody.append(line);
                }

                String path = null;
                String requestBodyStr = requestBody.toString();
                if (requestBodyStr.contains("path")) {
                    int startIdx = requestBodyStr.indexOf("\"path\"") + "\"path\"".length();
                    startIdx = requestBodyStr.indexOf("\"", startIdx) + 1;
                    int endIdx = requestBodyStr.indexOf("\"", startIdx);
                    if (startIdx > 0 && endIdx > startIdx) {
                        path = requestBodyStr.substring(startIdx, endIdx);
                    }
                }

                if (path == null || path.isEmpty()) {
                    sendResponse(exchange, 400, "Bad Request: Missing path parameter");
                    return;
                }

                path = URLDecoder.decode(path, StandardCharsets.UTF_8.name());

                File newDir = new File(STORAGE_DIR + File.separator + path);
                if (!newDir.getCanonicalPath().startsWith(new File(STORAGE_DIR).getCanonicalPath())) {
                    sendResponse(exchange, 403, "Forbidden: Access denied");
                    return;
                }

                if (newDir.exists()) {
                    sendResponse(exchange, 409, "Conflict: Directory already exists");
                    return;
                }

                boolean created = newDir.mkdirs();
                if (created) {
                    sendResponse(exchange, 201, "Directory created: " + path);
                } else {
                    sendResponse(exchange, 500, "Failed to create directory: " + path);
                }

            } catch (Exception e) {
                e.printStackTrace();
                sendResponse(exchange, 500, "Internal Server Error: " + e.getMessage());
            }
        }
    }

    static class ChangeDirectoryHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (!exchange.getRequestMethod().equals("GET")) {
                    sendResponse(exchange, 405, "Method Not Allowed. Use GET for directory navigation.");
                    return;
                }

                if (!isAuthenticated(exchange)) {
                    sendResponse(exchange, 401, "Authentication required");
                    return;
                }

                String query = exchange.getRequestURI().getQuery();
                if (query == null) {
                    sendResponse(exchange, 400, "Bad Request: Missing query parameters");
                    return;
                }

                Map<String, String> params = parseQueryParams(query);
                String path = params.get("path");

                if (path == null) {
                    sendResponse(exchange, 400, "Bad Request: Missing path parameter");
                    return;
                }

                path = URLDecoder.decode(path, StandardCharsets.UTF_8.name());

                File directory = new File(STORAGE_DIR + File.separator + path);
                if (!directory.getCanonicalPath().startsWith(new File(STORAGE_DIR).getCanonicalPath())) {
                    sendResponse(exchange, 403, "Forbidden: Access denied");
                    return;
                }

                if (!directory.exists() || !directory.isDirectory()) {
                    sendResponse(exchange, 404, "Directory Not Found: " + path);
                    return;
                }

                File[] files = directory.listFiles();

                StringBuilder responseBuilder = new StringBuilder();
                responseBuilder.append("{\n  \"files\": [\n");

                if (files != null) {
                    for (int i = 0; i < files.length; i++) {
                        File file = files[i];
                        responseBuilder.append("    {\n");
                        responseBuilder.append("      \"name\": \"").append(file.getName()).append("\",\n");
                        responseBuilder.append("      \"type\": \"").append(file.isDirectory() ? "directory" : "file")
                                .append("\",\n");
                        responseBuilder.append("      \"size\": ").append(file.length()).append(",\n");
                        responseBuilder.append("      \"lastModified\": \"").append(
                                LocalDateTime.ofInstant(
                                        java.nio.file.attribute.FileTime.fromMillis(file.lastModified()).toInstant(),
                                        java.time.ZoneId.systemDefault()).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                                .append("\"\n");
                        responseBuilder.append("    }").append(i < files.length - 1 ? "," : "").append("\n");
                    }
                }

                responseBuilder.append("  ],\n");
                responseBuilder.append("  \"currentPath\": \"").append(path).append("\"\n");
                responseBuilder.append("}");

                exchange.getResponseHeaders().set("Content-Type", "application/json");
                sendResponse(exchange, 200, responseBuilder.toString());

            } catch (Exception e) {
                e.printStackTrace();
                sendResponse(exchange, 500, "Internal Server Error: " + e.getMessage());
            }
        }
    }

    static class DownloadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (!exchange.getRequestMethod().equals("GET")) {
                    sendResponse(exchange, 405, "Method Not Allowed. Use GET for downloads.");
                    return;
                }

                if (!isAuthenticated(exchange)) {
                    sendResponse(exchange, 401, "Authentication required");
                    return;
                }

                String query = exchange.getRequestURI().getQuery();
                String filename = null;

                if (query != null) {
                    Map<String, String> params = parseQueryParams(query);
                    filename = params.get("filename");
                }

                if (filename == null || filename.isEmpty()) {
                    sendResponse(exchange, 400, "Bad Request: Missing filename parameter");
                    return;
                }

                filename = URLDecoder.decode(filename, StandardCharsets.UTF_8.name());

                File file = new File(STORAGE_DIR + File.separator + filename);
                if (!file.getCanonicalPath().startsWith(new File(STORAGE_DIR).getCanonicalPath())) {
                    sendResponse(exchange, 403, "Forbidden: Access denied");
                    return;
                }

                if (!file.exists() || !file.isFile()) {
                    sendResponse(exchange, 404, "File Not Found: " + filename);
                    return;
                }

                exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
                exchange.getResponseHeaders().set("Content-Disposition",
                        "attachment; filename=\"" + filename + "\"");

                exchange.sendResponseHeaders(200, file.length());

                try (FileInputStream fis = new FileInputStream(file);
                        OutputStream os = exchange.getResponseBody()) {

                    byte[] buffer = new byte[BUFFER_SIZE];
                    int bytesRead;

                    while ((bytesRead = fis.read(buffer)) != -1) {
                        os.write(buffer, 0, bytesRead);
                    }
                }

                System.out.println("File downloaded: " + filename);

            } catch (Exception e) {
                e.printStackTrace();
                sendResponse(exchange, 500, "Internal Server Error: " + e.getMessage());
            }
        }
    }

    static class UploadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (!exchange.getRequestMethod().equals("POST")) {
                    sendResponse(exchange, 405, "Method Not Allowed. Use POST for uploads.");
                    return;
                }

                if (!isAuthenticated(exchange)) {
                    sendResponse(exchange, 401, "Authentication required");
                    return;
                }

                String targetDir = STORAGE_DIR;
                String query = exchange.getRequestURI().getQuery();
                if (query != null) {
                    Map<String, String> params = parseQueryParams(query);
                    String path = params.get("path");

                    if (path != null && !path.isEmpty()) {
                        File dirFile = new File(STORAGE_DIR + File.separator + path);
                        if (dirFile.exists() && dirFile.isDirectory() &&
                                dirFile.getCanonicalPath().startsWith(new File(STORAGE_DIR).getCanonicalPath())) {
                            targetDir = dirFile.getCanonicalPath();
                        }
                    }
                }

                String timestamp = LocalDateTime.now().format(
                        DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));

                String originalFilename = "unknown";
                String contentDisposition = exchange.getRequestHeaders().getFirst("Content-Disposition");

                if (contentDisposition != null && contentDisposition.contains("filename=")) {
                    originalFilename = contentDisposition.split("filename=")[1]
                            .replace("\"", "").trim();
                }

                String newFilename = "upload_" + timestamp + "_" + originalFilename;
                File outputFile = new File(targetDir + File.separator + newFilename);

                try (InputStream is = exchange.getRequestBody();
                        FileOutputStream fos = new FileOutputStream(outputFile)) {

                    byte[] buffer = new byte[BUFFER_SIZE];
                    int bytesRead;

                    while ((bytesRead = is.read(buffer)) != -1) {
                        fos.write(buffer, 0, bytesRead);
                    }
                }

                String response = "File uploaded successfully. Saved as: " + newFilename;
                sendResponse(exchange, 200, response);

                System.out.println("File uploaded: " + newFilename);

            } catch (Exception e) {
                e.printStackTrace();
                sendResponse(exchange, 500, "Internal Server Error: " + e.getMessage());
            }
        }
    }

    private static Map<String, String> parseQueryParams(String query) {
        Map<String, String> params = new HashMap<>();

        if (query != null && !query.isEmpty()) {
            String[] pairs = query.split("&");

            for (String pair : pairs) {
                int idx = pair.indexOf("=");
                if (idx > 0) {
                    String key = pair.substring(0, idx);
                    String value = pair.substring(idx + 1);
                    params.put(key, value);
                }
            }
        }

        return params;
    }

    private static void sendResponse(HttpExchange exchange, int statusCode, String response)
            throws IOException {
        byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, responseBytes.length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(responseBytes);
        }
    }

    private static boolean isAuthenticated(HttpExchange exchange) {
        String authToken = exchange.getRequestHeaders().getFirst("X-Auth-Token");
        if (authToken != null && SESSIONS.containsKey(authToken)) {
            return true;
        }

        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        if (auth != null && auth.startsWith("Basic ")) {
            String credentials = new String(Base64.getDecoder().decode(auth.substring(6)), StandardCharsets.UTF_8);
            String[] parts = credentials.split(":", 2);

            if (parts.length == 2) {
                String username = parts[0];
                String password = parts[1];

                return USERS.containsKey(username) && USERS.get(username).equals(password);
            }
        }

        return false;
    }
}