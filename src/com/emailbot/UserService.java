package com.emailbot;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

public class UserService {

    public static Document findOrCreateUser(String email) {
        String normalizedEmail = email == null ? null : email.trim().toLowerCase();
        if (normalizedEmail == null || normalizedEmail.isBlank()) return null;

        MongoCollection<Document> users = MongoDBConnection.getUsersCollection();
        if (users == null) {
            Document fallback = new Document("email", normalizedEmail)
                    .append("createdAt", new Date());
            return fallback;
        }

        Document existing = users.find(Filters.eq("email", normalizedEmail)).first();
        if (existing != null) {
            return existing;
        }

        Date now = new Date();
        Document newUser = new Document("email", normalizedEmail)
                .append("createdAt", now)
                .append("updatedAt", now);
        try {
            users.insertOne(newUser);
        } catch (Exception e) {
            // may already exist due to race
            existing = users.find(Filters.eq("email", normalizedEmail)).first();
            if (existing != null) return existing;
        }
        return newUser;
    }

    public static Document getUserByEmail(String email) {
        String normalizedEmail = email == null ? null : email.trim().toLowerCase();
        if (normalizedEmail == null || normalizedEmail.isBlank()) return null;

        MongoCollection<Document> users = MongoDBConnection.getUsersCollection();
        if (users == null) return null;

        return users.find(Filters.eq("email", normalizedEmail)).first();
    }

    public static boolean updateUserProfile(String email, Map<String, Object> updates) {
        String normalizedEmail = email == null ? null : email.trim().toLowerCase();
        if (normalizedEmail == null || normalizedEmail.isBlank()) return false;

        MongoCollection<Document> users = MongoDBConnection.getUsersCollection();
        if (users == null) return false;

        findOrCreateUser(normalizedEmail);

        List<Bson> updateList = new ArrayList<>();
        if (updates != null) {
            for (Map.Entry<String, Object> entry : updates.entrySet()) {
                String key = entry.getKey();
                Object val = entry.getValue();
                if (key == null || key.equals("_id") || key.equals("email") || key.equals("createdAt")) continue;
                if (val == null) continue;
                updateList.add(Updates.set(key, val));
            }
        }
        updateList.add(Updates.set("updatedAt", new Date()));

        try {
            users.updateOne(
                    Filters.eq("email", normalizedEmail),
                    Updates.combine(updateList),
                    new UpdateOptions().upsert(true)
            );
            return true;
        } catch (Exception e) {
            System.err.println("[UserService] Update profile failed for " + normalizedEmail + ": " + e.getMessage());
            return false;
        }
    }

    public static List<Document> listUsers() {
        List<Document> result = new ArrayList<>();
        MongoCollection<Document> users = MongoDBConnection.getUsersCollection();
        if (users == null) return result;
        for (Document d : users.find()) {
            result.add(d);
        }
        return result;
    }
}
