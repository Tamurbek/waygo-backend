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

    private Double fromLat;
    private Double fromLon;
    private Double toLat;
    private Double toLon;

    @NotNull(message = "Price is required")
    @Positive(message = "Price must be positive")
    private BigDecimal price;

    private String departureDate;
    private String departureTime;
    private Integer passengerCount;
    private String notes;
}
