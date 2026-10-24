package com.sky.dto;

import lombok.Data;

import java.time.LocalDate;

@Data
public class ReservationCapacityDTO {
    private LocalDate mealDate;
    private Integer mealPeriod;
    private Integer maxReservations;
    private Integer enabled;
}
