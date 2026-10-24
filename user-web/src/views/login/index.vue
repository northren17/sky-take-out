<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'
import {
  getCaptcha,
  userLogin,
  userLoginByPassword,
  userRegister,
  type CaptchaVo,
} from '@/api/modules/user'
import { SUCCESS_CODE } from '@/constants/business'
import { clearWeChatCodeFromUrl, isWeChatBrowser, tryGetWeChatToken } from '@/utils/wechat'

/**
 * 登录页
 *
 * 三种方式并存，全部走真实接口：
 *  1. 账号密码登录（网页版新增，/user/user/loginByPassword），带图形验证码；
 *  2. 账号注册（网页版新增，/user/user/register），注册成功直接进入应用；
 *  3. 微信授权登录（原小程序链路，/user/user/login，入参 code）——仅在地址上带有微信回调 code 时可用。
 */
const router = useRouter()
const route = useRoute()
const user = useUserStore()

const activeTab = ref<'login' | 'register'>('login')
const loading = ref(false)
const rememberAccount = ref(false)
const rememberedAccountKey = 'sky-user-remembered-account'

/** 验证码（图片 + captchaKey），登录与注册共用同一个验证码接口 */
const captcha = ref<CaptchaVo>({})
const captchaLoading = ref(false)

const loginForm = reactive({
  username: '',
  password: '',
  code: '',
})

const registerForm = reactive({
  username: '',
  password: '',
  confirmPassword: '',
  phone: '',
  name: '',
  code: '',
})

/** 微信链路 */
const wxCode = ref('')
const triedAuto = ref(false)
const inWeChat = isWeChatBrowser()

const captchaImage = computed(() =>
  captcha.value.image ? `data:image/png;base64,${captcha.value.image}` : '',
)

function redirectTarget(): string {
  const r = route.query.redirect
  if (typeof r === 'string' && r.startsWith('/')) return r
  return '/index'
}

/** 拉取（或刷新）图形验证码 */
async function refreshCaptcha() {
  captchaLoading.value = true
  try {
    const res = await getCaptcha()
    if (res.code === SUCCESS_CODE && res.data) {
      captcha.value = res.data
      loginForm.code = ''
      registerForm.code = ''
    } else {
      ElMessage.error(res.msg || '验证码获取失败')
    }
  } catch {
    // 请求层已统一提示
  } finally {
    captchaLoading.value = false
  }
}

function enterApp(token?: string, username?: string) {
  if (token) user.setToken(token)
  if (username) user.setAccount(username)
  return router.replace(redirectTarget())
}

function persistRememberedAccount() {
  if (rememberAccount.value) {
    localStorage.setItem(rememberedAccountKey, loginForm.username.trim())
  } else {
    localStorage.removeItem(rememberedAccountKey)
  }
}

function showForgotPasswordHelp() {
  ElMessage.info('暂不支持在线找回密码，请联系餐厅工作人员重置账号。')
}

function showLegalNotice(name: string) {
  ElMessage.info(`${name}内容暂未配置，请联系平台客服了解详情。`)
}

/* ------------------------------ 账号密码登录 ------------------------------ */

async function submitLogin() {
  if (!loginForm.username.trim()) {
    ElMessage.warning('请输入账号')
    return
  }
  if (!loginForm.password) {
    ElMessage.warning('请输入密码')
    return
  }
  if (!loginForm.code.trim()) {
    ElMessage.warning('请输入验证码')
    return
  }
  if (!captcha.value.captchaKey) {
    ElMessage.warning('验证码已失效，请点击图片刷新')
    return
  }

  loading.value = true
  try {
    const res = await userLoginByPassword({
      username: loginForm.username.trim(),
      password: loginForm.password,
      code: loginForm.code.trim(),
      captchaKey: captcha.value.captchaKey,
    })
    if (res.code === SUCCESS_CODE && res.data?.token) {
      persistRememberedAccount()
      ElMessage.success('登录成功')
      await enterApp(res.data.token, res.data.username ?? loginForm.username.trim())
      return
    }
    ElMessage.error(res.msg || '登录失败')
    await refreshCaptcha()
  } catch {
    // 验证码错误等业务失败：换一张验证码，方便直接重试
    await refreshCaptcha()
  } finally {
    loading.value = false
  }
}

