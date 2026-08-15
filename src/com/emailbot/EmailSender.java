package com.emailbot;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

public class EmailSender {
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 15_000;
    private static final String SMTP_HOST = getEnv("SMTP_HOST", "smtp-relay.brevo.com");
    private static final int SMTP_PORT = Integer.parseInt(getEnv("SMTP_PORT", "587"));
    private static final String EMAIL_USER = getEnv("EMAIL_USER", "baddamvishwa445@gmail.com");
    private static final String EMAIL_PASS = getEnv("EMAIL_PASS", "");
    private static final String SENDER_EMAIL = getEnv("BREVO_SENDER_EMAIL", "baddamvishwa445@gmail.com");
    private static final String BREVO_API_KEY = getEnv("BREVO_API_KEY", "");
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
            .build();

    public static boolean sendOTPEmail(String recipientEmail, String userName, String otp) {
        if (!isValidEmail(recipientEmail)) {
            System.err.println("[EMAIL ERROR] Refusing to send to an invalid email address: " + recipientEmail);
            return false;
        }

        String key = BREVO_API_KEY.trim();

        // 1. If key is a Brevo SMTP key (starts with xsmtpsib-)
        if (key.startsWith("xsmtpsib-")) {
            System.out.println("[EMAIL LOG] Sending via Brevo SMTP Relay (smtp-relay.brevo.com:587)...");
            boolean sent = sendViaSmtpHost("smtp-relay.brevo.com", 587, SENDER_EMAIL, key, recipientEmail, userName, otp);
            if (sent) return true;
            System.err.println("[EMAIL WARN] Brevo SMTP relay failed.");
        }

        // 2. If key is a Brevo REST API key (starts with xkeysib-)
        if (key.startsWith("xkeysib-")) {
            System.out.println("[EMAIL LOG] Sending via Brevo REST API...");
            boolean sent = sendViaBrevoApi(recipientEmail, userName, otp);
            if (sent) return true;
            System.err.println("[EMAIL WARN] Brevo REST API failed.");
        }

        // 3. Try standard custom SMTP (Gmail, Brevo, etc.)
        if (!EMAIL_USER.isBlank() && !EMAIL_PASS.isBlank()) {
            System.out.println("[EMAIL LOG] Sending via SMTP (" + SMTP_HOST + ":" + SMTP_PORT + ")...");
            return sendViaSmtpHost(SMTP_HOST, SMTP_PORT, EMAIL_USER, EMAIL_PASS, recipientEmail, userName, otp);
        }

        System.err.println("[EMAIL ERROR] No valid email transport succeeded.");
        return false;
    }

