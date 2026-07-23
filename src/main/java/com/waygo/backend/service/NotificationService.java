package com.waygo.backend.service;

import com.waygo.backend.entity.DriverOffer;
import com.waygo.backend.entity.Order;
import com.waygo.backend.entity.RideBooking;
import com.waygo.backend.entity.User;
import com.waygo.backend.entity.VipChatMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

@Service
public class NotificationService {

    private final SimpMessagingTemplate messagingTemplate;
    private final SmsService smsService;
    private final SmsService realSmsService;

    public NotificationService(
            SimpMessagingTemplate messagingTemplate,
            @Qualifier("dynamicSmsService") SmsService realSmsService
    ) {
        this.messagingTemplate = messagingTemplate;
        this.smsService = realSmsService;
        this.realSmsService = realSmsService;
    }

    public void notifyNewOrder(Order order) {
        // Notify all drivers about a new pending order
        messagingTemplate.convertAndSend("/topic/orders/new", order);
    }

    public void notifyOrderStatusUpdate(Order order) {
        String msg = "WayGO: Buyurtmangiz holati yangilandi: " + order.getStatus();

        // Notify the specific passenger about their order status update if present
        if (order.getPassenger() != null) {
            messagingTemplate.convertAndSendToUser(
                    order.getPassenger().getPhone(),
                    "/queue/order-status",
                    order
            );
            
            java.util.Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("type", "ORDER_UPDATE");
            payload.put("order", order);
            messagingTemplate.convertAndSend(
                    "/topic/notifications/" + order.getPassenger().getId(),
                    payload
            );
            
            sendFcmNotification(order.getPassenger(), "Buyurtma holati yangilandi", msg, "ORDER_UPDATE");
        }

        // Notify all passengers attached to announcement bookings
        if (order.getBookings() != null) {
            for (RideBooking b : order.getBookings()) {
                if (b != null && b.getPassenger() != null) {
                    java.util.Map<String, Object> payload = new java.util.HashMap<>();
                    payload.put("type", "ORDER_UPDATE");
                    payload.put("order", order);
                    messagingTemplate.convertAndSend(
                            "/topic/notifications/" + b.getPassenger().getId(),
                            payload
                    );
                }
            }
        }

        // Also notify the directly assigned driver if present
        if (order.getDriver() != null) {
            messagingTemplate.convertAndSendToUser(
                    order.getDriver().getPhone(),
                    "/queue/order-status",
                    order
            );
            
            java.util.Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("type", "ORDER_UPDATE");
            payload.put("order", order);
            messagingTemplate.convertAndSend(
                    "/topic/notifications/" + order.getDriver().getId(),
                    payload
            );
            
            sendFcmNotification(order.getDriver(), "Buyurtma holati yangilandi", msg, "ORDER_UPDATE");
        }

        // Notify ALL drivers who submitted offers so they learn if accepted/rejected
        if (order.getDriverOffers() != null) {
            for (DriverOffer offer : order.getDriverOffers()) {
                if (offer.getDriver() != null) {
                    // Avoid double-notifying the already assigned driver (already notified above)
                    boolean isAssignedDriver = order.getDriver() != null &&
                            order.getDriver().getId().equals(offer.getDriver().getId());
                    if (!isAssignedDriver) {
                        messagingTemplate.convertAndSendToUser(
                                offer.getDriver().getPhone(),
                                "/queue/order-status",
                                order
                        );
                    }
                }
            }
        }

        // Broadcast updated order globally so both user and driver apps receive it in real-time
        messagingTemplate.convertAndSend("/topic/orders/update", order);
    }

    public void notifySeatCancelled(User passenger, String seatName, Order order) {
        if (passenger != null && passenger.getPhone() != null) {
            String msg = "WayGO: Haydovchi sizning \"" + seatName + "\" o'rindig'ingizni bekor qildi.";

            // Send private WebSocket update to passenger so they immediately receive it
            messagingTemplate.convertAndSendToUser(
                    passenger.getPhone(),
                    "/queue/order-status",
                    order
            );
            
            sendFcmNotification(passenger, "Joy bekor qilindi", msg, "ORDER_UPDATE");
        }
    }

    public void notifyBookingConfirmed(RideBooking booking) {
        if (booking == null || booking.getPassenger() == null) {
            return;
        }

        String phone = booking.getPassenger().getPhone();
        if (phone == null || phone.isEmpty()) {
            return;
        }

        Order order = booking.getOrder();
        String fromLoc = order != null ? order.getFromAddress() : "";
        String toLoc = order != null ? order.getToAddress() : "";

        String msg = "WayGO: Haydovchi sizning so'rovingizni tasdiqladi! Qatnov: " + fromLoc + " -> " + toLoc;

        if (order != null) {
            messagingTemplate.convertAndSendToUser(
                    phone,
                    "/queue/order-status",
                    order
            );
            sendFcmNotification(booking.getPassenger(), "So'rov tasdiqlandi", msg, "ORDER_UPDATE");
        }
    }

