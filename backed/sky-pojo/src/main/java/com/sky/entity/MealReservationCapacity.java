package com.sky.entity;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class MealReservationCapacity {
    private Long id;
    private LocalDate mealDate;
    private Integer mealPeriod;
    private Integer maxReservations;
    private Integer enabled;
    private LocalDateTime updateTime;
    private Long updateUser;
}
