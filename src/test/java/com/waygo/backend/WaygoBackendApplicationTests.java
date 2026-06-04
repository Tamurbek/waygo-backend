package com.waygo.backend;

import com.waygo.backend.entity.Order;
import com.waygo.backend.entity.User;
import com.waygo.backend.repository.OrderRepository;
import com.waygo.backend.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
class WaygoBackendApplicationTests {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @Transactional
    void testQueryOptimization_Subselect() {
        // 1. Create a passenger
        String randomPhone = "+99899" + (1000000 + new java.util.Random().nextInt(9000000));
        User passenger = User.builder()
                .phone(randomPhone)
                .fullName("Test Passenger")
                .role(User.Role.PASSENGER)
                .balance(BigDecimal.valueOf(100000))
                .build();
        passenger = userRepository.save(passenger);

        // 2. Create 3 different orders
        for (int i = 1; i <= 3; i++) {
            Order order = Order.builder()
                    .passenger(passenger)
                    .fromAddress("Start " + i)
                    .toAddress("End " + i)
                    .price(BigDecimal.valueOf(10000 * i))
                    .passengerCount(1)
                    .availableSeats(new ArrayList<>(Arrays.asList("FRONT", "BACK_LEFT")))
                    .status(Order.OrderStatus.PENDING)
                    .baggageDescription("Baggage " + i)
                    .selectedServices(new ArrayList<>(Arrays.asList("Konditsioner", "Bagaj")))
                    .build();
            orderRepository.save(order);
        }

        // Flush all changes to database
        entityManager.flush();
        // Clear persistence context to force reloading from database
        entityManager.clear();

        System.out.println("=== STARTING FETCH TEST ===");

        // Fetch the orders
        List<Order> orders = orderRepository.findByPassengerIdOrderByCreatedAtDesc(passenger.getId());
        assertNotNull(orders);
        assertFalse(orders.isEmpty());

        System.out.println("Fetched " + orders.size() + " orders. Now accessing collections...");

        // Trigger loading of eager/lazy collections
        for (Order o : orders) {
            System.out.println("Order ID: " + o.getId());
            // Access collections
            int seatsSize = o.getAvailableSeats().size();
            int bookingsSize = o.getBookings().size();
            int offersSize = o.getDriverOffers().size();
            System.out.println("  Seats count: " + seatsSize + ", Bookings count: " + bookingsSize + ", Offers count: " + offersSize);
        }

        System.out.println("=== END FETCH TEST ===");
    }

    @Autowired
    com.waygo.backend.controller.config.ConfigController configController;

    @Test
    void testConfigTariffs() {
        try {
            System.out.println(configController.getTariffs().getBody());
        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        }
    }
}