    public void notifyBookingRejected(RideBooking booking) {
        if (booking == null || booking.getPassenger() == null) {
            return;
        }

        String phone = booking.getPassenger().getPhone();
        if (phone == null || phone.isEmpty()) {
            return;
        }

        Order order = booking.getOrder();
        String fromLoc = order != null ? order.getFromAddress() : "";
        String toLoc = order != null ? order.getToAddress() : "";

        String msg = "WayGO: Afsuski, haydovchi sizning so'rovingizni rad etdi. Qatnov: " + fromLoc + " -> " + toLoc;

        if (order != null) {
            messagingTemplate.convertAndSendToUser(
                    phone,
                    "/queue/order-status",
                    order
            );
            sendFcmNotification(booking.getPassenger(), "So'rov rad etildi", msg, "ORDER_UPDATE");
        }
    }

    public void notifyPassengerOrderCancelledByDriver(Order passengerOrder, Order driverOrder) {
        if (passengerOrder == null || passengerOrder.getPassenger() == null) {
            return;
        }
        String phone = passengerOrder.getPassenger().getPhone();
        if (phone == null || phone.isEmpty()) {
            return;
        }

        String msg = "WayGO: Afsuski, haydovchi o'z qatnovini bekor qildi. Shu sababli sizning buyurtmangiz bekor qilindi.";

        messagingTemplate.convertAndSendToUser(
                phone,
                "/queue/order-status",
                passengerOrder
        );
        messagingTemplate.convertAndSend("/topic/orders/update", passengerOrder);
        
        sendFcmNotification(passengerOrder.getPassenger(), "Buyurtma bekor qilindi", msg, "ORDER_UPDATE");
    }

    public void notifyDriverOrderCancelledByPassenger(Order passengerOrder) {
        if (passengerOrder == null || passengerOrder.getDriver() == null) {
            return;
        }
        String phone = passengerOrder.getDriver().getPhone();
        if (phone == null || phone.isEmpty()) {
            return;
        }

        String msg = "WayGO: Yo'lovchi o'z buyurtmasini bekor qildi. Qatnov: " +
                (passengerOrder.getFromAddress() != null ? passengerOrder.getFromAddress() : "") + " -> " +
                (passengerOrder.getToAddress() != null ? passengerOrder.getToAddress() : "");

        messagingTemplate.convertAndSendToUser(
                phone,
                "/queue/order-status",
                passengerOrder
        );
        messagingTemplate.convertAndSend("/topic/orders/update", passengerOrder);
        
        sendFcmNotification(passengerOrder.getDriver(), "Buyurtma bekor qilindi", msg, "ORDER_UPDATE");
    }

    public void notifyBookingCancelledByDriver(RideBooking booking, Order driverOrder) {
        if (booking == null || booking.getPassenger() == null) {
            return;
        }
        String phone = booking.getPassenger().getPhone();
        if (phone == null || phone.isEmpty()) {
            return;
        }

        String msg = "WayGO: Afsuski, haydovchi o'z qatnovini bekor qildi. Shu sababli sizning band qilgan o'rindig'ingiz bekor qilindi.";

        if (driverOrder != null) {
            messagingTemplate.convertAndSendToUser(
                    phone,
                    "/queue/order-status",
                    driverOrder
            );
            sendFcmNotification(booking.getPassenger(), "Joy bekor qilindi", msg, "ORDER_UPDATE");
        }
    }
    
    public void notifySeatBookedByPassenger(Order driverOrder, User passenger) {
        if (driverOrder == null || driverOrder.getDriver() == null) {
            return;
        }
        String phone = driverOrder.getDriver().getPhone();
        if (phone == null || phone.isEmpty()) {
            return;
        }

        String msg = "WayGO: Yo'lovchi sizning qatnovingizda joy band qildi!";
        
        java.util.Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("type", "ORDER_UPDATE");
        payload.put("order", driverOrder);
        messagingTemplate.convertAndSend(
                "/topic/notifications/" + driverOrder.getDriver().getId(),
                payload
        );
        
        sendFcmNotification(driverOrder.getDriver(), "Yangi yo'lovchi", msg, "ORDER_UPDATE");
    }

