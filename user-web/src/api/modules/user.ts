/**
 * 用户 / 会话相关接口
 *
 * 说明：网页版新增了「账号密码注册 / 登录」链路（后端 /user/user/register、/user/user/loginByPassword），
 * 与原有微信登录（/user/user/login，入参 code）并存，互不影响。
 * 这里使用本地类型定义，不依赖 openapi.d.ts（该文件由后端接口文档生成，尚未包含新接口）。
 */
import { request, type ApiEnvelope } from '@/api/request'

/* -------------------------------------------------------------------------- */
/* 类型                                                                        */
/* -------------------------------------------------------------------------- */

/** 登录返回的 data */
export interface UserLoginVo {
  id?: number
  openid?: string
  token?: string
  /** 登录账号（账号密码登录时返回） */
  username?: string
  /** 姓名/昵称 */
  name?: string
}

/** 图形验证码 */
export interface CaptchaVo {
  /** 验证码标识，登录/注册时原样回传 */
  captchaKey?: string
  /** Base64 图片（需自行拼 data:image/png;base64, 前缀） */
  image?: string
  /** 有效期（秒） */
  expireIn?: number
}

/** 登录入参：文档中 `UserLoginDTO` 只有 `code` 一个字段（微信授权码） */
export interface UserLoginDTO {
  code: string
}

/** 账号密码登录入参 */
export interface UserPasswordLoginDTO {
  username: string
  password: string
  /** 用户填写的验证码 */
  code: string
  /** 验证码标识 */
  captchaKey: string
}

/** 注册入参 */
export interface UserRegisterDTO {
  username: string
  password: string
  confirmPassword: string
  phone?: string
  name?: string
  code: string
  captchaKey: string
}

/** 当前登录用户信息 */
export interface UserInfoVo {
  id?: number
  username?: string
  name?: string
  phone?: string
  sex?: string
  avatar?: string
  createTime?: string
}

/** One-time credential for a single authenticated voice connection */
export interface VoiceTicketVo {
  ticket: string
  expiresAt: string
  expiresInSeconds: number
}

/* -------------------------------------------------------------------------- */
/* 接口                                                                        */
/* -------------------------------------------------------------------------- */

/**
 * `GET /user/user/captcha`
 * 获取图形验证码（无需登录）
 */
export function getCaptcha() {
  return request.get<ApiEnvelope<CaptchaVo>>('/user/user/captcha')
}

/**
 * `POST /user/user/register`
 * 账号注册（无需登录），成功后直接返回 token，无需再登录一次
 */
export function userRegister(params: UserRegisterDTO) {
  return request.post<ApiEnvelope<UserLoginVo>>('/user/user/register', params)
}

/**
 * `POST /user/user/loginByPassword`
 * 账号密码登录（无需登录）
 */
export function userLoginByPassword(params: UserPasswordLoginDTO) {
  return request.post<ApiEnvelope<UserLoginVo>>('/user/user/loginByPassword', params)
}

/**
 * `GET /user/user/info`
 * 查询当前登录用户信息（需要 authentication 请求头）
 */
export function getUserInfo() {
  return request.get<ApiEnvelope<UserInfoVo>>('/user/user/info')
}

/**
 * `POST /user/user/login`
 * 微信登录：小程序里 `code` 来自 `uni.login()`；网页端见 `src/utils/wechat.ts` 的降级说明。
 */
export function userLogin(params: UserLoginDTO) {
  return request.post<ApiEnvelope<UserLoginVo>>('/user/user/login', params)
}

/**
 * `POST /user/user/logout`
 * 退出登录：服务端会删除 Redis 中保存的令牌，使当前 token 立即失效。
 */
export function userLogout() {
  return request.post<ApiEnvelope<string>>('/user/user/logout', {})
}

/** `POST /user/voice-sessions/ticket` — issue a short-lived, one-time ticket */
export function issueVoiceTicket() {
  return request.post<ApiEnvelope<VoiceTicketVo>>('/user/voice-sessions/ticket', {})
}
