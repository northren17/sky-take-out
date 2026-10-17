package com.sky.service.impl;

import com.alibaba.fastjson.JSON;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.context.BaseContext;
import com.sky.annotation.CouponDistributedLock;
import com.sky.dto.CouponActivityDTO;
import com.sky.dto.CouponClaimDTO;
import com.sky.dto.CouponPageQueryDTO;
import com.sky.dto.CouponTemplateDTO;
import com.sky.entity.CouponActivity;
import com.sky.entity.CouponClaimRecord;
import com.sky.entity.CouponTemplate;
import com.sky.exception.CouponBusinessException;
import com.sky.mapper.CouponMapper;
import com.sky.service.CouponClaimRedisService;
import com.sky.service.CouponRuleCache;
import com.sky.result.PageResult;
import com.sky.service.CouponService;
import com.sky.vo.CouponActivityVO;
import com.sky.vo.CouponClaimStatusVO;
import com.sky.vo.CouponActivityRule;
import com.sky.vo.UserCouponVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class CouponServiceImpl implements CouponService {
    private final CouponMapper couponMapper;
    private final CouponClaimRedisService claimRedisService;
    private final CouponClaimPersistenceService claimPersistenceService;
    private final CouponRuleCache ruleCache;

    @Override
    public PageResult pageTemplates(CouponPageQueryDTO query) {
        normalizePage(query.getPage(), query.getPageSize());
        PageHelper.startPage(query.getPage(), query.getPageSize());
        Page<CouponTemplate> page = couponMapper.pageTemplates(query);
        return new PageResult(page.getTotal(), page.getResult());
    }

    @Override
    public CouponTemplate getTemplate(Long id) {
        CouponTemplate template = ruleCache.getTemplate(id, () -> couponMapper.getTemplate(id));
        if (template == null) throw new CouponBusinessException("优惠券模板不存在");
        return template;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveTemplate(CouponTemplateDTO dto) {
        validateTemplate(dto);
        CouponTemplate template = toTemplate(dto);
        template.setCreateUser(BaseContext.getCurrentId());
        template.setUpdateUser(BaseContext.getCurrentId());
        couponMapper.insertTemplate(template);
        invalidateTemplateAfterCommit(template.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateTemplate(CouponTemplateDTO dto) {
        if (dto.getId() == null || couponMapper.getTemplateForUpdate(dto.getId()) == null) {
            throw new CouponBusinessException("优惠券模板不存在");
        }
        if (couponMapper.countPendingClaimsByTemplate(dto.getId()) > 0) {
            throw new CouponBusinessException("关联活动存在未完成领取任务，暂时不能修改模板");
        }
        validateTemplate(dto);
        CouponTemplate template = toTemplate(dto);
        template.setUpdateUser(BaseContext.getCurrentId());
        if (couponMapper.updateTemplate(template) != 1) {
            throw new CouponBusinessException("更新优惠券模板失败");
        }
        invalidateTemplateAfterCommit(template.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteTemplate(Long id) {
        if (couponMapper.countActivitiesByTemplate(id) > 0) {
            throw new CouponBusinessException("已有活动关联该模板，不能删除");
        }
        if (couponMapper.deleteTemplateIfUnused(id) != 1) {
            throw new CouponBusinessException("模板不存在或已启用，停用后再删除");
        }
        invalidateTemplateAfterCommit(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateTemplateStatus(Long id, Integer status) {
        if (status == null || (status != 0 && status != 1)) {
            throw new CouponBusinessException("模板状态只支持启用或停用");
        }
        CouponTemplate template = couponMapper.getTemplateForUpdate(id);
        if (template == null) throw new CouponBusinessException("优惠券模板不存在");
        if (couponMapper.countPendingClaimsByTemplate(id) > 0) {
            throw new CouponBusinessException("关联活动存在未完成领取任务，暂时不能修改模板状态");
        }
        template.setStatus(status);
        template.setUpdateUser(BaseContext.getCurrentId());
        if (couponMapper.updateTemplate(template) != 1) {
            throw new CouponBusinessException("更新优惠券模板状态失败");
        }
        invalidateTemplateAfterCommit(id);
    }

    @Override
    public PageResult pageActivities(CouponPageQueryDTO query) {
        normalizePage(query.getPage(), query.getPageSize());
        PageHelper.startPage(query.getPage(), query.getPageSize());
        Page<CouponActivityVO> page = couponMapper.pageActivities(query);
        return new PageResult(page.getTotal(), page.getResult());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveActivity(CouponActivityDTO dto) {
        validateActivity(dto);
        CouponTemplate template = couponMapper.getTemplate(dto.getTemplateId());
        if (template == null) throw new CouponBusinessException("关联优惠券模板不存在");
        CouponActivity activity = new CouponActivity();
        activity.setTemplateId(dto.getTemplateId());
        activity.setName(dto.getName().trim());
        activity.setStockTotal(dto.getStockTotal());
        activity.setStockRemaining(dto.getStockTotal());
        activity.setIssuedCount(0);
        activity.setUsedCount(0);
        activity.setPerUserLimit(dto.getPerUserLimit());
        activity.setStartTime(dto.getStartTime());
        activity.setEndTime(dto.getEndTime());
        activity.setStatus(0);
        activity.setCreateUser(BaseContext.getCurrentId());
        activity.setUpdateUser(BaseContext.getCurrentId());
        couponMapper.insertActivity(activity);
        invalidateActivityAfterCommit(activity.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @CouponDistributedLock(name = "coupon-activity", key = "#p0.id")
    public void updateActivity(CouponActivityDTO dto) {
        if (dto.getId() == null) throw new CouponBusinessException("活动编号不能为空");
        CouponActivity current = couponMapper.getActivityForUpdate(dto.getId());
        if (current == null) throw new CouponBusinessException("优惠券活动不存在");
        validateActivity(dto);
        if (!current.getTemplateId().equals(dto.getTemplateId())) {
            throw new CouponBusinessException("活动关联模板不可修改");
        }
        if (couponMapper.countPendingClaims(current.getId()) > 0) {
            throw new CouponBusinessException("活动存在未完成领取任务，暂时不能修改");
        }
        if (current.getIssuedCount() > 0 && !current.getStockTotal().equals(dto.getStockTotal())) {
            throw new CouponBusinessException("活动已发券后不能修改总库存");
        }
        CouponActivity update = new CouponActivity();
        update.setId(dto.getId());
        update.setName(dto.getName().trim());
        update.setPerUserLimit(dto.getPerUserLimit());
        update.setStartTime(dto.getStartTime());
        update.setEndTime(dto.getEndTime());
        update.setUpdateUser(BaseContext.getCurrentId());
        if (current.getIssuedCount() == 0) {
            update.setStockTotal(dto.getStockTotal());
            update.setStockRemaining(dto.getStockTotal());
        }
        if (couponMapper.updateActivity(update) != 1) {
            throw new CouponBusinessException("更新优惠券活动失败");
        }
        CouponActivity synchronizedActivity = new CouponActivity();
        synchronizedActivity.setId(current.getId());
        synchronizedActivity.setStatus(current.getStatus());
        synchronizedActivity.setStockTotal(current.getIssuedCount() == 0
                ? dto.getStockTotal() : current.getStockTotal());
        synchronizedActivity.setStockRemaining(current.getIssuedCount() == 0
                ? dto.getStockTotal() : current.getStockRemaining());
        synchronizedActivity.setStartTime(dto.getStartTime());
        synchronizedActivity.setEndTime(dto.getEndTime());
        synchronizedActivity.setPerUserLimit(dto.getPerUserLimit());
        syncActivityAfterCommit(synchronizedActivity, current.getIssuedCount() == 0);
        invalidateActivityAfterCommit(current.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @CouponDistributedLock(name = "coupon-activity", key = "#p0")
    public void updateActivityStatus(Long id, Integer status) {
        if (status == null || (status != 1 && status != 2)) {
            throw new CouponBusinessException("活动状态只支持开始或暂停");
        }
        CouponActivity activity = couponMapper.getActivityForUpdate(id);
        if (activity == null) throw new CouponBusinessException("优惠券活动不存在");
        int pendingClaims = couponMapper.countPendingClaims(id);
        if (status == 1) {
            CouponTemplate template = couponMapper.getTemplate(activity.getTemplateId());
            LocalDateTime now = LocalDateTime.now();
            if (template == null || template.getStatus() != 1) {
                throw new CouponBusinessException("请先启用关联优惠券模板");
            }
            if (activity.getStockRemaining() <= 0 || !activity.getEndTime().isAfter(now)) {
                throw new CouponBusinessException("活动已结束或库存不足，不能开始");
            }
        }
        if (couponMapper.updateActivityStatus(id, status) != 1) {
            throw new CouponBusinessException("更新优惠券活动状态失败");
        }
        activity.setStatus(status);
        if (pendingClaims == 0) syncActivityAfterCommit(activity, false);
        invalidateActivityAfterCommit(id);
    }

    @Override
    public List<CouponActivityVO> listAvailableActivities(Long userId) {
        return couponMapper.listAvailableActivities(userId, LocalDateTime.now());
    }

    @Override
    public CouponClaimStatusVO claim(Long userId, CouponClaimDTO dto) {
        if (userId == null) throw new CouponBusinessException("用户未登录");
        if (dto == null || dto.getActivityId() == null) throw new CouponBusinessException("活动编号不能为空");
        String requestId = dto.getRequestId();
        if (!StringUtils.hasText(requestId) || requestId.length() > 64 ||
                !requestId.matches("[A-Za-z0-9_-]{16,64}")) {
            throw new CouponBusinessException("领取请求编号无效");
        }

        CouponClaimRecord existing = couponMapper.getClaimByBusinessNo(requestId);
        if (existing != null) {
            return resumeExistingClaim(existing, userId, dto.getActivityId());
        }
        LocalDateTime now = LocalDateTime.now();
        CouponActivityRule activityRule = ruleCache.getActivityRule(
                dto.getActivityId(), () -> couponMapper.getActivityRule(dto.getActivityId()));
        if (activityRule == null) throw new CouponBusinessException("优惠券活动不存在");
        CouponActivity activityInventory = couponMapper.getActivityInventory(dto.getActivityId());
        if (activityInventory == null) throw new CouponBusinessException("优惠券活动不存在");
        CouponActivity activity = activityRule.toActivity(activityInventory);
        if (activity.getStatus() != 1 ||
                now.isBefore(activity.getStartTime()) || !now.isBefore(activity.getEndTime())) {
            throw new CouponBusinessException("活动当前不可领取");
        }
        CouponTemplate template = getTemplate(activity.getTemplateId());
        if (template == null || template.getStatus() != 1) {
            throw new CouponBusinessException("优惠券规则当前不可领取");
        }
        if (template.getValidityType() == 2 && !template.getValidEndTime().isAfter(now)) {
            throw new CouponBusinessException("优惠券已超过有效期，不能领取");
        }

        claimRedisService.prepareActivity(activity, couponMapper.countPendingClaims(activity.getId()));
        claimRedisService.initializeUserClaimCount(activity.getId(), userId,
                couponMapper.countSuccessfulClaims(activity.getId(), userId));
        CouponClaimRecord record;
        try {
            record = claimPersistenceService.createPendingRecord(requestId, activity.getId(), userId);
        } catch (DuplicateKeyException duplicateRequest) {
            CouponClaimRecord concurrent = couponMapper.getClaimByBusinessNo(requestId);
            if (concurrent == null) throw duplicateRequest;
            return resumeExistingClaim(concurrent, userId, activity.getId());
        }
        template = getTemplate(activity.getTemplateId());
        if (template == null || template.getStatus() != 1) {
            claimPersistenceService.markFailed(record.getId(), "优惠券规则在领取期间被停用");
            throw new CouponBusinessException("优惠券规则当前不可领取");
        }

        long reservation;
        try {
            reservation = claimRedisService.reserve(activity, userId, requestId, now);
        } catch (RuntimeException redisFailure) {
            log.error("优惠券Redis预占结果不确定，保留领取流水等待对账，activityId={}, businessNo={}",
                    activity.getId(), requestId, redisFailure);
            return toClaimStatus(record);
        }
        if (reservation != 1 && reservation != 2) {
            String failureReason = reservationFailureMessage(reservation);
            claimPersistenceService.markFailed(record.getId(), failureReason);
            record.setStatus(2);
            record.setFailureReason(failureReason);
            return toClaimStatus(record);
        }
        try {
            if (couponMapper.activateClaimDispatch(record.getId()) != 1) {
                CouponClaimRecord current = couponMapper.getClaimByBusinessNo(requestId);
                if (current != null && current.getStatus() == 2) {
                    compensateTerminalClaim(current);
                    return toClaimStatus(current);
                }
                log.warn("领取记录已预占但未能立即激活消息投递，等待定时恢复，businessNo={}", requestId);
            }
        } catch (RuntimeException activationFailure) {
            log.error("领取记录已预占但激活消息投递失败，等待定时恢复，businessNo={}",
                    requestId, activationFailure);
        }
        return toClaimStatus(record);
    }

    private CouponClaimStatusVO resumeExistingClaim(CouponClaimRecord record, Long userId, Long activityId) {
        if (!userId.equals(record.getUserId()) || !activityId.equals(record.getActivityId())) {
            throw new CouponBusinessException("领取请求编号已被使用");
        }
        return toClaimStatus(record);
    }

    @Override
    public CouponClaimStatusVO getClaimStatus(Long userId, String businessNo) {
        if (userId == null) throw new CouponBusinessException("用户未登录");
        if (!StringUtils.hasText(businessNo) || businessNo.length() > 64) {
            throw new CouponBusinessException("领取请求编号无效");
        }
        CouponClaimRecord record = couponMapper.getClaimByBusinessNo(businessNo);
        if (record == null || !userId.equals(record.getUserId())) {
            throw new CouponBusinessException("领取记录不存在");
        }
        return toClaimStatus(record);
    }

    private CouponClaimStatusVO toClaimStatus(CouponClaimRecord record) {
        CouponClaimStatusVO result = new CouponClaimStatusVO();
        result.setBusinessNo(record.getBusinessNo());
        result.setStatus(record.getStatus());
        result.setUserCouponId(record.getUserCouponId());
        result.setFailureReason(record.getFailureReason());
        return result;
    }

    private void compensateTerminalClaim(CouponClaimRecord record) {
        try {
            claimRedisService.compensateReservation(
                    record.getActivityId(), record.getUserId(), record.getBusinessNo());
        } catch (RuntimeException compensationFailure) {
            log.error("终止领取任务后的Redis库存补偿失败，businessNo={}",
                    record.getBusinessNo(), compensationFailure);
        }
    }

    private String reservationFailureMessage(long result) {
        return switch ((int) result) {
            case -1 -> "活动当前不可领取";
            case -2 -> "活动尚未开始或已结束";
            case -3 -> "优惠券已领完";
            case -4 -> "已达到该活动的个人领取上限";
            case -5 -> "优惠券活动缓存状态异常，请稍后重试";
            case -6 -> "领取请求编号已被其他用户使用";
            case -7 -> "活动库存正在对账，请稍后重试";
            default -> "优惠券领取失败";
        };
    }

    private void syncActivityAfterCommit(CouponActivity activity, boolean resetStock) {
        Runnable sync = () -> claimRedisService.syncActivity(activity, resetStock);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            sync.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                sync.run();
            }
        });
    }

    private void invalidateTemplateAfterCommit(Long templateId) {
        invalidateRuleAfterCommit(() -> ruleCache.invalidateTemplate(templateId),
                "templateId=" + templateId);
    }

    private void invalidateActivityAfterCommit(Long activityId) {
        invalidateRuleAfterCommit(() -> ruleCache.invalidateActivity(activityId),
                "activityId=" + activityId);
    }

    private void invalidateRuleAfterCommit(Runnable invalidation, String identity) {
        Runnable invalidate = () -> {
            try {
                invalidation.run();
            } catch (RuntimeException failure) {
                log.error("优惠券规则已变更，但缓存版本失效失败，{}；缓存读取将按TTL或数据库回源修复",
                        identity, failure);
            }
        };
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            invalidate.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                invalidate.run();
            }
        });
    }

    @Override
    public PageResult pageUserCoupons(Long userId, Integer pageNum, Integer pageSize, Integer status) {
        normalizePage(pageNum, pageSize);
        if (status != null && (status < 0 || status > 3)) {
            throw new CouponBusinessException("优惠券状态无效");
        }
        PageHelper.startPage(pageNum, pageSize);
        Page<UserCouponVO> page = couponMapper.pageUserCoupons(userId, status, LocalDateTime.now());
        return new PageResult(page.getTotal(), page.getResult());
    }

    private CouponTemplate toTemplate(CouponTemplateDTO dto) {
        CouponTemplate template = new CouponTemplate();
        template.setId(dto.getId());
        template.setName(dto.getName().trim());
        template.setCouponType(dto.getCouponType());
        template.setThresholdAmount(dto.getThresholdAmount());
        template.setDiscountAmount(dto.getDiscountAmount());
        template.setDiscountRate(dto.getDiscountRate());
        template.setMaxDiscountAmount(dto.getMaxDiscountAmount());
        template.setValidityType(dto.getValidityType());
        template.setValidityDays(dto.getValidityDays());
        template.setValidStartTime(dto.getValidStartTime());
        template.setValidEndTime(dto.getValidEndTime());
        template.setScopeType(dto.getScopeType());
        template.setScopeData(dto.getScopeType() == null || dto.getScopeType() == 1 ||
                dto.getScopeIds() == null ? null : JSON.toJSONString(dto.getScopeIds()));
        template.setStatus(dto.getStatus() == null ? 0 : dto.getStatus());
        return template;
    }

    private void validateTemplate(CouponTemplateDTO dto) {
        if (dto == null || !StringUtils.hasText(dto.getName()) || dto.getName().trim().length() > 100) {
            throw new CouponBusinessException("模板名称不能为空且不能超过100个字符");
        }
        if (dto.getCouponType() == null || dto.getCouponType() < 1 || dto.getCouponType() > 3) {
            throw new CouponBusinessException("优惠券类型无效");
        }
        requireNonNegative(dto.getThresholdAmount(), "优惠门槛");
        requireNonNegative(dto.getDiscountAmount(), "优惠金额");
        requireNonNegative(dto.getMaxDiscountAmount(), "优惠上限");
        if (dto.getCouponType() != 2 && dto.getDiscountAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new CouponBusinessException("优惠金额须大于0");
        }
        if (dto.getDiscountRate() == null || dto.getDiscountRate().compareTo(BigDecimal.ZERO) <= 0 ||
                dto.getDiscountRate().compareTo(BigDecimal.ONE) > 0) {
            throw new CouponBusinessException("折扣比例须大于0且不超过1");
        }
        if (dto.getValidityType() == null || (dto.getValidityType() != 1 && dto.getValidityType() != 2)) {
            throw new CouponBusinessException("有效期类型无效");
        }
        if (dto.getValidityType() == 1 && (dto.getValidityDays() == null || dto.getValidityDays() < 1)) {
            throw new CouponBusinessException("相对有效期至少为1天");
        }
        if (dto.getValidityType() == 2 && (dto.getValidStartTime() == null ||
                dto.getValidEndTime() == null || !dto.getValidEndTime().isAfter(dto.getValidStartTime()))) {
            throw new CouponBusinessException("固定有效期时间范围无效");
        }
        if (dto.getStatus() != null && dto.getStatus() != 0 && dto.getStatus() != 1) {
            throw new CouponBusinessException("模板状态只支持启用或停用");
        }
        if (dto.getScopeType() == null || dto.getScopeType() < 1 || dto.getScopeType() > 3) {
            throw new CouponBusinessException("适用范围无效");
        }
        if (dto.getScopeType() != 1 && (dto.getScopeIds() == null || dto.getScopeIds().isEmpty() ||
                dto.getScopeIds().stream().anyMatch(id -> id == null || id <= 0))) {
            throw new CouponBusinessException("指定适用范围时必须填写有效菜品或分类编号");
        }
    }

    private void validateActivity(CouponActivityDTO dto) {
        if (dto == null || dto.getTemplateId() == null) throw new CouponBusinessException("优惠券模板不能为空");
        if (!StringUtils.hasText(dto.getName()) || dto.getName().trim().length() > 100) {
            throw new CouponBusinessException("活动名称不能为空且不能超过100个字符");
        }
        if (dto.getStockTotal() == null || dto.getStockTotal() < 1) throw new CouponBusinessException("活动库存须大于0");
        if (dto.getPerUserLimit() == null || dto.getPerUserLimit() < 1) {
            throw new CouponBusinessException("每人限领数量须大于0");
        }
        if (dto.getStartTime() == null || dto.getEndTime() == null ||
                !dto.getEndTime().isAfter(dto.getStartTime())) {
            throw new CouponBusinessException("活动时间范围无效");
        }
    }

    private void requireNonNegative(BigDecimal amount, String label) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) < 0) {
            throw new CouponBusinessException(label + "不能小于0");
        }
    }

    private void normalizePage(Integer page, Integer pageSize) {
        if (page == null || page < 1 || pageSize == null || pageSize < 1 || pageSize > 100) {
            throw new CouponBusinessException("分页参数无效");
        }
    }
}
