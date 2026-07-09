package com.waygo.backend.dto.order;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class OrderCreateDTO {

    @NotBlank(message = "From address cannot be empty")
    private String fromAddress;

    @NotBlank(message = "To address cannot be empty")
    private String toAddress;

    @NotNull(message = "From latitude is required")
    private Double fromLat;

    @NotNull(message = "From longitude is required")
    private Double fromLon;

    @NotNull(message = "To latitude is required")
    private Double toLat;

    @NotNull(message = "To longitude is required")
    private Double toLon;

    @NotNull(message = "Price is required")
    @Positive(message = "Price must be positive")
    private BigDecimal price;

    private String departureDate;
    private String departureTime;
    private Integer passengerCount;
    private String notes;
    private String baggageDescription;
    private java.util.List<String> availableSeats;
    private java.util.List<String> selectedServices;
}
