package com.sky.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class MealReservation {
    private Long id;
    private String reservationNo;
    private Long userId;
    private LocalDate mealDate;
    private Integer mealPeriod;
    private Integer status;
    private String remark;
    private BigDecimal totalAmount;
    private LocalDateTime createTime;
    private LocalDateTime cancelTime;
    private LocalDateTime pickupTime;
}
