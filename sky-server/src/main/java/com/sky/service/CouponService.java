package com.sky.service;

import com.sky.dto.CouponActivityDTO;
import com.sky.dto.CouponClaimDTO;
import com.sky.dto.CouponPageQueryDTO;
import com.sky.dto.CouponTemplateDTO;
import com.sky.result.PageResult;
import com.sky.entity.CouponTemplate;
import com.sky.vo.CouponActivityVO;
import com.sky.vo.CouponClaimStatusVO;
import com.sky.vo.UserCouponVO;

import java.util.List;

public interface CouponService {
    PageResult pageTemplates(CouponPageQueryDTO query);
    CouponTemplate getTemplate(Long id);
    void saveTemplate(CouponTemplateDTO dto);
    void updateTemplate(CouponTemplateDTO dto);
    void deleteTemplate(Long id);
    void updateTemplateStatus(Long id, Integer status);

    PageResult pageActivities(CouponPageQueryDTO query);
    void saveActivity(CouponActivityDTO dto);
    void updateActivity(CouponActivityDTO dto);
    void updateActivityStatus(Long id, Integer status);

    List<CouponActivityVO> listAvailableActivities(Long userId);
    CouponClaimStatusVO claim(Long userId, CouponClaimDTO dto);
    CouponClaimStatusVO getClaimStatus(Long userId, String businessNo);
    PageResult pageUserCoupons(Long userId, Integer page, Integer pageSize, Integer status);
}
