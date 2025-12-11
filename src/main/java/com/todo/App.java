package com.todo;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * App.java
 * Simple Java HTTP server with REST API for tasks (GET, POST, PUT, DELETE),
 * static file serving, CORS support, and simple JSON file persistence.
 *
 * Place this file at: src/main/java/com/todo/App.java
 * Run with: mvn exec:java
 */
public class App {

    // Task model
    public static class Task {
        public String id;
        public String title;
        public boolean done;
        public String project;
        public int priority;
        public String due;
        public String createdAt;

        public Task() {}
    }

    private static final List<Task> tasks = Collections.synchronizedList(new ArrayList<>());
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private static final String STORAGE_FILE = System.getProperty("user.dir") + File.separator + "tasks.json";

    public static void main(String[] args) throws Exception {
        // Load tasks from disk if available
        loadFromDisk();

        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);

        String webRoot = System.getProperty("user.dir") + "/src/main/resources/web/";
        System.out.println("WEB ROOT PATH = " + webRoot);
        System.out.println("TASK STORAGE FILE = " + STORAGE_FILE);

        // Static file endpoints
        server.createContext("/", exchange -> serveFile(exchange, webRoot, "index.html"));
        server.createContext("/index.html", exchange -> serveFile(exchange, webRoot, "index.html"));
        server.createContext("/style.css", exchange -> serveFile(exchange, webRoot, "style.css"));
        server.createContext("/script.js", exchange -> serveFile(exchange, webRoot, "script.js"));
        server.createContext("/favicon.ico", exchange -> {
            // return no content
            addCORS(exchange);
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });

        // API endpoint
        server.createContext("/api/tasks", App::handleTasksRoot);
        // single task endpoints pattern will be handled inside handleTasksRoot by parsing path

