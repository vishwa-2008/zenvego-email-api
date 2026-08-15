package com.emailbot;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import org.bson.Document;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class OTPServer {
    private static final int PORT = Integer.parseInt(getEnv("PORT", "8080"));
    private static final String ALLOWED_ORIGINS = getEnv("ALLOWED_ORIGINS", "*");

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);

        server.createContext("/", new StaticFileHandler());
        server.createContext("/send-otp", new SendOTPHandler());
        server.createContext("/verify-otp", new VerifyOTPHandler());
        server.createContext("/register-user", new RegisterUserHandler());
        server.createContext("/user", new GetUserHandler());
        server.createContext("/health", new HealthHandler());

        server.setExecutor(null);
        server.start();
        System.out.println("==================================================");
        System.out.println("🌱 Zenvego OTP Server running on port " + PORT);
        System.out.println("   Allowed origins: " + ALLOWED_ORIGINS);
        System.out.println("Ready to handle OTP requests...");
        System.out.println("==================================================");
    }

    static class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (handleCorsPreflight(exchange)) return;
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("status", "ok");
                body.put("mongoAvailable", MongoDBConnection.isAvailable());
                sendJsonResponse(exchange, body, 200);
            } catch (Exception e) {
                sendErrorResponse(exchange, "Health check error: " + e.getMessage(), 500);
            }
        }
    }

    static class SendOTPHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (handleCorsPreflight(exchange)) return;

                if (!"POST".equals(exchange.getRequestMethod())) {
                    sendErrorResponse(exchange, "Method not allowed", 405);
                    return;
                }

                Map<String, String> params = parseRequestBody(exchange);
                String email = params.get("email");
                String userName = params.get("username");
                if (!isPresent(userName)) {
                    if (isPresent(email) && email.contains("@")) {
                        userName = email.substring(0, email.indexOf('@'));
                    } else {
                        userName = "Valued Customer";
                    }
                }

                if (isPresent(email)) {
                    EmailOTPService.generateAndSendOTP(email, userName);
                    sendOkResponse(exchange, "OTP sent to your email", null);
                } else {
                    sendErrorResponse(exchange, "Email required", 400);
                }
            } catch (Exception e) {
                System.err.println("[SendOTPHandler Exception] " + e.getMessage());
                e.printStackTrace();
                sendErrorResponse(exchange, "Internal server error: " + e.getMessage(), 500);
            }
        }
    }

    static class VerifyOTPHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (handleCorsPreflight(exchange)) return;

                if (!"POST".equals(exchange.getRequestMethod())) {
                    sendErrorResponse(exchange, "Method not allowed", 405);
                    return;
                }

                Map<String, String> params = parseRequestBody(exchange);
                String email = params.get("email");
                String otp = params.get("otp");

                if (isPresent(email) && isPresent(otp)) {
                    boolean isValid = EmailOTPService.verifyOTP(email, otp);
                    if (isValid) {
                        Document userDoc = null;
                        try {
                            userDoc = UserService.findOrCreateUser(email);
                        } catch (Exception e) {
                            System.err.println("[VerifyOTP] Could not fetch user: " + e.getMessage());
                        }
                        Map<String, Object> extra = new LinkedHashMap<>();
                        if (userDoc != null) {
                            extra.put("user", sanitizeUserDoc(userDoc));
                        }
                        sendOkResponse(exchange, "OTP verified successfully", extra);
                    } else {
                        sendErrorResponse(exchange, "Invalid or expired OTP", 400);
                    }
                } else {
                    sendErrorResponse(exchange, "Email and OTP required", 400);
                }
            } catch (Exception e) {
                System.err.println("[VerifyOTPHandler Exception] " + e.getMessage());
                e.printStackTrace();
                sendErrorResponse(exchange, "Internal server error: " + e.getMessage(), 500);
            }
        }
    }

    static class RegisterUserHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (handleCorsPreflight(exchange)) return;

                if (!"POST".equals(exchange.getRequestMethod())) {
                    sendErrorResponse(exchange, "Method not allowed", 405);
                    return;
                }

                Map<String, String> params = parseRequestBody(exchange);
                String email = params.get("email");
                if (!isPresent(email)) {
                    sendErrorResponse(exchange, "Email required", 400);
                    return;
                }

                Map<String, Object> updates = new LinkedHashMap<>();
                putIfPresent(updates, "fullName", params.get("fullName"));
                putIfPresent(updates, "phone", params.get("phone"));
                putIfPresent(updates, "role", params.get("role"));
                putIfPresent(updates, "avatar", params.get("avatar"));
                putIfPresent(updates, "banner", params.get("banner"));

                boolean ok = UserService.updateUserProfile(email, updates);
                Document userDoc = UserService.getUserByEmail(email);
                Map<String, Object> extra = new LinkedHashMap<>();
                if (userDoc != null) extra.put("user", sanitizeUserDoc(userDoc));
                sendOkResponse(exchange, "Profile updated", extra);
            } catch (Exception e) {
                System.err.println("[RegisterUserHandler Exception] " + e.getMessage());
                e.printStackTrace();
                sendErrorResponse(exchange, "Internal server error: " + e.getMessage(), 500);
            }
        }
    }

    static class GetUserHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (handleCorsPreflight(exchange)) return;

                if (!"GET".equals(exchange.getRequestMethod())) {
                    sendErrorResponse(exchange, "Method not allowed", 405);
                    return;
                }

                String rawQuery = exchange.getRequestURI().getRawQuery();
                Map<String, String> qs = parseQuery(rawQuery == null ? "" : rawQuery);
                String email = qs.get("email");

                if (!isPresent(email)) {
                    sendErrorResponse(exchange, "Email query parameter required", 400);
                    return;
                }

                Document userDoc = UserService.getUserByEmail(email);
                if (userDoc == null) {
                    sendErrorResponse(exchange, "User not found", 404);
                    return;
                }

                Map<String, Object> extra = new LinkedHashMap<>();
                extra.put("user", sanitizeUserDoc(userDoc));
                sendOkResponse(exchange, "User found", extra);
            } catch (Exception e) {
                sendErrorResponse(exchange, "Internal server error: " + e.getMessage(), 500);
            }
        }
    }

    private static Map<String, Object> sanitizeUserDoc(Document doc) {
        Map<String, Object> out = new LinkedHashMap<>();
        Object id = doc.get("_id");
        if (id != null) out.put("id", id.toString());
        for (String key : Arrays.asList("email", "fullName", "phone", "role", "avatar", "banner", "address", "createdAt", "updatedAt")) {
            if (doc.containsKey(key)) out.put(key, doc.get(key));
        }
        return out;
    }

    private static void putIfPresent(Map<String, Object> m, String key, String val) {
        if (isPresent(val)) m.put(key, val.trim());
    }

    private static Map<String, String> parseRequestBody(HttpExchange exchange) throws IOException {
        byte[] bodyBytes = exchange.getRequestBody().readAllBytes();
        String body = new String(bodyBytes, StandardCharsets.UTF_8);
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null) contentType = "";
        contentType = contentType.toLowerCase();

        if (contentType.contains("application/json")) {
            Map<String, String> result = new HashMap<>();
            if (body.isBlank()) return result;
            try {
                Document doc = Document.parse(body);
                flattenDoc("", doc, result);
            } catch (Exception e) {
                System.err.println("[OTPServer] JSON parse failed: " + e.getMessage());
            }
            return result;
        }

        return parseQuery(body);
    }

    private static void flattenDoc(String prefix, Document doc, Map<String, String> out) {
        for (Map.Entry<String, Object> e : doc.entrySet()) {
            String key = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            Object val = e.getValue();
            if (val == null) continue;
            if (val instanceof Document) {
                flattenDoc(key, (Document) val, out);
                out.put(key, ((Document) val).toJson());
            } else if (val instanceof Map) {
                out.put(key, val.toString());
            } else {
                out.put(key, val.toString());
            }
        }
    }

    private static void sendOkResponse(HttpExchange exchange, String message, Map<String, Object> extra) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "success");
        if (message != null) body.put("message", message);
        if (extra != null) body.putAll(extra);
        sendJsonResponse(exchange, body, 200);
    }

    private static void sendErrorResponse(HttpExchange exchange, String message, int code) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "error");
        body.put("message", message);
        sendJsonResponse(exchange, body, code);
    }

    private static void sendJsonResponse(HttpExchange exchange, Object bodyObj, int statusCode) throws IOException {
        String json;
        if (bodyObj instanceof Map || bodyObj instanceof List) {
            json = toJson(bodyObj);
        } else if (bodyObj instanceof Document) {
            json = ((Document) bodyObj).toJson();
        } else {
            json = String.valueOf(bodyObj);
        }
        applyCorsHeaders(exchange);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        OutputStream os = exchange.getResponseBody();
        os.write(bytes);
        os.close();
    }

    private static String toJson(Object o) {
        StringBuilder sb = new StringBuilder();
        writeJson(sb, o);
        return sb.toString();
    }

    private static void writeJson(StringBuilder sb, Object o) {
        if (o == null) { sb.append("null"); return; }
        if (o instanceof String) {
            sb.append('"').append(jsonEscape((String) o)).append('"');
        } else if (o instanceof Number || o instanceof Boolean) {
            sb.append(o.toString());
        } else if (o instanceof Date) {
            sb.append('"').append(((Date) o).toInstant().toString()).append('"');
        } else if (o instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append('"').append(jsonEscape(String.valueOf(e.getKey()))).append('"').append(':');
                writeJson(sb, e.getValue());
            }
            sb.append('}');
        } else if (o instanceof List || o.getClass().isArray()) {
            sb.append('[');
            boolean first = true;
            Iterable<?> it = (o instanceof List) ? (List<?>) o : Arrays.asList((Object[]) o);
            for (Object item : it) {
                if (!first) sb.append(',');
                first = false;
                writeJson(sb, item);
            }
            sb.append(']');
        } else if (o instanceof Document) {
            writeJson(sb, new LinkedHashMap<>((Document) o));
        } else {
            sb.append('"').append(jsonEscape(String.valueOf(o))).append('"');
        }
    }

    private static String jsonEscape(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> params = new HashMap<>();
        if (query != null && !query.isBlank()) {
            String[] pairs = query.trim().split("[&\\r\\n]+");
            for (String pair : pairs) {
                String[] keyValue = pair.split("=", 2);
                if (keyValue.length == 2) {
                    String key = URLDecoder.decode(keyValue[0].trim(), StandardCharsets.UTF_8);
                    String value = URLDecoder.decode(keyValue[1].trim(), StandardCharsets.UTF_8);
                    params.put(key, value);
                }
            }
        }
        return params;
    }

    private static void applyCorsHeaders(HttpExchange exchange) {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        String header = (origin != null && !origin.isBlank()) ? origin : "*";
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", header);
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type,Authorization");
        exchange.getResponseHeaders().set("Access-Control-Allow-Credentials", "true");
        exchange.getResponseHeaders().set("Vary", "Origin");
    }

    private static boolean handleCorsPreflight(HttpExchange exchange) throws IOException {
        if (!"OPTIONS".equals(exchange.getRequestMethod())) {
            return false;
        }
        applyCorsHeaders(exchange);
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
        return true;
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    private static String getEnv(String name, String defaultValue) {
        String v = System.getenv(name);
        if (v != null && !v.isBlank()) return v;
        return defaultValue;
    }

    static class StaticFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendErrorResponse(exchange, "Method not allowed", 405);
                return;
            }

            String pathStr = exchange.getRequestURI().getPath();
            if (pathStr.equals("/")) {
                pathStr = "/index.html";
            }

            if (pathStr.startsWith("/send-otp") || pathStr.startsWith("/verify-otp") ||
                pathStr.startsWith("/register-user") || pathStr.startsWith("/user") ||
                pathStr.startsWith("/health")) {
                sendErrorResponse(exchange, "Not found", 404);
                return;
            }

            Path webDir = Path.of("web").toAbsolutePath().normalize();
            Path filePath = webDir.resolve(pathStr.substring(1)).normalize();

            if (!filePath.startsWith(webDir) || !Files.exists(filePath) || Files.isDirectory(filePath)) {
                sendErrorResponse(exchange, "File not found", 404);
                return;
            }

            String contentType = getContentType(filePath.toString());
            byte[] fileBytes = Files.readAllBytes(filePath);
            applyCorsHeaders(exchange);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(200, fileBytes.length);
            OutputStream os = exchange.getResponseBody();
            os.write(fileBytes);
            os.close();
        }

        private String getContentType(String path) {
            if (path.endsWith(".html")) return "text/html; charset=UTF-8";
            if (path.endsWith(".css")) return "text/css; charset=UTF-8";
            if (path.endsWith(".js")) return "application/javascript; charset=UTF-8";
            if (path.endsWith(".png")) return "image/png";
            if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image/jpeg";
            if (path.endsWith(".svg")) return "image/svg+xml";
            if (path.endsWith(".ico")) return "image/x-icon";
            return "application/octet-stream";
        }
    }
}
