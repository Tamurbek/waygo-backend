package com.waygo.backend.service;

import com.waygo.backend.entity.DriverOffer;
import com.waygo.backend.entity.Order;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class NotificationService {

    private final SimpMessagingTemplate messagingTemplate;
    private final SmsService smsService;

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
}