/* -------------------------------- 注册 -------------------------------- */

async function submitRegister() {
  if (!registerForm.username.trim()) {
    ElMessage.warning('请输入账号')
    return
  }
  if (registerForm.username.trim().length < 4) {
    ElMessage.warning('账号长度至少 4 位')
    return
  }
  if (registerForm.password.length < 6) {
    ElMessage.warning('密码长度至少 6 位')
    return
  }
  if (registerForm.password !== registerForm.confirmPassword) {
    ElMessage.warning('两次输入的密码不一致')
    return
  }
  if (!registerForm.code.trim()) {
    ElMessage.warning('请输入验证码')
    return
  }
  if (!captcha.value.captchaKey) {
    ElMessage.warning('验证码已失效，请点击图片刷新')
    return
  }

  loading.value = true
  try {
    const res = await userRegister({
      username: registerForm.username.trim(),
      password: registerForm.password,
      confirmPassword: registerForm.confirmPassword,
      phone: registerForm.phone.trim() || undefined,
      name: registerForm.name.trim() || undefined,
      code: registerForm.code.trim(),
      captchaKey: captcha.value.captchaKey,
    })
    if (res.code === SUCCESS_CODE && res.data?.token) {
      ElMessage.success('注册成功，已自动登录')
      await enterApp(res.data.token, res.data.username ?? registerForm.username.trim())
      return
    }
    ElMessage.error(res.msg || '注册失败')
    await refreshCaptcha()
  } catch {
    await refreshCaptcha()
  } finally {
    loading.value = false
  }
}

/* ------------------------------ 微信授权登录 ------------------------------ */

async function loginByWeChatCode(code: string) {
  loading.value = true
  try {
    const res = await userLogin({ code })
    if (res.code === SUCCESS_CODE && res.data?.token) {
      user.setToken(res.data.token)
      ElMessage.success('登录成功')
      await router.replace(redirectTarget())
      return true
    }
    ElMessage.error('微信登录未返回 token')
    return false
  } catch {
    // 请求层已统一提示错误
    return false
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  const rememberedAccount = localStorage.getItem(rememberedAccountKey)
  if (rememberedAccount) {
    loginForm.username = rememberedAccount
    rememberAccount.value = true
  }
  await refreshCaptcha()

  wxCode.value = tryGetWeChatToken()
  if (!wxCode.value) return
  triedAuto.value = true
  const ok = await loginByWeChatCode(wxCode.value)
  if (ok) clearWeChatCodeFromUrl()
})

function useWeChatLogin() {
  if (!wxCode.value) {
    ElMessage.info('当前地址上没有微信授权 code，无法自动登录')
    return
  }
  void loginByWeChatCode(wxCode.value)
}

function switchTab(tab: 'login' | 'register') {
  activeTab.value = tab
  void refreshCaptcha()
}
</script>

