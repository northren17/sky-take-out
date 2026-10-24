package com.sky.dto;

import lombok.Data;

@Data
public class ReservationItemDTO {
    private Integer itemType;
    private Long itemId;
    private Integer quantity;
}
