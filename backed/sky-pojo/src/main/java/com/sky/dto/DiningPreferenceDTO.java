package com.sky.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class DiningPreferenceDTO {
    private List<String> tastes;
    private BigDecimal dailyBudget;
}
