package com.waygo.backend.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import org.springframework.context.annotation.Configuration;
import jakarta.annotation.PostConstruct;

import java.io.FileInputStream;
import java.io.InputStream;

@Configuration
public class FirebaseConfig {

    @PostConstruct
    public void initialize() {
        try {
            // First try to load from classpath
            InputStream serviceAccount = getClass().getClassLoader().getResourceAsStream("firebase-service-account.json");
            
            // Fallback to local file system
            if (serviceAccount == null) {
                try {
                    serviceAccount = new FileInputStream("firebase-service-account.json");
                } catch (Exception e) {
                    System.out.println("Firebase service account file not found. Firebase will not be initialized.");
                    return;
                }
            }

            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(serviceAccount))
                    .build();

            if (FirebaseApp.getApps().isEmpty()) {
                FirebaseApp.initializeApp(options);
                System.out.println("Firebase Admin SDK initialized successfully");
            }
        } catch (Exception e) {
            System.err.println("Failed to initialize Firebase: " + e.getMessage());
        }
    }
}
