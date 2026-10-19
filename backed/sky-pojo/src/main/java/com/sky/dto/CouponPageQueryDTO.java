package com.sky.dto;

import lombok.Data;

@Data
public class CouponPageQueryDTO {
    private Integer page = 1;
    private Integer pageSize = 10;
    private String name;
    private Integer status;
}
