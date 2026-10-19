import request from '@/utils/request'

export const getCouponTemplates = (params: Record<string, unknown>) =>
  request({ url: '/coupon/templates/page', method: 'get', params })

export const saveCouponTemplate = (data: Record<string, unknown>) =>
  request({ url: '/coupon/templates', method: 'post', data })

export const updateCouponTemplate = (data: Record<string, unknown>) =>
  request({ url: '/coupon/templates', method: 'put', data })

export const deleteCouponTemplate = (id: number) =>
  request({ url: `/coupon/templates/${id}`, method: 'delete' })

export const changeCouponTemplateStatus = (id: number, status: number) =>
  request({ url: `/coupon/templates/${id}/status/${status}`, method: 'post' })

export const getCouponActivities = (params: Record<string, unknown>) =>
  request({ url: '/coupon/activities/page', method: 'get', params })

export const saveCouponActivity = (data: Record<string, unknown>) =>
  request({ url: '/coupon/activities', method: 'post', data })

export const updateCouponActivity = (data: Record<string, unknown>) =>
  request({ url: '/coupon/activities', method: 'put', data })

export const changeCouponActivityStatus = (id: number, status: number) =>
  request({ url: `/coupon/activities/${id}/status/${status}`, method: 'post' })

export const generateCouponExchangeCodes = (data: {
  activityId: number
  count: number
  maxRedeemCount: number
  expiresAt?: string | null
}) => request({ url: '/coupon/exchange-codes', method: 'post', data })
