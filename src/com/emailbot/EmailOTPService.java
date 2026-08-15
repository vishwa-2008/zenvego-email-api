package com.emailbot;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import org.bson.Document;

import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class EmailOTPService {
    private static final long EXPIRY_TIME = 10 * 60 * 1000;
    private static final Map<String, InMemoryOtp> inMemoryOtps = new ConcurrentHashMap<>();

    private static class InMemoryOtp {
        final String code;
        final Date expiresAt;

        InMemoryOtp(String code, Date expiresAt) {
            this.code = code;
            this.expiresAt = expiresAt;
        }
    }

    public static boolean generateAndSendOTP(String email, String userName) {
        if (email == null) return false;
        String normalizedEmail = email.trim().toLowerCase();
        String otp = OTPGenerator.generateOTP();

        System.out.println("\n==========================================");
        System.out.println("[OTP LOG] Sending OTP " + otp + " to: " + normalizedEmail);
        System.out.println("==========================================\n");

        boolean sent = EmailSender.sendOTPEmail(normalizedEmail, userName, otp);
        if (!sent) {
            System.err.println("[OTP WARN] Brevo email delivery failed or key missing. OTP " + otp + " saved to in-memory store for fallback verification.");
        }

        Date now = new Date();
        Date expiresAt = new Date(now.getTime() + EXPIRY_TIME);
        MongoCollection<Document> otpColl = MongoDBConnection.getOtpCollection();
        if (otpColl != null) {
            try {
                Document doc = new Document("email", normalizedEmail)
                        .append("otpCode", otp)
                        .append("expiresAt", expiresAt)
                        .append("createdAt", now);
                otpColl.deleteMany(Filters.eq("email", normalizedEmail));
                otpColl.insertOne(doc);
            } catch (Exception e) {
                System.err.println("[OTP ERROR] Failed to persist OTP record to MongoDB: " + e.getMessage());
            }
        }

        inMemoryOtps.put(normalizedEmail, new InMemoryOtp(otp, expiresAt));

        try {
            UserService.findOrCreateUser(normalizedEmail);
        } catch (Exception e) {
            System.err.println("[OTP WARN] Could not stub user record: " + e.getMessage());
        }

        return true;
    }

    public static boolean verifyOTP(String email, String otp) {
        if (email == null || otp == null) {
            System.out.println("[OTP LOG] Verification failed: Email or OTP is null.");
            return false;
        }

        String normalizedEmail = email.trim().toLowerCase();
        String normalizedOtp = otp.trim();

        System.out.println("\n------------------------------------------");
        System.out.println("[OTP LOG] Verifying OTP for email: " + normalizedEmail);

        MongoCollection<Document> otpColl = MongoDBConnection.getOtpCollection();
        if (otpColl == null) {
            InMemoryOtp record = inMemoryOtps.get(normalizedEmail);
            if (record == null || record.expiresAt.before(new Date())) {
                inMemoryOtps.remove(normalizedEmail);
                System.out.println("[OTP LOG] Verification FAILED: No active OTP found for " + normalizedEmail);
                System.out.println("------------------------------------------\n");
                return false;
            }
            boolean isValid = record.code.equals(normalizedOtp);
            if (isValid) inMemoryOtps.remove(normalizedEmail);
            System.out.println("[OTP LOG] Verification " + (isValid ? "SUCCESSFUL" : "FAILED: Code mismatch") + " using in-memory OTP storage");
            System.out.println("------------------------------------------\n");
            return isValid;
        }

        InMemoryOtp inMem = inMemoryOtps.get(normalizedEmail);
        if (inMem != null && !inMem.expiresAt.before(new Date()) && inMem.code.equals(normalizedOtp)) {
            inMemoryOtps.remove(normalizedEmail);
            try { otpColl.deleteMany(Filters.eq("email", normalizedEmail)); } catch (Exception ignored) {}
            System.out.println("[OTP LOG] Verification SUCCESSFUL via in-memory cache for " + normalizedEmail);
            return true;
        }

        Document record;
        try {
            record = otpColl.find(Filters.eq("email", normalizedEmail)).first();
        } catch (Exception e) {
            System.err.println("[OTP ERROR] MongoDB query failed: " + e.getMessage());
            return false;
        }

        if (record == null) {
            System.out.println("[OTP LOG] Verification FAILED: No active OTP found for " + normalizedEmail);
            System.out.println("------------------------------------------\n");
            return false;
        }

        Date expiresAt = record.getDate("expiresAt");
        if (expiresAt == null || expiresAt.before(new Date())) {
            System.out.println("[OTP LOG] Verification FAILED: OTP expired (> 10 minutes)");
            try {
                otpColl.deleteMany(Filters.eq("email", normalizedEmail));
            } catch (Exception ignored) {}
            System.out.println("------------------------------------------\n");
            return false;
        }

        String storedOtp = record.getString("otpCode");
        boolean isValid = storedOtp != null && storedOtp.equals(normalizedOtp);

        if (isValid) {
            System.out.println("[OTP LOG] Verification SUCCESSFUL for " + normalizedEmail);
            try {
                otpColl.deleteMany(Filters.eq("email", normalizedEmail));
            } catch (Exception ignored) {}
        } else {
            System.out.println("[OTP LOG] Verification FAILED: Code mismatch for " + normalizedEmail);
        }
        System.out.println("------------------------------------------\n");

        return isValid;
    }
}
