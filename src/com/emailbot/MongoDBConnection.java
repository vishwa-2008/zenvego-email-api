package com.emailbot;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import org.bson.Document;

import java.util.concurrent.TimeUnit;

public class MongoDBConnection {
    private static final String DEFAULT_URI = "mongodb://localhost:27017";
    private static final String DB_NAME = "zenvego";
    private static final String USERS_COLL = "users";
    private static final String OTP_COLL = "otp_verifications";

    private static MongoClient client;
    private static MongoDatabase database;
    private static boolean indexesEnsured = false;
    private static boolean connectionAttempted = false;

    private MongoDBConnection() {}

    private static synchronized void ensureConnected() {
        if (client == null && !connectionAttempted) {
            connectionAttempted = true;
            String uri = getEnv("MONGODB_URI", DEFAULT_URI);
            if (uri.contains("<db_password>")) {
                System.err.println("[MongoDB] Not configured; continuing with in-memory OTP storage.");
                return;
            }
            try {
                client = MongoClients.create(uri);
                database = client.getDatabase(DB_NAME);
                System.out.println("[MongoDB] Connected successfully to database: " + DB_NAME);
                ensureIndexes();
            } catch (Exception e) {
                System.err.println("[MongoDB] Failed to connect: " + e.getMessage());
                database = null;
            }
        }
    }

    public static MongoDatabase getDatabase() {
        ensureConnected();
        return database;
    }

    public static MongoCollection<Document> getUsersCollection() {
        ensureConnected();
        if (database == null) return null;
        return database.getCollection(USERS_COLL);
    }

    public static MongoCollection<Document> getOtpCollection() {
        ensureConnected();
        if (database == null) return null;
        return database.getCollection(OTP_COLL);
    }

    public static boolean isAvailable() {
        ensureConnected();
        return database != null;
    }

    private static void ensureIndexes() {
        if (indexesEnsured || database == null) return;
        try {
            MongoCollection<Document> users = database.getCollection(USERS_COLL);
            users.createIndex(Indexes.ascending("email"), new IndexOptions().unique(true));
            System.out.println("[MongoDB] Unique index ensured on users.email");
        } catch (Exception e) {
            System.err.println("[MongoDB] Users index ensure failed (may already exist): " + e.getMessage());
        }
        try {
            MongoCollection<Document> otps = database.getCollection(OTP_COLL);
            otps.createIndex(Indexes.ascending("expiresAt"),
                    new IndexOptions().expireAfter(0L, TimeUnit.SECONDS));
            otps.createIndex(Indexes.ascending("email"));
            System.out.println("[MongoDB] TTL index ensured on otp_verifications.expiresAt");
        } catch (Exception e) {
            System.err.println("[MongoDB] OTP index ensure failed (may already exist): " + e.getMessage());
        }
        indexesEnsured = true;
    }

    private static String getEnv(String name, String defaultValue) {
        String v = System.getenv(name);
        if (v != null && !v.isBlank()) return v;
        return defaultValue;
    }
}