<template>
  <div class="login-page">
    <main class="login-main">
      <section class="login-showcase" aria-label="智享云膳平台介绍">
        <div class="showcase-content">
          <div class="login-brand">
            <img src="/static/brand-mark.svg" alt="" />
            <div>
              <h1>智享云膳</h1>
              <p>智慧餐饮服务平台</p>
            </div>
          </div>
          <div class="showcase-copy">
            <span class="showcase-eyebrow">SMART DINING · FRESH DAILY</span>
            <h2>新鲜好味，<br />即刻享。</h2>
            <p>在线点餐、预约订餐<br />一站式智慧食堂平台</p>
            <div class="showcase-features" aria-label="平台服务">
              <span>在线点餐</span>
              <i />
              <span>预约订餐</span>
              <i />
              <span>便捷生活</span>
            </div>
          </div>
        </div>
        <svg
          class="food-illustration"
          viewBox="0 0 560 500"
          fill="none"
          aria-hidden="true"
          focusable="false"
        >
          <circle cx="294" cy="251" r="178" class="illustration-halo" />
          <circle cx="294" cy="251" r="144" class="illustration-plate" />
          <circle cx="294" cy="251" r="121" class="illustration-plate-inner" />
          <path d="M232 266c14-48 48-76 97-82 9 41-5 84-40 110-23 17-46 19-68-28Z" class="illustration-leaf" />
          <path d="M255 278c21-27 43-47 68-65" class="illustration-vein" />
          <path d="M306 309c5-33 23-54 56-63 9 28-1 57-25 74-13 9-24 8-31-11Z" class="illustration-leaf-secondary" />
          <path d="M318 314c14-20 26-32 42-43" class="illustration-vein" />
          <circle cx="245" cy="218" r="8" class="illustration-dot" />
          <circle cx="351" cy="237" r="6" class="illustration-dot-secondary" />
          <path d="M107 323c-22-23-25-52-7-82 28 12 44 34 43 63-1 17-12 24-36 19Z" class="illustration-leaf" />
          <path d="M118 323c4-24 10-43 24-62" class="illustration-vein" />
          <path d="M412 130c-5-28 8-51 39-68 19 23 21 50 5 75-9 14-22 13-44-7Z" class="illustration-leaf-secondary" />
          <path d="M419 135c13-20 25-34 44-47" class="illustration-vein" />
          <path d="M396 364c19-17 44-18 73-1-8 27-28 43-56 43-16 0-22-12-17-42Z" class="illustration-leaf" />
          <path d="M397 379c22 1 40 5 61 17" class="illustration-vein" />
          <path d="M148 145c9-12 18-20 31-27M390 91c8-12 16-20 27-27M448 284c12 0 22 2 33 7" class="illustration-spark" />
          <circle cx="165" cy="106" r="4" class="illustration-spark-dot" />
          <circle cx="455" cy="226" r="5" class="illustration-spark-dot" />
        </svg>
        <div class="showcase-caption">
          <span class="caption-mark">S</span>
          <span>用心烹饪每一餐</span>
        </div>
      </section>

      <section class="login-panel" aria-label="账号登录">
        <div class="login-content">
          <div class="login-card">
            <div class="card-heading">
              <span class="card-eyebrow">WELCOME BACK</span>
              <h2>{{ activeTab === 'login' ? '欢迎回来' : '创建新账号' }}</h2>
              <p>{{ activeTab === 'login' ? '登录后开启美好用餐体验' : '注册账号，立即体验便捷点餐' }}</p>
            </div>

            <div class="login-tabs" role="tablist" aria-label="登录或注册">
              <button
                class="login-tab"
                :class="{ active: activeTab === 'login' }"
                type="button"
                role="tab"
                :aria-selected="activeTab === 'login'"
                @click="switchTab('login')"
              >
                登录
              </button>
              <button
                class="login-tab"
                :class="{ active: activeTab === 'register' }"
                type="button"
                role="tab"
                :aria-selected="activeTab === 'register'"
                @click="switchTab('register')"
              >
                注册
              </button>
            </div>

            <form v-if="activeTab === 'login'" class="login-form" @submit.prevent="submitLogin">
              <label class="login-label" for="login-username">账号</label>
              <el-input
                id="login-username"
                v-model="loginForm.username"
                size="large"
                placeholder="请输入账号"
                autocomplete="username"
              >
                <template #prefix><el-icon><User /></el-icon></template>
              </el-input>

              <label class="login-label" for="login-password">密码</label>
              <el-input
                id="login-password"
                v-model="loginForm.password"
                size="large"
                type="password"
                placeholder="请输入密码"
                show-password
                autocomplete="current-password"
              >
                <template #prefix><el-icon><Lock /></el-icon></template>
              </el-input>

              <label class="login-label" for="login-code">验证码</label>
              <div class="login-captcha">
                <el-input
                  id="login-code"
                  v-model="loginForm.code"
                  size="large"
                  placeholder="请输入验证码"
                  maxlength="4"
                  class="login-captcha-input"
                >
                  <template #prefix><el-icon><Key /></el-icon></template>
                </el-input>
                <img v-if="captchaImage" class="captcha-image" :src="captchaImage" alt="图形验证码" />
                <span v-else class="captcha-image captcha-image-empty">验证码</span>
                <el-button
                  native-type="button"
                  class="captcha-refresh"
                  text
                  :disabled="captchaLoading"
                  @click="refreshCaptcha"
                >
                  换一张
                </el-button>
              </div>

              <div class="login-options">
                <el-checkbox v-model="rememberAccount">记住账号</el-checkbox>
                <button class="forgot-password" type="button" @click="showForgotPasswordHelp">
                  忘记密码？
                </button>
              </div>

              <el-button
                type="primary"
                native-type="submit"
                :loading="loading"
                class="login-submit"
              >
                登录
                <el-icon v-if="!loading"><ArrowRight /></el-icon>
              </el-button>
            </form>

            <form v-else class="login-form" @submit.prevent="submitRegister">
              <label class="login-label" for="reg-username">账号</label>
              <el-input
                id="reg-username"
                v-model="registerForm.username"
                size="large"
                placeholder="4~32 位，注册后不可修改"
                autocomplete="username"
              />

              <label class="login-label" for="reg-password">密码</label>
              <el-input
                id="reg-password"
                v-model="registerForm.password"
                size="large"
                type="password"
                placeholder="6~32 位"
                show-password
                autocomplete="new-password"
              />

              <label class="login-label" for="reg-confirm">确认密码</label>
              <el-input
                id="reg-confirm"
                v-model="registerForm.confirmPassword"
                size="large"
                type="password"
                placeholder="请再次输入密码"
                show-password
                autocomplete="new-password"
              />

              <label class="login-label" for="reg-phone">手机号（选填）</label>
              <el-input
                id="reg-phone"
                v-model="registerForm.phone"
                size="large"
                placeholder="用于配送联系"
                maxlength="11"
              />

              <label class="login-label" for="reg-name">昵称（选填）</label>
              <el-input
                id="reg-name"
                v-model="registerForm.name"
                size="large"
                placeholder="默认与账号相同"
              />

              <label class="login-label" for="reg-code">验证码</label>
              <div class="login-captcha">
                <el-input
                  id="reg-code"
                  v-model="registerForm.code"
                  size="large"
                  placeholder="请输入验证码"
                  maxlength="4"
                  class="login-captcha-input"
                />
                <img
                  v-if="captchaImage"
                  class="captcha-image"
                  :src="captchaImage"
                  alt="图形验证码"
                />
                <span v-else class="captcha-image captcha-image-empty">验证码</span>
                <el-button
                  class="captcha-refresh"
                  text
                  native-type="button"
                  :disabled="captchaLoading"
                  @click="refreshCaptcha"
                >
                  换一张
                </el-button>
              </div>

              <el-button
                type="primary"
                native-type="submit"
                :loading="loading"
                class="login-submit"
              >
                注册并登录
                <el-icon v-if="!loading"><ArrowRight /></el-icon>
              </el-button>
            </form>

            <p class="legal-copy">
              {{ activeTab === 'login' ? '登录' : '注册' }}即代表同意
              <button type="button" class="legal-link" @click="showLegalNotice('用户协议')">
                《用户协议》
              </button>
              <button type="button" class="legal-link" @click="showLegalNotice('隐私政策')">
                《隐私政策》
              </button>
            </p>
          </div>

          <div v-if="wxCode" class="wechat-card">
            <p class="login-tip">检测到微信授权 code，可直接完成登录。</p>
            <el-button type="primary" :loading="loading" class="login-submit" @click="useWeChatLogin">
              微信授权登录
            </el-button>
            <p v-if="triedAuto && !loading" class="login-note">
              若自动登录失败，可使用上方账号密码登录。
            </p>
          </div>
          <p v-if="inWeChat" class="wechat-note">
            当前在微信内置浏览器中，如未配置网页授权回调，请使用账号密码登录。
          </p>
        </div>
      </section>
    </main>

    <footer class="login-footer">
      <span>© {{ new Date().getFullYear() }} 智享云膳</span>
      <i />
      <span>用心烹饪每一餐</span>
    </footer>
  </div>
