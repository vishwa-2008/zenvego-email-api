package com.emailbot;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

public class EmailSender {
    private static final String BREVO_API_KEY = getEnv("BREVO_API_KEY", "");
    private static final String SENDER_EMAIL = getEnv("BREVO_SENDER_EMAIL", "baddamvishwa445@gmail.com");
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    public static boolean sendOTPEmail(String recipientEmail, String userName, String otp) {
        if (BREVO_API_KEY.isBlank() || SENDER_EMAIL.isBlank()) {
            System.err.println("[ERROR] BREVO_API_KEY or BREVO_SENDER_EMAIL is missing.");
            return false;
        }

        try {
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

            String html = template.replace("{{userName}}", htmlEscape(userName)).replace("{{otp}}", otp);

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
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();

            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                System.out.println("[SUCCESS] OTP email sent to: " + recipientEmail);
                return true;
            }

            System.err.println("[ERROR] Brevo rejected email (" + response.statusCode() + "): " + response.body());
            return false;
        } catch (Exception e) {
            System.err.println("[ERROR] Brevo email request failed: " + e.getMessage());
            e.printStackTrace();
            return false;
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