    private static boolean sendViaSmtpHost(String host, int port, String user, String pass, String recipientEmail, String userName, String otp) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));

            if (requireResponse(reader, "220", "SMTP greeting") == null) return false;

            sendCommand(writer, "EHLO localhost");
            if (requireResponse(reader, "250", "EHLO") == null) return false;

            sendCommand(writer, "STARTTLS");
            if (requireResponse(reader, "220", "STARTTLS") == null) return false;

            SSLSocketFactory sslFactory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            try (SSLSocket sslSocket = (SSLSocket) sslFactory.createSocket(socket, host, port, true)) {
                sslSocket.setSoTimeout(READ_TIMEOUT_MS);
                sslSocket.startHandshake();

                BufferedReader tlsReader = new BufferedReader(new InputStreamReader(sslSocket.getInputStream(), StandardCharsets.UTF_8));
                BufferedWriter tlsWriter = new BufferedWriter(new OutputStreamWriter(sslSocket.getOutputStream(), StandardCharsets.UTF_8));

                sendCommand(tlsWriter, "EHLO localhost");
                if (requireResponse(tlsReader, "250", "TLS EHLO") == null) return false;

                sendCommand(tlsWriter, "AUTH LOGIN");
                if (requireResponse(tlsReader, "334", "AUTH LOGIN") == null) return false;

                sendCommand(tlsWriter, Base64.getEncoder().encodeToString(user.getBytes(StandardCharsets.UTF_8)));
                if (requireResponse(tlsReader, "334", "SMTP username") == null) return false;

                sendCommand(tlsWriter, Base64.getEncoder().encodeToString(pass.getBytes(StandardCharsets.UTF_8)));
                if (requireResponse(tlsReader, "235", "SMTP authentication") == null) return false;

                sendCommand(tlsWriter, "MAIL FROM:<" + user + ">");
                if (requireResponse(tlsReader, "250", "MAIL FROM") == null) return false;

                sendCommand(tlsWriter, "RCPT TO:<" + recipientEmail + ">");
                if (requireResponse(tlsReader, "250", "RCPT TO") == null) return false;

                sendCommand(tlsWriter, "DATA");
                if (requireResponse(tlsReader, "354", "DATA") == null) return false;

                String subject = "Zenvego Verification Code: " + otp;
                String bodyHtml = buildHtmlBody(userName, otp);

                tlsWriter.write("From: Zenvego <" + user + ">\r\n");
                tlsWriter.write("To: <" + recipientEmail + ">\r\n");
                tlsWriter.write("Subject: " + subject + "\r\n");
                tlsWriter.write("MIME-Version: 1.0\r\n");
                tlsWriter.write("Content-Type: text/html; charset=UTF-8\r\n");
                tlsWriter.write("\r\n");
                tlsWriter.write(bodyHtml);
                tlsWriter.write("\r\n.\r\n");
                tlsWriter.flush();

                if (requireResponse(tlsReader, "250", "message delivery") != null) {
                    System.out.println("[SUCCESS] Email accepted by " + host + " for delivery to " + recipientEmail);
                    sendCommand(tlsWriter, "QUIT");
                    return true;
                }

                sendCommand(tlsWriter, "QUIT");
                return false;
            }
        } catch (Exception e) {
            System.err.println("[SMTP ERROR] Sending via SMTP (" + host + ") failed: " + e.getMessage());
            return false;
        }
    }

    private static boolean sendViaBrevoApi(String recipientEmail, String userName, String otp) {
        try {
            String html = buildHtmlBody(userName, otp);
            String payload = """
                {"sender":{"name":"Zenvego","email":"%s"},
                 "to":[{"email":"%s"}],
                 "subject":"Zenvego Verification Code: %s",
                 "htmlContent":"%s"}
                """.formatted(
                    jsonEscape(SENDER_EMAIL),
                    jsonEscape(recipientEmail),
                    jsonEscape(otp),
                    jsonEscape(html)
                );

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.brevo.com/v3/smtp/email"))
                .header("accept", "application/json")
                .header("content-type", "application/json")
                .header("api-key", BREVO_API_KEY)
                .timeout(Duration.ofMillis(READ_TIMEOUT_MS))
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();

            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                System.out.println("[SUCCESS] Email delivered to " + recipientEmail + " via Brevo REST API!");
                return true;
            }

            System.err.println("[ERROR] Brevo REST API rejected email (" + response.statusCode() + "): " + response.body());
            return false;
        } catch (Exception e) {
            System.err.println("[ERROR] Brevo REST API request failed: " + e.getMessage());
            return false;
        }
    }

    private static String buildHtmlBody(String userName, String otp) {
        String template = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="UTF-8">
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <title>Zenvego Verification Code</title>
              <style>
                body { margin: 0; padding: 0; background-color: #f4f8f5; font-family: 'Segoe UI', Arial, sans-serif; color: #2d3748; }
                .email-container { max-width: 500px; margin: 40px auto; background: #ffffff; border-radius: 16px; overflow: hidden; box-shadow: 0 10px 25px rgba(13, 92, 70, 0.08); border: 1px solid #e2ece5; }
                .header { background: #0d5c46; padding: 30px 20px; text-align: center; color: #ffffff; }
                .logo-title { font-size: 28px; font-weight: 800; letter-spacing: 1px; margin: 0; text-transform: uppercase; }
                .tagline { font-size: 12px; opacity: 0.85; margin-top: 4px; letter-spacing: 0.5px; }
                .content { padding: 35px 30px; text-align: center; }
                .greeting { font-size: 18px; font-weight: 600; margin-bottom: 8px; color: #1a202c; }
                .subtext { font-size: 14px; color: #718096; margin-bottom: 25px; }
                .code-box { background-color: #edf7f3; border: 2px dashed #0d5c46; border-radius: 12px; padding: 18px 10px; margin: 20px 0; }
                .code { font-size: 32px; font-weight: 700; letter-spacing: 8px; color: #0d5c46; margin: 0; }
                .expiry { font-size: 13px; color: #e53e3e; font-weight: 500; margin-top: 20px; }
                .footer { background-color: #f8faf9; padding: 20px; text-align: center; font-size: 12px; color: #a0aec0; border-top: 1px solid #edf2f7; }
              </style>
            </head>
            <body>
              <div class="email-container">
                <div class="header">
                  <div class="logo-title">ZENVEGO</div>
                  <div class="tagline">Neighborhood Ecosystem Platform</div>
                </div>
                <div class="content">
                  <div class="greeting">Hello {{userName}},</div>
                  <div class="subtext">Use the following 6-digit verification code to complete your security authentication.</div>
                  <div class="code-box">
                    <div class="code">{{otp}}</div>
                  </div>
                  <div class="expiry">Code expires in 10 minutes. Do not share this code.</div>
                </div>
                <div class="footer">
                  &copy; Zenvego. All rights reserved.<br>If you didn't request this code, please ignore this email.
                </div>
              </div>
            </body>
            </html>
            """;
        return template.replace("{{userName}}", htmlEscape(userName)).replace("{{otp}}", otp);
    }

    private static void sendCommand(BufferedWriter writer, String cmd) throws IOException {
        writer.write(cmd + "\r\n");
        writer.flush();
    }

    private static String requireResponse(BufferedReader reader, String expectedCode, String step) throws IOException {
        String line;
        String lastLine = null;
        while ((line = reader.readLine()) != null) {
            lastLine = line;
            if (line.length() < 4 || line.charAt(3) == ' ') break;
        }
        if (lastLine == null || !lastLine.startsWith(expectedCode)) {
            System.err.println("[SMTP ERROR] " + step + " failed: " + lastLine);
            return null;
        }
        return lastLine;
    }

    private static String jsonEscape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r");
    }

    private static String htmlEscape(String value) {
        if (value == null || value.isBlank()) return "Zenvego user";
        return value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;");
    }

    private static boolean isValidEmail(String value) {
        return value != null && value.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    }

    private static String getEnv(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}