        server.setExecutor(null);
        System.out.println("Server running at http://localhost:8080");
        server.start();
    }

    // ---------- Static file serving ----------
    private static void serveFile(HttpExchange exchange, String basePath, String filename) throws IOException {
        addCORS(exchange);

        String method = exchange.getRequestMethod();
        if ("OPTIONS".equalsIgnoreCase(method)) {
            // Preflight
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }

        File file = new File(basePath + filename);
        if (!file.exists() || !file.isFile()) {
            String msg = "404 Not Found: " + filename;
            exchange.sendResponseHeaders(404, msg.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(msg.getBytes());
            }
            return;
        }

        String contentType = "application/octet-stream";
        if (filename.endsWith(".html")) contentType = "text/html; charset=utf-8";
        else if (filename.endsWith(".css")) contentType = "text/css; charset=utf-8";
        else if (filename.endsWith(".js")) contentType = "application/javascript; charset=utf-8";

        byte[] bytes = Files.readAllBytes(file.toPath());
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    // ---------- API handling ----------
    private static void handleTasksRoot(HttpExchange exchange) throws IOException {
        try {
            addCORS(exchange);

            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath(); // e.g., /api/tasks or /api/tasks/{id}
            String context = "/api/tasks";
            String suffix = path.length() > context.length() ? path.substring(context.length()) : "";

            // handle preflight
            if ("OPTIONS".equalsIgnoreCase(method)) {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }

            if (suffix == null || suffix.isEmpty() || suffix.equals("/")) {
                // /api/tasks root
                if ("GET".equalsIgnoreCase(method)) {
                    handleGetAll(exchange);
                } else if ("POST".equalsIgnoreCase(method)) {
                    handleCreate(exchange);
                } else if ("DELETE".equalsIgnoreCase(method)) {
                    handleDeleteAll(exchange);
                } else {
                    sendJson(exchange, 405, Collections.singletonMap("error", "Method not allowed"));
                }
            } else {
                // suffix like /{id} or /{id}/...
                String id = suffix.startsWith("/") ? suffix.substring(1) : suffix;
                // in case there are trailing slashes, extract until next slash
                int slashIdx = id.indexOf('/');
                if (slashIdx != -1) id = id.substring(0, slashIdx);

                if ("GET".equalsIgnoreCase(method)) {
                    handleGetById(exchange, id);
                } else if ("PUT".equalsIgnoreCase(method)) {
                    handleUpdateById(exchange, id);
                } else if ("DELETE".equalsIgnoreCase(method)) {
                    handleDeleteById(exchange, id);
                } else {
                    sendJson(exchange, 405, Collections.singletonMap("error", "Method not allowed"));
                }
            }
        } catch (Exception ex) {
            ex.printStackTrace();
            sendJson(exchange, 500, Collections.singletonMap("error", "Server error"));
        }
    }

    // GET /api/tasks
    private static void handleGetAll(HttpExchange exchange) throws IOException {
        synchronized (tasks) {
            sendJson(exchange, 200, tasks);
        }
    }

    // POST /api/tasks
    private static void handleCreate(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Task t = gson.fromJson(body, Task.class);
        if (t == null) {
            sendJson(exchange, 400, Collections.singletonMap("error", "Invalid JSON"));
            return;
        }

        if (t.id == null || t.id.isEmpty()) t.id = UUID.randomUUID().toString();
        if (t.createdAt == null || t.createdAt.isEmpty()) t.createdAt = new Date().toString();
        if (t.project == null) t.project = "Inbox";

        synchronized (tasks) {
            tasks.add(t);
            persistToDisk();
            sendJson(exchange, 201, t);
        }
    }

    // PUT /api/tasks/{id}
    private static void handleUpdateById(HttpExchange exchange, String id) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Task incoming = gson.fromJson(body, Task.class);
        if (incoming == null) {
            sendJson(exchange, 400, Collections.singletonMap("error", "Invalid JSON"));
            return;
        }

        synchronized (tasks) {
            Optional<Task> found = tasks.stream().filter(x -> id.equals(x.id)).findFirst();
            if (found.isEmpty()) {
                sendJson(exchange, 404, Collections.singletonMap("error", "Task not found"));
                return;
            }
            Task t = found.get();
            // update fields if provided
            if (incoming.title != null) t.title = incoming.title;
            t.done = incoming.done;
            if (incoming.project != null) t.project = incoming.project;
            t.priority = incoming.priority;
            t.due = incoming.due;
            persistToDisk();
            sendJson(exchange, 200, t);
        }
    }

    // DELETE /api/tasks/{id}
    private static void handleDeleteById(HttpExchange exchange, String id) throws IOException {
        synchronized (tasks) {
            boolean removed = tasks.removeIf(x -> id.equals(x.id));
            if (!removed) {
                sendJson(exchange, 404, Collections.singletonMap("error", "Task not found"));
                return;
            }
            persistToDisk();
            sendJson(exchange, 200, Collections.singletonMap("status", "deleted"));
        }
    }

    // GET /api/tasks/{id}
    private static void handleGetById(HttpExchange exchange, String id) throws IOException {
        synchronized (tasks) {
            Optional<Task> found = tasks.stream().filter(x -> id.equals(x.id)).findFirst();
            if (found.isEmpty()) {
                sendJson(exchange, 404, Collections.singletonMap("error", "Task not found"));
                return;
            }
            sendJson(exchange, 200, found.get());
        }
    }

    // DELETE /api/tasks
    private static void handleDeleteAll(HttpExchange exchange) throws IOException {
        synchronized (tasks) {
            tasks.clear();
            persistToDisk();
            sendJson(exchange, 200, Collections.singletonMap("status", "cleared"));
        }
    }

    // ---------- Helpers ----------

    private static void sendJson(HttpExchange exchange, int code, Object obj) throws IOException {
        String body = gson.toJson(obj);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        addCORS(exchange);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void addCORS(HttpExchange exchange) {
        // Accept any origin for development; change in production
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
    }

    // Simple persistence: save tasks to tasks.json in working directory
    private static void persistToDisk() {
        synchronized (tasks) {
            try (Writer w = new FileWriter(STORAGE_FILE, StandardCharsets.UTF_8)) {
                gson.toJson(tasks, w);
            } catch (IOException e) {
                System.err.println("Failed to persist tasks: " + e.getMessage());
            }
        }
    }

    private static void loadFromDisk() {
        File f = new File(STORAGE_FILE);
        if (!f.exists()) return;
        try (Reader r = new FileReader(f, StandardCharsets.UTF_8)) {
            Type listType = new TypeToken<List<Task>>() {}.getType();
            List<Task> loaded = gson.fromJson(r, listType);
            if (loaded != null) {
                tasks.clear();
                tasks.addAll(loaded);
                System.out.println("Loaded " + tasks.size() + " tasks from disk.");
            }
        } catch (Exception e) {
            System.err.println("Failed to load tasks from disk: " + e.getMessage());
        }
    }
}
