package com.emailbot;

import java.security.SecureRandom;

public class OTPGenerator {
    private static final SecureRandom random = new SecureRandom();
    
    public static String generateOTP() {
        // Generate 6-digit random number
        int otp = 100000 + random.nextInt(900000);
        return String.valueOf(otp);
    }
}