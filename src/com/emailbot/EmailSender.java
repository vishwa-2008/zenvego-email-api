package com.emailbot;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.*;
import java.net.URI;
import java.net.Socket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class EmailSender {
    private static final String SMTP_HOST = getEnv("SMTP_HOST", "smtp.gmail.com");
    private static final int SMTP_PORT = Integer.parseInt(getEnv("SMTP_PORT", "587"));
    private static final String EMAIL_USER = getEnv("EMAIL_USER", "vishwabaddam@gmail.com");
    private static final String EMAIL_PASS = getEnv("EMAIL_PASS", "hmezfepdhrbnydjl");
    private static final String BREVO_API_KEY = getEnv("BREVO_API_KEY", "");
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    public static boolean sendOTPEmail(String recipientEmail, String userName, String otp) {
        if (!BREVO_API_KEY.isBlank()) {
            boolean brevoSent = sendViaBrevo(recipientEmail, userName, otp);
            if (brevoSent) return true;
            System.err.println("[OTP WARN] Brevo delivery failed; trying Gmail SMTP fallback...");
        }

        if (!EMAIL_USER.isBlank() && !EMAIL_PASS.isBlank()) {
            return sendViaSmtp(recipientEmail, userName, otp);
        }

        System.err.println("[ERROR] Neither Brevo API key nor SMTP credentials (EMAIL_USER/EMAIL_PASS) are configured.");
        return false;
    }

    private static boolean sendViaSmtp(String recipientEmail, String userName, String otp) {
        try (Socket socket = new Socket(SMTP_HOST, SMTP_PORT)) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));

            String line = reader.readLine();
            if (line == null || !line.startsWith("220")) return false;

            sendCommand(writer, "EHLO localhost");
            readResponse(reader);

            sendCommand(writer, "STARTTLS");
            line = reader.readLine();
            if (line == null || !line.startsWith("220")) return false;

            SSLSocketFactory sslFactory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            SSLSocket sslSocket = (SSLSocket) sslFactory.createSocket(socket, SMTP_HOST, SMTP_PORT, true);
            sslSocket.startHandshake();

            BufferedReader tlsReader = new BufferedReader(new InputStreamReader(sslSocket.getInputStream(), StandardCharsets.UTF_8));
            BufferedWriter tlsWriter = new BufferedWriter(new OutputStreamWriter(sslSocket.getOutputStream(), StandardCharsets.UTF_8));

            sendCommand(tlsWriter, "EHLO localhost");
            readResponse(tlsReader);

            sendCommand(tlsWriter, "AUTH LOGIN");
            line = tlsReader.readLine();
            if (line == null || !line.startsWith("334")) return false;

            sendCommand(tlsWriter, Base64.getEncoder().encodeToString(EMAIL_USER.getBytes(StandardCharsets.UTF_8)));
            line = tlsReader.readLine();
            if (line == null || !line.startsWith("334")) return false;

            sendCommand(tlsWriter, Base64.getEncoder().encodeToString(EMAIL_PASS.getBytes(StandardCharsets.UTF_8)));
            line = tlsReader.readLine();
            if (line == null || !line.startsWith("235")) {
                System.err.println("[SMTP ERROR] Authentication failed: " + line);
                return false;
            }

            sendCommand(tlsWriter, "MAIL FROM:<" + EMAIL_USER + ">");
            line = tlsReader.readLine();
            if (line == null || !line.startsWith("250")) return false;

            sendCommand(tlsWriter, "RCPT TO:<" + recipientEmail + ">");
            line = tlsReader.readLine();
            if (line == null || !line.startsWith("250")) return false;

            sendCommand(tlsWriter, "DATA");
            line = tlsReader.readLine();
            if (line == null || !line.startsWith("354")) return false;

            String subject = "Zenvego Verification Code: " + otp;
            String bodyHtml = buildHtmlBody(userName, otp);

            tlsWriter.write("From: Zenvego <" + EMAIL_USER + ">\r\n");
            tlsWriter.write("To: <" + recipientEmail + ">\r\n");
            tlsWriter.write("Subject: " + subject + "\r\n");
            tlsWriter.write("MIME-Version: 1.0\r\n");
            tlsWriter.write("Content-Type: text/html; charset=UTF-8\r\n");
            tlsWriter.write("\r\n");
            tlsWriter.write(bodyHtml);
            tlsWriter.write("\r\n.\r\n");
            tlsWriter.flush();

            line = tlsReader.readLine();
            if (line != null && line.startsWith("250")) {
                System.out.println("[SUCCESS] Email delivered to " + recipientEmail + " via Gmail SMTP!");
                return true;
            }

            sendCommand(tlsWriter, "QUIT");
            return false;
        } catch (Exception e) {
            System.err.println("[SMTP ERROR] Sending via SMTP failed: " + e.getMessage());
            return false;
        }
    }

    private static boolean sendViaBrevo(String recipientEmail, String userName, String otp) {
        try {
            String html = buildHtmlBody(userName, otp);
            String payload = """
                {"sender":{"name":"Zenvego","email":"%s"},
                 "to":[{"email":"%s"}],
                 "subject":"Zenvego Verification Code: %s",
                 "htmlContent":"%s"}
                """.formatted(
                    jsonEscape(EMAIL_USER),
                    jsonEscape(recipientEmail),
                    jsonEscape(otp),
                    jsonEscape(html)
                );

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.brevo.com/v3/smtp/email"))
                .header("accept", "application/json")
                .header("content-type", "application/json")
                .header("api-key", BREVO_API_KEY)
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();

            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                System.out.println("[SUCCESS] Email delivered to " + recipientEmail + " via Brevo API!");
                return true;
            }

            System.err.println("[ERROR] Brevo rejected email (" + response.statusCode() + "): " + response.body());
            return false;
        } catch (Exception e) {
            System.err.println("[ERROR] Brevo request failed: " + e.getMessage());
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

    private static void readResponse(BufferedReader reader) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.length() >= 4 && line.charAt(3) == ' ') break;
        }
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

    private static String getEnv(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}