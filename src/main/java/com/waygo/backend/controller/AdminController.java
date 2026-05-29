package com.waygo.backend.controller;

import com.waygo.backend.entity.Order;
import com.waygo.backend.entity.User;
import com.waygo.backend.repository.OrderRepository;
import com.waygo.backend.repository.UserRepository;
import com.waygo.backend.service.BackupService;
import com.waygo.backend.service.SystemSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;

@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    private final SystemSettingsService settingsService;
    private final BackupService backupService;

    @GetMapping("/login")
    public String login() {
        return "admin/login";
    }

    @GetMapping("/dashboard")
    public String dashboard(Model model) {
        // ... (existing code)
        long driverCount = userRepository.countByRole(User.Role.DRIVER);
        long passengerCount = userRepository.countByRole(User.Role.PASSENGER);
        long activeOrders = orderRepository.countByStatus(Order.OrderStatus.PENDING) 
                          + orderRepository.countByStatus(Order.OrderStatus.ACCEPTED)
                          + orderRepository.countByStatus(Order.OrderStatus.STARTED);
        
        List<Order> latestOrders = orderRepository.findTop10ByOrderByCreatedAtDesc();

        model.addAttribute("title", "WayGO Admin Dashboard");
        model.addAttribute("driverCount", driverCount);
        model.addAttribute("passengerCount", passengerCount);
        model.addAttribute("activeOrders", activeOrders);
        model.addAttribute("latestOrders", latestOrders);
        model.addAttribute("activeItem", "dashboard");
        
        return "admin/dashboard";
    }

    @GetMapping("/settings")
    public String settings(Model model) {
        model.addAttribute("title", "WayGO Tizim Sozlamalari");
        model.addAttribute("settings", settingsService.getSettings());
        model.addAttribute("backups", backupService.listBackups());
        model.addAttribute("activeItem", "settings");
        return "admin/settings";
    }

    @GetMapping("/backup/download")
    public ResponseEntity<byte[]> downloadBackup() {
        // ... (existing code for on-demand JSON)
        try {
            byte[] data = backupService.generateBackupJson();
            String filename = "waygo_backup_" + java.time.LocalDate.now() + ".json";
            
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(data);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/backup/file/{filename}")
    public ResponseEntity<byte[]> downloadBackupFile(@org.springframework.web.bind.annotation.PathVariable String filename) {
        try {
            java.io.File file = new java.io.File("backups/" + filename);
            if (!file.exists()) return ResponseEntity.notFound().build();
            
            byte[] data = java.nio.file.Files.readAllBytes(file.toPath());
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentType(filename.endsWith(".sql") ? MediaType.APPLICATION_OCTET_STREAM : MediaType.APPLICATION_JSON)
                    .body(data);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/backup/sql")
    public ResponseEntity<byte[]> downloadSqlBackup() {
        try {
            byte[] data = backupService.generateSqlBackup();
            String filename = "waygo_db_backup_" + java.time.LocalDate.now() + ".sql";
            
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(data);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/drivers")
    public String drivers(Model model) {
        model.addAttribute("title", "WayGO Haydovchilar");
        model.addAttribute("drivers", userRepository.findByRoleOrderByCreatedAtDesc(User.Role.DRIVER));
        model.addAttribute("activeItem", "drivers");
        return "admin/drivers";
    }

    @GetMapping("/passengers")
    public String passengers(Model model) {
        model.addAttribute("title", "WayGO Mijozlar");
        model.addAttribute("passengers", userRepository.findByRoleOrderByCreatedAtDesc(User.Role.PASSENGER));
        model.addAttribute("activeItem", "passengers");
        return "admin/passengers";
    }

    @GetMapping("/orders")
    public String orders(Model model) {
        model.addAttribute("title", "WayGO Barcha Buyurtmalar");
        model.addAttribute("orders", orderRepository.findAll());
        model.addAttribute("activeItem", "orders");
        return "admin/orders";
    }

    @org.springframework.web.bind.annotation.PostMapping("/settings")
    public String updateSettings(@org.springframework.web.bind.annotation.ModelAttribute com.waygo.backend.entity.SystemSettings settings) {
        settingsService.updateSettings(settings);
        return "redirect:/admin/settings?success";
    }

    @org.springframework.web.bind.annotation.PostMapping("/backup/import")
    public String importBackup(@org.springframework.web.bind.annotation.RequestParam("file") org.springframework.web.multipart.MultipartFile file) {
        try {
            if (!file.isEmpty()) {
                backupService.restoreFromJson(file.getBytes());
                return "redirect:/admin/settings?importSuccess";
            }
        } catch (Exception e) {
            return "redirect:/admin/settings?importError=" + e.getMessage();
        }
        return "redirect:/admin/settings";
    }

    @org.springframework.web.bind.annotation.PostMapping("/orders/clear")
    @org.springframework.transaction.annotation.Transactional
    public String clearOrders() {
        try {
            orderRepository.deleteAll();
            return "redirect:/admin/settings?clearSuccess";
        } catch (Exception e) {
            return "redirect:/admin/settings?clearError=" + e.getMessage();
        }
    }
}
