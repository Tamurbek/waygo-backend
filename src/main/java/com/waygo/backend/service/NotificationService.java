package com.waygo.backend.service;

import com.waygo.backend.entity.DriverOffer;
import com.waygo.backend.entity.Order;
import com.waygo.backend.entity.RideBooking;
import com.waygo.backend.entity.User;
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
            @Qualifier("consoleSmsService") SmsService smsService,
            @Qualifier("dynamicSmsService") SmsService realSmsService
    ) {
        this.messagingTemplate = messagingTemplate;
        this.smsService = smsService;
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
            // SMS to passenger
            smsService.sendSms(order.getPassenger().getPhone(), msg);
        }

        // Also notify the directly assigned driver if present
        if (order.getDriver() != null) {
            messagingTemplate.convertAndSendToUser(
                    order.getDriver().getPhone(),
                    "/queue/order-status",
                    order
            );
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
                        // Send SMS to REJECTED drivers specifically
                        if ("REJECTED".equalsIgnoreCase(offer.getStatus())) {
                            smsService.sendSms(
                                offer.getDriver().getPhone(),
                                "WayGO: Afsuski, yo'lovchi boshqa haydovchini tanladi. Taklifingiz rad etildi."
                            );
                        }
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
            smsService.sendSms(passenger.getPhone(), msg);

            // Send private WebSocket update to passenger so they immediately receive it
            messagingTemplate.convertAndSendToUser(
                    passenger.getPhone(),
                    "/queue/order-status",
                    order
            );
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
        smsService.sendSms(phone, msg);

        if (order != null) {
            messagingTemplate.convertAndSendToUser(
                    phone,
                    "/queue/order-status",
                    order
            );
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
        smsService.sendSms(phone, msg);

        if (order != null) {
            messagingTemplate.convertAndSendToUser(
                    phone,
                    "/queue/order-status",
                    order
            );
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
        smsService.sendSms(phone, msg);

        messagingTemplate.convertAndSendToUser(
                phone,
                "/queue/order-status",
                passengerOrder
        );
        messagingTemplate.convertAndSend("/topic/orders/update", passengerOrder);
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
        smsService.sendSms(phone, msg);

        messagingTemplate.convertAndSendToUser(
                phone,
                "/queue/order-status",
                passengerOrder
        );
        messagingTemplate.convertAndSend("/topic/orders/update", passengerOrder);
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
        smsService.sendSms(phone, msg);

        if (driverOrder != null) {
            messagingTemplate.convertAndSendToUser(
                    phone,
                    "/queue/order-status",
                    driverOrder
            );
        }
    }

    public void notifyBalanceUpdate(User user, java.math.BigDecimal amount) {
        if (user == null || user.getPhone() == null) {
            return;
        }
        String formattedAmount = amount.setScale(0, java.math.RoundingMode.HALF_UP).toString();
        String formattedBalance = user.getBalance() != null
                ? user.getBalance().setScale(0, java.math.RoundingMode.HALF_UP).toString()
                : "0";
        String msg = "WayGO: Hisobingiz " + formattedAmount + " UZS ga to'ldirildi! Joriy balans: " + formattedBalance + " UZS";
        realSmsService.sendSms(user.getPhone(), msg);

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
    }
}

