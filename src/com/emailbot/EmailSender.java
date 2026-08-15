package com.emailbot;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class EmailSender {
    private static final String SMTP_HOST = getEnv("SMTP_HOST", "smtp.gmail.com");
    private static final int SMTP_PORT = Integer.parseInt(getEnv("SMTP_PORT", "587"));
    private static final String EMAIL_FROM = getEnv("EMAIL_USER", "");
    private static final String EMAIL_PASSWORD = getEnv("EMAIL_PASS", "");
    private static final int SOCKET_TIMEOUT_MS = 30_000;

    public static boolean sendOTPEmail(String recipientEmail, String userName, String otp) {
        if (EMAIL_FROM == null || EMAIL_FROM.isBlank() || EMAIL_PASSWORD == null || EMAIL_PASSWORD.isBlank()) {
            System.out.println("[WARN] SMTP credentials not configured (EMAIL_USER / EMAIL_PASS env vars missing).");
            System.out.println("[DEV MODE] OTP code for " + recipientEmail + " >>> " + otp + " <<<");
            return true;
        }

        String subject = "Zenvego Verification Code: " + otp;
        String htmlBody = buildZenvegoHtmlEmail(userName, otp);

        try {
            sendSmtpMail(recipientEmail, subject, htmlBody);
            System.out.println("[SUCCESS] Zenvego OTP email sent to: " + recipientEmail);
            return true;
        } catch (Exception e) {
            System.err.println("[ERROR] Failed to send Zenvego OTP email: " + e.getMessage());
            e.printStackTrace(System.err);
            return false;
        }
    }

    private static void sendSmtpMail(String recipientEmail, String subject, String htmlBody) throws IOException {
        Socket plainSocket = new Socket(SMTP_HOST, SMTP_PORT);
        plainSocket.setSoTimeout(SOCKET_TIMEOUT_MS);

        SmtpConnection smtp = new SmtpConnection(plainSocket);
        smtp.expect(220);
        smtp.command("EHLO localhost", 250);
        smtp.command("STARTTLS", 220);

        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket tlsSocket = (SSLSocket) factory.createSocket(plainSocket, SMTP_HOST, SMTP_PORT, false);
        tlsSocket.setSoTimeout(SOCKET_TIMEOUT_MS);
        tlsSocket.startHandshake();

        smtp = new SmtpConnection(tlsSocket);
        smtp.command("EHLO localhost", 250);
        smtp.command("AUTH LOGIN", 334);
        smtp.command(base64(EMAIL_FROM), 334);
        smtp.command(base64(EMAIL_PASSWORD), 235);
        smtp.command("MAIL FROM:<" + EMAIL_FROM + ">", 250);
        smtp.command("RCPT TO:<" + recipientEmail + ">", 250);
        smtp.command("DATA", 354);

        String message = buildMimeMessage(recipientEmail, subject, htmlBody);
        smtp.data(message);

        try { smtp.send("QUIT"); } catch (IOException ignored) {}

        plainSocket.close();
    }

    private static String buildMimeMessage(String recipientEmail, String subject, String htmlBody) {
        return "From: Zenvego <" + EMAIL_FROM + ">\r\n"
                + "To: " + recipientEmail + "\r\n"
                + "Subject: " + subject + "\r\n"
                + "MIME-Version: 1.0\r\n"
                + "Content-Type: text/html; charset=UTF-8\r\n"
                + "\r\n"
                + htmlBody.replace("\n", "\r\n");
    }

    private static String buildZenvegoHtmlEmail(String userName, String otp) {
        String displayName = (userName != null && !userName.isBlank()) ? userName : "Valued Customer";
        
        return "<!DOCTYPE html>\n"
                + "<html>\n"
                + "<head>\n"
                + "  <meta charset=\"UTF-8\">\n"
                + "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n"
                + "  <title>Zenvego OTP Verification</title>\n"
                + "</head>\n"
                + "<body style=\"margin: 0; padding: 0; background-color: #f2f7f4; font-family: 'Segoe UI', Arial, sans-serif; -webkit-font-smoothing: antialiased;\">\n"
                + "  <table border=\"0\" cellpadding=\"0\" cellspacing=\"0\" width=\"100%\" style=\"max-width: 600px; margin: 30px auto; background-color: #ffffff; border-radius: 16px; overflow: hidden; box-shadow: 0 10px 30px rgba(13,92,70,0.12);\">\n"
                + "    <!-- Header Banner -->\n"
                + "    <tr>\n"
                + "      <td style=\"background: linear-gradient(135deg, #0d5c46 0%, #158a69 100%); padding: 36px 30px; text-align: center;\">\n"
                + "        <div style=\"display: inline-block; background-color: rgba(255,255,255,0.15); padding: 12px 24px; border-radius: 50px; margin-bottom: 12px;\">\n"
                + "          <span style=\"font-size: 26px; font-weight: 800; color: #ffffff; letter-spacing: 1px;\">🌱 Zenvego</span>\n"
                + "        </div>\n"
                + "        <p style=\"margin: 0; color: #bde7d9; font-size: 14px; font-weight: 500; letter-spacing: 0.5px;\">Hyperlocal Fresh Produce Marketplace</p>\n"
                + "      </td>\n"
                + "    </tr>\n"
                + "    <!-- Body Content -->\n"
                + "    <tr>\n"
                + "      <td style=\"padding: 40px 36px; text-align: center;\">\n"
                + "        <h2 style=\"margin: 0 0 12px 0; color: #0d5c46; font-size: 24px; font-weight: 700;\">Email Verification Code</h2>\n"
                + "        <p style=\"margin: 0 0 28px 0; color: #556b63; font-size: 15px; line-height: 1.6;\">\n"
                + "          Hello <strong>" + displayName + "</strong>,<br>\n"
                + "          Use the verification code below to sign in to your <strong>Zenvego</strong> account and start shopping fresh local produce.\n"
                + "        </p>\n"
                + "        \n"
                + "        <!-- OTP Card -->\n"
                + "        <div style=\"background: linear-gradient(135deg, #e8f5f1 0%, #d4ede5 100%); border: 2px dashed #0d5c46; border-radius: 14px; padding: 24px; margin: 0 auto 30px auto; max-width: 320px;\">\n"
                + "          <span style=\"font-size: 12px; font-weight: 700; color: #0d5c46; text-transform: uppercase; letter-spacing: 2px; display: block; margin-bottom: 8px;\">Your 6-Digit Code</span>\n"
                + "          <div style=\"font-size: 38px; font-weight: 800; color: #0d5c46; letter-spacing: 10px; font-family: 'Courier New', monospace;\">" + otp + "</div>\n"
                + "        </div>\n"
                + "        \n"
                + "        <p style=\"margin: 0 0 8px 0; color: #789087; font-size: 13px;\">⏱️ This code is valid for <strong>10 minutes</strong>. Do not share it with anyone.</p>\n"
                + "        <p style=\"margin: 0; color: #9ab0a7; font-size: 12px;\">If you did not request this verification code, please ignore this email.</p>\n"
                + "      </td>\n"
                + "    </tr>\n"
                + "    <!-- Footer -->\n"
                + "    <tr>\n"
                + "      <td style=\"background-color: #f7faf8; padding: 20px 30px; text-align: center; border-top: 1px solid #e3ede8;\">\n"
                + "        <p style=\"margin: 0; color: #789087; font-size: 12px;\">&copy; 2026 <strong>Zenvego Marketplace</strong>. Fresh produce delivered from local farms.</p>\n"
                + "      </td>\n"
                + "    </tr>\n"
                + "  </table>\n"
                + "</body>\n"
                + "</html>";
    }

    private static String base64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String getEnv(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static class SmtpConnection {
        private final BufferedReader reader;
        private final BufferedWriter writer;

        SmtpConnection(Socket socket) throws IOException {
            this.reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            this.writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        }

        void command(String command, int expectedCode) throws IOException {
            send(command);
            expect(expectedCode);
        }

        void send(String text) throws IOException {
            writer.write(text + "\r\n");
            writer.flush();
        }

        void data(String message) throws IOException {
            writer.write(message + "\r\n.\r\n");
            writer.flush();
            expect(250);
        }

        void expect(int expectedCode) throws IOException {
            String line;
            String lastLine = null;
            do {
                line = reader.readLine();
                if (line == null) {
                    throw new IOException("SMTP server closed the connection unexpectedly");
                }
                lastLine = line;
            } while (line.length() > 3 && line.charAt(3) == '-');

            int code = Integer.parseInt(lastLine.substring(0, 3));
            if (code != expectedCode) {
                throw new IOException("Expected SMTP " + expectedCode + " but got: " + lastLine);
            }
        }
    }
}
