import { request } from '@/api/request'

export interface CouponActivity {
  id: number
  templateId: number
  name: string
  couponType: number
  thresholdAmount: number
  discountAmount: number
  discountRate: number
  maxDiscountAmount: number
  validityType: number
  validityDays?: number
  validStartTime?: string
  validEndTime?: string
  scopeType: number
  scopeData?: string | null
  stockRemaining: number
  perUserLimit: number
  startTime: string
  endTime: string
  activityStatus: number
  claimedByCurrentUser: number
}

export interface UserCoupon {
  id: number
  activityId?: number
  templateId: number
  couponNameSnapshot: string
  couponTypeSnapshot: number
  thresholdAmountSnapshot: number
  discountAmountSnapshot: number
  discountRateSnapshot: number
  maxDiscountAmountSnapshot: number
  scopeTypeSnapshot: number
  scopeDataSnapshot?: string | null
  status: number
  acquiredTime: string
  validStartTime: string
  validEndTime: string
  lockedOrderId?: number | null
  usedOrderId?: number | null
  usedTime?: string | null
}

export interface CouponClaimStatus {
  businessNo: string
  status: 0 | 1 | 2
  userCouponId?: number | null
  failureReason?: string | null
}

interface CouponPage<T> {
  total: number
  records: T[]
}

export function getCouponActivities() {
  return request.get<{ code: number; data: CouponActivity[] }>('/user/coupon/activities')
}

export function claimCoupon(activityId: number, requestId: string) {
  return request.post<{ code: number; data: CouponClaimStatus }>('/user/coupon/claim', { activityId, requestId })
}

export function getCouponClaimStatus(requestId: string) {
  return request.get<{ code: number; data: CouponClaimStatus }>(
    `/user/coupon/claim/${encodeURIComponent(requestId)}`,
  )
}

export function redeemCoupon(code: string) {
  return request.post<{ code: number; data: CouponClaimStatus }>('/user/coupon/redeem', { code })
}

export function getMyCoupons(page: number, pageSize: number, status?: number) {
  return request.get<{ code: number; data: CouponPage<UserCoupon> }>('/user/coupon/mine', {
    params: { page, pageSize, status },
  })
}