    public void notifyNextPassengerTurn(User nextPassenger, User driver, Long orderId) {
        if (nextPassenger == null) {
            return;
        }

        String driverName = (driver != null && driver.getFullName() != null && !driver.getFullName().isEmpty())
                ? driver.getFullName()
                : "Haydovchi";

        String msg = "Navbat sizga keldi! " + driverName + " sizni olib ketgani yo'lga chiqdi. Ilovada real vaqtda kuzatishingiz mumkin!";

        java.util.Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("type", "NEXT_PASSENGER_TURN");
        payload.put("message", msg);

        if (orderId != null) {
            payload.put("orderId", orderId);
        }

        if (driver != null) {
            payload.put("driverId", driver.getId());
        }

        messagingTemplate.convertAndSend(
                "/topic/notifications/" + nextPassenger.getId(),
                payload
        );

        sendFcmNotification(nextPassenger, "Navbat sizga keldi! 🚖", msg, "NEXT_PASSENGER_TURN");
    }

    public void notifyBalanceUpdate(User user, java.math.BigDecimal amount) {
        if (user == null || user.getPhone() == null) {
            return;
        }
        java.text.NumberFormat nf = java.text.NumberFormat.getInstance(new java.util.Locale("uz", "UZ"));
        nf.setGroupingUsed(true);
        nf.setMaximumFractionDigits(0);

        String formattedAmount  = nf.format(amount.setScale(0, java.math.RoundingMode.HALF_UP));
        String formattedBalance = nf.format(
                (user.getBalance() != null ? user.getBalance() : java.math.BigDecimal.ZERO)
                        .setScale(0, java.math.RoundingMode.HALF_UP));

        String msg = "WayGoUz: Hisobingizga " + formattedAmount + " so'm tushdi. "
                   + "Joriy balansingiz: " + formattedBalance + " so'm.";

        // Send SMS via Eskiz
        smsService.sendSms(user.getPhone(), msg);

        java.util.Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("type", "BALANCE_UPDATE");
        payload.put("amount", amount);
        payload.put("balance", user.getBalance());
        payload.put("message", msg);

        // Send directly to the user's numeric ID specific topic to avoid special character (+) routing issues in STOMP
        messagingTemplate.convertAndSend(
                "/topic/notifications/" + user.getId(),
                payload
        );
        
        sendFcmNotification(user, "Balans yangilandi", msg, "BALANCE_UPDATE");
    }

    public void notifyTariffUpdate(User user, String message) {
        if (user == null || user.getPhone() == null) {
            return;
        }
        smsService.sendSms(user.getPhone(), "WayGO: " + message);

        java.util.Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("type", "TARIFF_UPDATE");
        payload.put("message", message);

        // Send directly to the user's numeric ID specific topic
        messagingTemplate.convertAndSend(
                "/topic/notifications/" + user.getId(),
                payload
        );
        
        sendFcmNotification(user, "Tarif yangilandi", message, "TARIFF_UPDATE");
    }

    public void notifyNewChatMessage(VipChatMessage message) {
        if (message == null || message.getDriver() == null) {
            return;
        }
        
        Runnable sendNotification = () -> {
            java.util.Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("type", "CHAT_MESSAGE");
            payload.put("messageText", message.getMessageText());
            payload.put("sender", message.getSender().name());
            payload.put("createdAt", message.getCreatedAt() != null ? message.getCreatedAt().toString() : java.time.LocalDateTime.now().toString());
            payload.put("id", message.getId());

            // Send directly to the user's numeric ID specific topic
            messagingTemplate.convertAndSend(
                    "/topic/notifications/" + message.getDriver().getId(),
                    payload
            );
            
            // Send Push Notification
            sendFcmNotification(message.getDriver(), "Yangi xabar", message.getMessageText(), "CHAT_MESSAGE");
        };

        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        sendNotification.run();
                    }
                }
            );
        } else {
            sendNotification.run();
        }
    }

    private void sendFcmNotification(User user, String title, String body, String type) {
        if (user == null || user.getFcmToken() == null || user.getFcmToken().isEmpty()) {
            return;
        }
        try {
            com.google.firebase.messaging.Message message = com.google.firebase.messaging.Message.builder()
                    .setToken(user.getFcmToken())
                    .setNotification(com.google.firebase.messaging.Notification.builder()
                            .setTitle(title)
                            .setBody(body)
                            .build())
                    .setAndroidConfig(com.google.firebase.messaging.AndroidConfig.builder()
                            .setPriority(com.google.firebase.messaging.AndroidConfig.Priority.HIGH)
                            .setNotification(com.google.firebase.messaging.AndroidNotification.builder()
                                    .setChannelId("high_importance_channel")
                                    .build())
                            .build())
                    .putData("type", type)
                    .build();

            com.google.firebase.messaging.FirebaseMessaging.getInstance().send(message);
        } catch (Exception e) {
            System.err.println("Failed to send FCM notification to user " + user.getId() + ": " + e.getMessage());
        }
    }
}

