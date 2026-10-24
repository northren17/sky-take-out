import { request } from '@/api/request'

export interface ApiEnvelope<T> {
  code: number
  msg?: string
  data?: T
}

export interface Category {
  id: number
  name: string
  type: number
}

export interface MealProduct {
  id: number
  name: string
  price: number
  image?: string
  description?: string
  status?: number
  stock?: number
  categoryId?: number
  dishId?: number
  copies?: number
  contents?: Array<{ dishId?: number; name: string; copies: number; image?: string; description?: string }>
}

export interface ReservationLine {
  itemType: 1 | 2
  itemId: number
  quantity: number
}

export interface Reservation {
  id: number
  reservationNo: string
  mealDate: string
  mealPeriod: 1 | 2
  status: number
  remark?: string
  totalAmount: number
  items: Array<{
    id: number
    itemType: number
    itemId: number
    itemName: string
    image?: string
    quantity: number
    unitPrice: number
    subtotal: number
  }>
}

export interface ReviewOrder {
  id: number
  number: string
  orderTime: string
  amount: number
  orderDetails?: Array<{
    id: number
    name: string
    dishId?: number
    setmealId?: number
    image?: string
  }>
}

export interface ReviewEntry {
  id: number
  orderId: number
  orderNumber: string
  rating: number
  content?: string
  createTime: string
  images: Array<{ imageUrl: string }>
  items: Array<{ dishId: number; dishName: string; rating: number }>
}

export interface DishReview {
  reviewId: number
  userName: string
  rating: number
  content?: string
  createTime: string
  images: Array<{ imageUrl: string }>
}

export function listCategories() {
  return request.get<ApiEnvelope<Category[]>>('/user/category/list')
}

export function listDishes(categoryId: number) {
  return request.get<ApiEnvelope<MealProduct[]>>('/user/dish/list', { params: { categoryId } })
}

export function listSetmeals(categoryId: number) {
  return request.get<ApiEnvelope<MealProduct[]>>('/user/setmeal/list', { params: { categoryId } })
}

export function listSetmealDishes(id: number) {
  return request.get<ApiEnvelope<MealProduct[]>>(`/user/setmeal/dish/${id}`)
}

export function createReservation(data: {
  requestId: string
  mealDate: string
  mealPeriod: 1 | 2
  remark: string
  items: ReservationLine[]
}) {
  return request.post<ApiEnvelope<Reservation>>('/user/reservation', data)
}

export function listReservations(page = 1, pageSize = 20) {
  return request.get<ApiEnvelope<Reservation[]>>('/user/reservation/mine', {
    params: { page, pageSize },
  })
}

export function getReservationCapacity(date: string, period: 1 | 2) {
  return request.get<ApiEnvelope<{ maxReservations: number; enabled: number }>>(
    '/user/reservation/capacity',
    { params: { date, period } },
  )
}

export function cancelReservation(id: number) {
  return request.put<ApiEnvelope<never>>(`/user/reservation/${id}/cancel`)
}

export function listPendingReviewOrders(page = 1, pageSize = 10) {
  return request.get<ApiEnvelope<ReviewOrder[]>>('/user/review/pending', { params: { page, pageSize } })
}

export function submitOrderReview(data: {
  orderId: number
  rating: number
  content: string
  images: string[]
  items: Array<{ orderDetailId: number; dishId: number; rating: number }>
}) {
  return request.post<ApiEnvelope<never>>('/user/review', data)
}

export function listMyReviews(page = 1, pageSize = 10) {
  return request.get<ApiEnvelope<ReviewEntry[]>>('/user/review/mine', { params: { page, pageSize } })
}

export function listDishReviews(dishId: number, page = 1, pageSize = 10) {
  return request.get<ApiEnvelope<DishReview[]>>(`/user/review/dish/${dishId}`, {
    params: { page, pageSize },
  })
}

export function uploadReviewImage(file: File) {
  const form = new FormData()
  form.append('file', file)
  return request.post<ApiEnvelope<string>>('/user/review/upload', form, {
    headers: { 'Content-Type': 'multipart/form-data' },
  })
}
