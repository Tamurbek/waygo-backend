package com.waygo.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.waygo.backend.repository.OrderRepository;
import com.waygo.backend.repository.SystemSettingsRepository;
import com.waygo.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class BackupService {

    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    private final SystemSettingsRepository settingsRepository;

    @org.springframework.beans.factory.annotation.Value("${spring.datasource.url}")
    private String dbUrl;

    @org.springframework.beans.factory.annotation.Value("${spring.datasource.username}")
    private String dbUsername;

    @org.springframework.beans.factory.annotation.Value("${spring.datasource.password}")
    private String dbPassword;

    private static final String BACKUP_DIR = "backups";

    @jakarta.annotation.PostConstruct
    public void init() {
        java.io.File dir = new java.io.File(BACKUP_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    public List<Map<String, String>> listBackups() {
        java.io.File dir = new java.io.File(BACKUP_DIR);
        java.io.File[] files = dir.listFiles();
        List<Map<String, String>> backupList = new java.util.ArrayList<>();
        
        if (files != null) {
            java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (java.io.File file : files) {
                Map<String, String> info = new HashMap<>();
                info.put("name", file.getName());
                info.put("size", (file.length() / 1024) + " KB");
                info.put("date", new java.text.SimpleDateFormat("dd.MM.yyyy HH:mm").format(new java.util.Date(file.lastModified())));
                backupList.add(info);
            }
        }
        return backupList;
    }

    private void saveToFile(String filename, byte[] data) throws java.io.IOException {
        java.nio.file.Files.write(java.nio.file.Paths.get(BACKUP_DIR, filename), data);
    }

    public byte[] generateBackupJson() throws Exception {
        // ... (existing code)
        Map<String, Object> backupData = new HashMap<>();
        
        backupData.put("users", userRepository.findAll());
        backupData.put("orders", orderRepository.findAll());
        backupData.put("system_settings", settingsRepository.findAll());
        backupData.put("backup_timestamp", java.time.LocalDateTime.now().toString());
        backupData.put("version", "1.0.4-beta");

        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        
        byte[] data = mapper.writeValueAsBytes(backupData);
        String filename = "backup_" + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmm")) + ".json";
        saveToFile(filename, data);
        return data;
    }

    @org.springframework.transaction.annotation.Transactional
    public void restoreFromJson(byte[] data) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        
        @SuppressWarnings("unchecked")
        Map<String, Object> backupData = mapper.readValue(data, Map.class);
        
        // 1. Clear current data (Order first because of FK to User)
        orderRepository.deleteAll();
        userRepository.deleteAll();
        settingsRepository.deleteAll();
        
        // 2. Map and Save Users
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> usersData = (List<Map<String, Object>>) backupData.get("users");
        if (usersData != null) {
            for (Map<String, Object> ud : usersData) {
                com.waygo.backend.entity.User user = mapper.convertValue(ud, com.waygo.backend.entity.User.class);
                userRepository.save(user);
            }
        }
        
        // 3. Map and Save Settings
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> settingsData = (List<Map<String, Object>>) backupData.get("system_settings");
        if (settingsData != null) {
            for (Map<String, Object> sd : settingsData) {
                com.waygo.backend.entity.SystemSettings s = mapper.convertValue(sd, com.waygo.backend.entity.SystemSettings.class);
                settingsRepository.save(s);
            }
        }
        
        // 4. Map and Save Orders
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ordersData = (List<Map<String, Object>>) backupData.get("orders");
        if (ordersData != null) {
            for (Map<String, Object> od : ordersData) {
                com.waygo.backend.entity.Order order = mapper.convertValue(od, com.waygo.backend.entity.Order.class);
                orderRepository.save(order);
            }
        }
    }

    public byte[] generateSqlBackup() throws Exception {
        // Parsing host and db name from jdbc:postgresql://host:port/dbname
        String cleanUrl = dbUrl.replace("jdbc:postgresql://", "");
        String hostPort = cleanUrl.split("/")[0];
        String host = hostPort.split(":")[0];
        String dbName = cleanUrl.split("/")[1];

        ProcessBuilder pb = new ProcessBuilder(
                "pg_dump",
                "-h", host,
                "-U", dbUsername,
                "-d", dbName
        );

        // Pass password via environment variable to pg_dump
        pb.environment().put("PGPASSWORD", dbPassword);

        Process process = pb.start();
        
        try (java.io.InputStream is = process.getInputStream()) {
            byte[] backup = is.readAllBytes();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                // Read error stream for debugging
                String error = new String(process.getErrorStream().readAllBytes());
                throw new RuntimeException("pg_dump failed with exit code " + exitCode + ": " + error);
            }
            String filename = "db_backup_" + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmm")) + ".sql";
            saveToFile(filename, backup);
            return backup;
        }
    }

    /**
     * Har oyning 10-sanasida soat 00:00 da avtomatik backup oladi.
     * Cron: "0 0 0 10 * *" -> second=0, minute=0, hour=0, day=10, month=*, weekday=*
     */
    @Scheduled(cron = "0 0 0 10 * *")
    public void monthlyBackup() {
        log.info("=== Scheduled monthly backup starting (day 10)... ===");
        try {
            generateBackupJson();
            log.info("Monthly JSON backup completed.");
        } catch (Exception e) {
            log.error("Monthly JSON backup FAILED: {}", e.getMessage(), e);
        }
        try {
            generateSqlBackup();
            log.info("Monthly SQL backup completed.");
        } catch (Exception e) {
            log.error("Monthly SQL backup FAILED: {}", e.getMessage(), e);
        }
        log.info("=== Scheduled monthly backup finished ===");
    }
}