</template>

<style scoped>
.login-page {
  --login-green: #176b50;
  --login-green-dark: #10563f;
  display: flex;
  min-height: 100vh;
  min-height: 100dvh;
  flex-direction: column;
  background: #fff;
  color: #25372e;
}

.login-main {
  display: grid;
  flex: 1;
  grid-template-columns: minmax(0, 1.04fr) minmax(460px, 0.96fr);
  min-height: 680px;
}

.login-showcase {
  position: relative;
  display: flex;
  min-width: 0;
  flex-direction: column;
  justify-content: space-between;
  overflow: hidden;
  padding: clamp(40px, 6vw, 86px) clamp(42px, 7vw, 112px) 30px;
  background:
    radial-gradient(ellipse at 78% 30%, rgb(255 255 255 / 42%), transparent 30%),
    linear-gradient(140deg, #eef7ec 0%, #e3f1e4 48%, #d9ecdf 100%);
}

.login-showcase::before,
.login-showcase::after {
  position: absolute;
  width: 280px;
  height: 280px;
  border: 1px solid rgb(36 112 77 / 9%);
  border-radius: 50%;
  content: '';
  pointer-events: none;
}

.login-showcase::before {
  top: -140px;
  left: -110px;
}

.login-showcase::after {
  right: -175px;
  bottom: -165px;
  width: 380px;
  height: 380px;
}

.showcase-content {
  position: relative;
  z-index: 2;
}

.login-brand {
  display: flex;
  align-items: center;
  gap: 14px;
}

.login-brand img {
  width: 52px;
  height: 52px;
  padding: 4px;
  border: 1px solid rgb(255 255 255 / 72%);
  border-radius: 16px;
  background: rgb(255 255 255 / 72%);
  box-shadow: 0 8px 24px rgb(36 91 57 / 9%);
  object-fit: contain;
}

.login-brand h1 {
  margin: 0;
  color: #194c38;
  font-size: 20px;
  font-weight: 750;
  letter-spacing: 0.04em;
}

.login-brand p {
  max-width: none;
  margin: 3px 0 0;
  color: #718778;
  font-size: 12px;
  letter-spacing: 0.06em;
}

.showcase-copy {
  position: relative;
  z-index: 2;
  margin-top: clamp(76px, 10vh, 130px);
}

.showcase-eyebrow,
.card-eyebrow {
  color: #64836d;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.2em;
}

.showcase-copy h2 {
  margin-top: 20px;
  color: #1c4c37;
  font-size: clamp(42px, 5vw, 68px);
  font-weight: 700;
  letter-spacing: -0.055em;
  line-height: 1.22;
}

.showcase-copy > p {
  max-width: none;
  margin-top: 18px;
  color: #5d7967;
  font-size: 17px;
  line-height: 1.9;
}

.showcase-features {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-top: 28px;
  color: #42664f;
  font-size: 12px;
  font-weight: 600;
}

.showcase-features i,
.login-footer i {
  width: 3px;
  height: 3px;
  border-radius: 50%;
  background: #8aab91;
}

.food-illustration {
  position: absolute;
  right: 0;
  bottom: 3%;
  width: min(63%, 570px);
  height: auto;
  overflow: visible;
  opacity: 0.94;
}

.illustration-halo {
  fill: rgb(255 255 255 / 23%);
}

.illustration-plate {
  fill: #f9fcf6;
  stroke: #c7dfca;
  stroke-width: 2;
}

.illustration-plate-inner {
  stroke: #d6e7d5;
  stroke-width: 2;
}

.illustration-leaf {
  fill: #82ae79;
}

.illustration-leaf-secondary {
  fill: #b3c879;
}

.illustration-vein {
  stroke: #527f55;
  stroke-linecap: round;
  stroke-width: 3;
}

.illustration-dot {
  fill: #e9b96d;
}

.illustration-dot-secondary {
  fill: #d8946b;
}

.illustration-spark {
  stroke: #7ca783;
  stroke-linecap: round;
  stroke-width: 3;
}

.illustration-spark-dot {
  fill: #d8ae67;
}

.showcase-caption {
  position: relative;
  z-index: 2;
  display: flex;
  align-items: center;
  gap: 9px;
  color: #6d8875;
  font-size: 11px;
  letter-spacing: 0.08em;
}

.caption-mark {
  display: grid;
  width: 22px;
  height: 22px;
  place-items: center;
  border: 1px solid #b7d2bd;
  border-radius: 50%;
  color: #397350;
  font-family: Georgia, serif;
  font-size: 12px;
  font-style: italic;
}

.login-panel {
  display: grid;
  min-width: 0;
  place-items: center;
  padding: 48px clamp(28px, 5vw, 82px);
  background:
    radial-gradient(ellipse at 100% 0%, #f4f8f4 0, transparent 33%),
    #fff;
}

.login-content {
  width: min(100%, 430px);
}

.login-card {
  width: 100%;
  padding: 38px 40px 26px;
  border: 1px solid #edf1ed;
  border-radius: 18px;
  background: #fff;
  box-shadow: 0 22px 65px rgb(34 67 46 / 9%), 0 3px 10px rgb(34 67 46 / 3%);
}

.card-heading {
  margin-bottom: 25px;
}

.card-eyebrow {
  color: #79957f;
  font-size: 9px;
  letter-spacing: 0.19em;
}

.card-heading h2 {
  margin-top: 10px;
  color: #26382e;
  font-size: 27px;
  font-weight: 700;
  letter-spacing: -0.035em;
}

.card-heading p {
  max-width: none;
  margin-top: 7px;
  color: #8a948d;
  font-size: 13px;
}

.login-tabs {
  display: flex;
  gap: 28px;
  margin-bottom: 20px;
  border-bottom: 1px solid #edf0ed;
}

.login-tab {
  position: relative;
  min-width: 54px;
  padding: 0 0 12px;
  border: 0;
  background: transparent;
  color: #929a94;
  font-size: 14px;
  font-weight: 550;
  transition: color 160ms ease;
}

.login-tab.active {
  color: var(--login-green);
  font-weight: 700;
}

.login-tab.active::after {
  position: absolute;
  right: 0;
  bottom: -1px;
  left: 0;
  height: 2px;
  border-radius: 2px 2px 0 0;
  background: var(--login-green);
  content: '';
}

.login-form {
  display: block;
}

.login-label {
  display: block;
  margin: 15px 0 7px;
  color: #47574d;
  font-size: 12px;
  font-weight: 650;
}

.login-label:first-child {
  margin-top: 0;
}

.login-form :deep(.el-input__wrapper) {
  min-height: 46px;
  padding: 1px 13px;
  border-radius: 9px;
  background: #fbfcfb;
  box-shadow: 0 0 0 1px #e3eae4 inset;
  transition: background 160ms ease, box-shadow 160ms ease;
}

.login-form :deep(.el-input__wrapper:hover) {
  box-shadow: 0 0 0 1px #b8d0be inset;
}

.login-form :deep(.el-input__wrapper.is-focus) {
  background: #fff;
  box-shadow: 0 0 0 1px var(--login-green) inset, 0 0 0 3px rgb(23 107 80 / 9%);
}

.login-form :deep(.el-input__inner) {
  color: #34463b;
  font-size: 13px;
}

.login-form :deep(.el-input__inner::placeholder) {
  color: #adb6af;
}

.login-form :deep(.el-input__prefix-inner > .el-icon) {
  color: #9aa99e;
  font-size: 16px;
}

.login-captcha {
  display: flex;
  align-items: center;
  gap: 8px;
}

.login-captcha-input {
  flex: 1;
  min-width: 0;
}

.captcha-image {
  display: block;
  flex: 0 0 100px;
  width: 100px;
  height: 44px;
  border: 1px solid #e4eae4;
  border-radius: 8px;
  background: #f6f9f6;
  object-fit: cover;
}

.captcha-image-empty {
  display: grid;
  place-items: center;
  color: #97a39a;
  font-size: 11px;
}

.captcha-refresh {
  flex: 0 0 auto;
  padding: 0 2px;
  color: var(--login-green);
  font-size: 11px;
}

.captcha-refresh:hover {
  color: var(--login-green-dark);
}

.login-options {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 14px;
}

.login-options :deep(.el-checkbox__label) {
  padding-left: 7px;
  color: #737e76;
  font-size: 12px;
}

.login-options :deep(.el-checkbox__input.is-checked .el-checkbox__inner) {
  border-color: var(--login-green);
  background: var(--login-green);
}

.forgot-password,
.legal-link {
  padding: 0;
  background: transparent;
  color: var(--login-green);
  font-size: 12px;
}

.forgot-password:hover,
.legal-link:hover {
  color: var(--login-green-dark);
  text-decoration: underline;
  text-underline-offset: 3px;
}

.login-submit {
  display: flex;
  width: 100%;
  height: 48px;
  justify-content: center;
  gap: 9px;
  margin-top: 22px;
  border: 0;
  border-radius: 9px;
  background: linear-gradient(100deg, #1a7859, #176b50);
  box-shadow: 0 8px 18px rgb(23 107 80 / 17%);
  font-size: 14px;
  font-weight: 650;
  letter-spacing: 0.04em;
  transition: box-shadow 180ms ease, transform 300ms var(--sky-press-release), background 180ms ease;
}

.login-submit:hover {
  transform: translateY(-1px);
  background: linear-gradient(100deg, #176b50, #10563f);
  box-shadow: 0 11px 22px rgb(23 107 80 / 23%);
}

.login-submit:active {
  transform: scale(0.96);
  transition-duration: 90ms;
  transition-timing-function: cubic-bezier(0.2, 0.7, 0.3, 1);
}

.legal-copy {
  max-width: none;
  margin-top: 22px;
  color: #9ba49d;
  font-size: 10px;
  line-height: 1.7;
  text-align: center;
}

.legal-link {
  font-size: inherit;
}

.wechat-card {
  margin-top: 16px;
  padding: 18px 22px;
  border: 1px solid #e8eee9;
  border-radius: 12px;
  background: #fff;
}

.login-tip,
.login-note,
.wechat-note {
  max-width: none;
  margin: 0;
  color: #78857b;
  font-size: 12px;
  line-height: 1.7;
}

.login-note {
  margin-top: 9px;
}

.wechat-note {
  margin-top: 16px;
  text-align: center;
}

.login-footer {
  display: flex;
  min-height: 52px;
  align-items: center;
  justify-content: center;
  gap: 12px;
  border-top: 1px solid #f0f2ef;
  color: #9ba49d;
  font-size: 10px;
}

.login-footer i {
  background: #b3beb5;
}

@media (max-width: 1050px) {
  .login-main {
    grid-template-columns: minmax(0, 0.9fr) minmax(430px, 1.1fr);
  }

  .login-showcase {
    padding-right: 42px;
    padding-left: 42px;
  }

  .food-illustration {
    right: -8%;
    width: 76%;
  }

  .login-panel {
    padding-inline: 32px;
  }
}

@media (max-width: 820px) {
  .login-main {
    grid-template-columns: minmax(0, 1fr);
    min-height: 0;
  }

  .login-showcase {
    min-height: 340px;
    padding: 32px 38px 25px;
  }

  .showcase-copy {
    margin-top: 44px;
  }

  .showcase-copy h2 {
    margin-top: 12px;
    font-size: clamp(36px, 7vw, 48px);
  }

  .showcase-copy > p {
    margin-top: 10px;
    font-size: 14px;
    line-height: 1.65;
  }

  .showcase-features {
    margin-top: 16px;
  }

  .food-illustration {
    right: 1%;
    bottom: -8%;
    width: min(44%, 300px);
  }

  .showcase-caption {
    display: none;
  }

  .login-panel {
    padding: 44px 24px 54px;
  }

  .login-content {
    width: min(100%, 440px);
  }

  .login-footer {
    min-height: 48px;
  }
}

@media (max-width: 520px) {
  .login-showcase {
    min-height: 300px;
    padding: 24px 23px 22px;
  }

  .login-brand {
    gap: 11px;
  }

  .login-brand img {
    width: 44px;
    height: 44px;
    border-radius: 13px;
  }

  .login-brand h1 {
    font-size: 17px;
  }

  .login-brand p {
    font-size: 10px;
  }

  .showcase-copy {
    margin-top: 35px;
  }

  .showcase-eyebrow {
    font-size: 8px;
  }

  .showcase-copy h2 {
    font-size: 38px;
  }

  .showcase-copy > p {
    font-size: 12px;
  }

  .showcase-features {
    gap: 9px;
    font-size: 10px;
  }

  .food-illustration {
    right: -17%;
    bottom: -10%;
    width: 58%;
    opacity: 0.55;
  }

  .login-panel {
    padding: 30px 16px 40px;
  }

  .login-card {
    padding: 28px 22px 22px;
    border-radius: 15px;
  }

  .card-heading {
    margin-bottom: 21px;
  }

  .card-heading h2 {
    font-size: 24px;
  }

  .login-captcha {
    gap: 6px;
  }

  .captcha-image {
    flex-basis: 82px;
    width: 82px;
  }

  .captcha-refresh {
    font-size: 10px;
  }

  .login-footer {
    min-height: 46px;
    gap: 8px;
    font-size: 9px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .login-tab,
  .login-form :deep(.el-input__wrapper),
  .login-submit {
    transition: none;
  }
}
</style>
