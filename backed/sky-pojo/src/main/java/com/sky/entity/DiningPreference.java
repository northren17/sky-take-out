package com.sky.entity;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class DiningPreference {
    private Long userId;
    private String tastePreferences;
    private BigDecimal dailyBudget;
}
