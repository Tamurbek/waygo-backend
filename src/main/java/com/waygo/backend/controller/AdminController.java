package com.waygo.backend.controller;

import com.waygo.backend.entity.Order;
import com.waygo.backend.entity.Transaction;
import com.waygo.backend.entity.User;
import com.waygo.backend.repository.OrderRepository;
import com.waygo.backend.repository.TransactionRepository;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    private final SystemSettingsService settingsService;
    private final BackupService backupService;
    private final TransactionRepository transactionRepository;
    private final com.waygo.backend.service.TransactionService transactionService;
    private final com.waygo.backend.service.NotificationService notificationService;
    private final com.waygo.backend.repository.config.TariffPlanRepository tariffPlanRepository;

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

    @GetMapping("/payments")
    public String payments(
            @org.springframework.web.bind.annotation.RequestParam(value = "startDate", required = false) String startDateStr,
            @org.springframework.web.bind.annotation.RequestParam(value = "endDate", required = false) String endDateStr,
            Model model) {
        
        List<Transaction> tariffPurchases;
        boolean isFiltered = false;
        BigDecimal filteredRevenue = BigDecimal.ZERO;
        long filteredCount = 0;
        
        LocalDateTime start = null;
        LocalDateTime end = null;
        
        if (startDateStr != null && !startDateStr.trim().isEmpty()) {
            try {
                start = java.time.LocalDate.parse(startDateStr.trim()).atStartOfDay();
            } catch (Exception ignored) {}
        }
        if (endDateStr != null && !endDateStr.trim().isEmpty()) {
            try {
                end = java.time.LocalDate.parse(endDateStr.trim()).atTime(23, 59, 59, 999999000);
            } catch (Exception ignored) {}
        }
        
        if (start != null || end != null) {
            isFiltered = true;
            if (start == null) {
                start = LocalDateTime.of(1970, 1, 1, 0, 0);
            }
            if (end == null) {
                end = LocalDateTime.now();
            }
            tariffPurchases = transactionRepository
                    .findByTypeAndCreatedAtBetweenOrderByCreatedAtDesc(Transaction.TransactionType.TARIFF_PURCHASE, start, end);
            filteredRevenue = transactionRepository
                    .sumAmountByTypeAndCreatedAtBetween(Transaction.TransactionType.TARIFF_PURCHASE, start, end);
            filteredCount = transactionRepository
                    .countByTypeAndCreatedAtBetween(Transaction.TransactionType.TARIFF_PURCHASE, start, end);
        } else {
            tariffPurchases = transactionRepository
                    .findByTypeOrderByCreatedAtDesc(Transaction.TransactionType.TARIFF_PURCHASE);
        }

        // Statistika
        BigDecimal totalRevenue = transactionRepository
                .sumAmountByType(Transaction.TransactionType.TARIFF_PURCHASE);
        long totalCount = transactionRepository
                .countByType(Transaction.TransactionType.TARIFF_PURCHASE);
        BigDecimal todayRevenue = transactionRepository
                .sumTariffRevenueFrom(LocalDateTime.now().withHour(0).withMinute(0).withSecond(0));
        BigDecimal monthRevenue = transactionRepository
                .sumTariffRevenueFrom(LocalDateTime.now().withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0));

        model.addAttribute("title", "WayGO To'lovlar Statistikasi");
        model.addAttribute("tariffPurchases", tariffPurchases);
        model.addAttribute("totalRevenue", totalRevenue != null ? totalRevenue : BigDecimal.ZERO);
        model.addAttribute("totalCount", totalCount);
        model.addAttribute("todayRevenue", todayRevenue != null ? todayRevenue : BigDecimal.ZERO);
        model.addAttribute("monthRevenue", monthRevenue != null ? monthRevenue : BigDecimal.ZERO);
        
        // Filter information
        model.addAttribute("isFiltered", isFiltered);
        model.addAttribute("startDate", startDateStr);
        model.addAttribute("endDate", endDateStr);
        model.addAttribute("filteredRevenue", filteredRevenue != null ? filteredRevenue : BigDecimal.ZERO);
        model.addAttribute("filteredCount", filteredCount);
        
        model.addAttribute("activeItem", "payments");
        return "admin/payments";
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
        model.addAttribute("tariffs", tariffPlanRepository.findAll());
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

    @org.springframework.web.bind.annotation.PostMapping("/drivers/{id}/toggle-billing")
    @org.springframework.transaction.annotation.Transactional
    public String toggleDriverBilling(@org.springframework.web.bind.annotation.PathVariable Long id) {
        try {
            User driver = userRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Driver not found"));
            boolean newBillingState = !driver.isDriverBillingEnabled();
            driver.setDriverBillingEnabled(newBillingState);
            if (newBillingState) {
                driver.unfreezeTariff();
            } else {
                driver.freezeTariff();
            }
            User saved = userRepository.save(driver);
            
            try {
                String message = newBillingState 
                    ? "To'lov tizimi faollashtirildi. Iltimos, joriy tarif yoki balansni tekshiring." 
                    : "To'lov tizimi o'chirildi. Sizga VIP statusi berildi!";
                notificationService.notifyTariffUpdate(saved, message);
            } catch (Exception e) {
                // Ignore notification failure to prevent transaction rollback
            }
            
            return "redirect:/admin/drivers?success";
        } catch (Exception e) {
            return "redirect:/admin/drivers?error=" + e.getMessage();
        }
    }

    @org.springframework.web.bind.annotation.PostMapping("/drivers/{id}/cancel-tariff")
    public String cancelDriverTariff(@org.springframework.web.bind.annotation.PathVariable Long id) {
        try {
            transactionService.cancelDriverTariff(id);
            return "redirect:/admin/drivers?success";
        } catch (Exception e) {
            return "redirect:/admin/drivers?error=" + e.getMessage();
        }
    }

    @org.springframework.web.bind.annotation.PostMapping("/drivers/{id}/change-tariff")
    public String changeDriverTariff(@org.springframework.web.bind.annotation.PathVariable Long id, @org.springframework.web.bind.annotation.RequestParam("tariffId") Long tariffId) {
        try {
            transactionService.changeDriverTariff(id, tariffId);
            return "redirect:/admin/drivers?success";
        } catch (Exception e) {
            return "redirect:/admin/drivers?error=" + e.getMessage();
        }
    }

    @org.springframework.web.bind.annotation.PostMapping("/drivers/{id}/assign-vip")
    public String assignDriverVip(
            @org.springframework.web.bind.annotation.PathVariable Long id,
            @org.springframework.web.bind.annotation.RequestParam("price") java.math.BigDecimal price,
            @org.springframework.web.bind.annotation.RequestParam("durationDays") Integer durationDays) {
        try {
            transactionService.assignManualVip(id, price, durationDays);
            return "redirect:/admin/drivers?success";
        } catch (Exception e) {
            return "redirect:/admin/drivers?error=" + e.getMessage();
        }
    }

    @org.springframework.web.bind.annotation.PostMapping("/drivers/bulk-action")
    @org.springframework.transaction.annotation.Transactional
    public String bulkActionDrivers(
            @org.springframework.web.bind.annotation.RequestParam(value = "driverIds", required = false) List<Long> driverIds,
            @org.springframework.web.bind.annotation.RequestParam("action") String action) {
        if (driverIds != null && !driverIds.isEmpty()) {
            try {
                boolean enable = "enable-billing".equals(action);
                List<User> drivers = userRepository.findAllById(driverIds);
                for (User driver : drivers) {
                    if (driver.getRole() == User.Role.DRIVER) {
                        if (driver.isDriverBillingEnabled() != enable) {
                            driver.setDriverBillingEnabled(enable);
                            if (enable) {
                                driver.unfreezeTariff();
                            } else {
                                driver.freezeTariff();
                            }
                            User saved = userRepository.save(driver);
                            
                            try {
                                String message = enable 
                                    ? "To'lov tizimi faollashtirildi. Iltimos, joriy tarif yoki balansni tekshiring." 
                                    : "To'lov tizimi o'chirildi. Sizga VIP statusi berildi!";
                                notificationService.notifyTariffUpdate(saved, message);
                            } catch (Exception notificationEx) {
                                // Ignore notification failure for individual driver
                            }
                        }
                    }
                }
                return "redirect:/admin/drivers?success";
            } catch (Exception e) {
                return "redirect:/admin/drivers?error=" + e.getMessage();
            }
        }
        return "redirect:/admin/drivers";
    }

    @org.springframework.web.bind.annotation.PostMapping("/drivers/{id}/add-balance")
    @org.springframework.transaction.annotation.Transactional
    public String addDriverBalance(
            @org.springframework.web.bind.annotation.PathVariable Long id,
            @org.springframework.web.bind.annotation.RequestParam("amount") java.math.BigDecimal amount) {
        try {
            transactionService.topUp(id, amount);
            return "redirect:/admin/drivers?success";
        } catch (Exception e) {
            return "redirect:/admin/drivers?error=" + e.getMessage();
        }
    }

    @org.springframework.web.bind.annotation.PostMapping("/drivers/{id}/reset-balance")
    public String resetDriverBalance(@org.springframework.web.bind.annotation.PathVariable Long id) {
        try {
            transactionService.resetBalance(id);
            return "redirect:/admin/drivers?success";
        } catch (Exception e) {
            return "redirect:/admin/drivers?error=" + e.getMessage();
        }
    }
}

