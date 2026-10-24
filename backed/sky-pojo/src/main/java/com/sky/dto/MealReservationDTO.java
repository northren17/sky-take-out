package com.sky.dto;

import lombok.Data;

import java.time.LocalDate;
import java.util.List;

@Data
public class MealReservationDTO {
    private String requestId;
    private LocalDate mealDate;
    private Integer mealPeriod;
    private String remark;
    private List<ReservationItemDTO> items;
}
