package com.emailbot;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

public class EmailSender {
    private static final String BREVO_API_KEY = getEnv("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", "");
    private static final String SENDER_EMAIL = getEnv("vishwabaddam@gmail.com", "30");
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    public static boolean sendOTPEmail(String recipientEmail, String userName, String otp) {
        if (BREVO_API_KEY.isBlank() || SENDER_EMAIL.isBlank()) {
            System.err.println("[ERROR] BREVO_API_KEY or BREVO_SENDER_EMAIL is missing.");
            return false;
        }

        String html = """
            <html><body style="font-family:Arial,sans-serif">
              <h2>Zenvego Verification Code</h2>
              <p>Hello %s,</p>
              <p>Your 6-digit verification code is:</p>
              <h1 style="letter-spacing:8px;color:#0d5c46">%s</h1>
              <p>This code expires in 10 minutes. Do not share it.</p>
            </body></html>
            """.formatted(htmlEscape(userName), otp);

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

        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.brevo.com/v3/smtp/email"))
                .header("accept", "application/json")
                .header("content-type", "application/json")
                .header("api-key", BREVO_API_KEY)
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();

            HttpResponse<String> response =
                HTTP.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                System.out.println("[SUCCESS] OTP email sent to: " + recipientEmail);
                return true;
            }

            System.err.println("[ERROR] Brevo rejected email: " + response.body());
            return false;
        } catch (Exception e) {
            System.err.println("[ERROR] Brevo email request failed: " + e.getMessage());
            return false;
        }
    }

    private static String jsonEscape(String value) {
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