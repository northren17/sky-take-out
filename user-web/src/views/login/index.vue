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
    <div class="login-brand">
      <img src="/static/logo_ruiji.png" alt="苍穹外卖" />
      <h1>苍穹外卖</h1>
      <p>用户端网页版</p>
    </div>

    <div class="login-card">
      <div class="login-tabs">
        <button
          class="login-tab"
          :class="{ active: activeTab === 'login' }"
          type="button"
          @click="switchTab('login')"
        >
          登录
        </button>
        <button
          class="login-tab"
          :class="{ active: activeTab === 'register' }"
          type="button"
          @click="switchTab('register')"
        >
          注册
        </button>
      </div>

      <!-- 登录 -->
      <form v-if="activeTab === 'login'" class="login-form" @submit.prevent="submitLogin">
        <label class="login-label" for="login-username">账号</label>
        <el-input
          id="login-username"
          v-model="loginForm.username"
          placeholder="请输入账号"
          autocomplete="username"
        />

        <label class="login-label" for="login-password">密码</label>
        <el-input
          id="login-password"
          v-model="loginForm.password"
          type="password"
          placeholder="请输入密码"
          show-password
          autocomplete="current-password"
        />

        <label class="login-label" for="login-code">验证码</label>
        <div class="login-captcha">
          <el-input
            id="login-code"
            v-model="loginForm.code"
            placeholder="请输入验证码"
            maxlength="4"
            class="login-captcha-input"
          />
          <button
            class="captcha-box"
            type="button"
            :disabled="captchaLoading"
            title="点击刷新验证码"
            @click="refreshCaptcha"
          >
            <img v-if="captchaImage" :src="captchaImage" alt="验证码" />
            <span v-else class="captcha-empty">点击获取</span>
          </button>
        </div>

        <el-button type="primary" native-type="submit" :loading="loading" class="login-submit">
          登录
        </el-button>
      </form>

      <!-- 注册 -->
      <form v-else class="login-form" @submit.prevent="submitRegister">
        <label class="login-label" for="reg-username">账号</label>
        <el-input
          id="reg-username"
          v-model="registerForm.username"
          placeholder="4~32 位，注册后不可修改"
          autocomplete="username"
        />

        <label class="login-label" for="reg-password">密码</label>
        <el-input
          id="reg-password"
          v-model="registerForm.password"
          type="password"
          placeholder="6~32 位"
          show-password
          autocomplete="new-password"
        />

        <label class="login-label" for="reg-confirm">确认密码</label>
        <el-input
          id="reg-confirm"
          v-model="registerForm.confirmPassword"
          type="password"
          placeholder="请再次输入密码"
          show-password
          autocomplete="new-password"
        />

        <label class="login-label" for="reg-phone">手机号（选填）</label>
        <el-input id="reg-phone" v-model="registerForm.phone" placeholder="用于配送联系" maxlength="11" />

        <label class="login-label" for="reg-name">昵称（选填）</label>
        <el-input id="reg-name" v-model="registerForm.name" placeholder="默认与账号相同" />

        <label class="login-label" for="reg-code">验证码</label>
        <div class="login-captcha">
          <el-input
            id="reg-code"
            v-model="registerForm.code"
            placeholder="请输入验证码"
            maxlength="4"
            class="login-captcha-input"
          />
          <button
            class="captcha-box"
            type="button"
            :disabled="captchaLoading"
            title="点击刷新验证码"
            @click="refreshCaptcha"
          >
            <img v-if="captchaImage" :src="captchaImage" alt="验证码" />
            <span v-else class="captcha-empty">点击获取</span>
          </button>
        </div>

        <el-button type="primary" native-type="submit" :loading="loading" class="login-submit">
          注册并登录
        </el-button>
      </form>
    </div>

    <!-- 微信链路：仅当地址上带回 code 时展示 -->
    <div v-if="wxCode" class="login-card">
      <p class="login-tip">检测到微信授权 code，可直接完成登录。</p>
      <el-button type="primary" :loading="loading" class="login-submit" @click="useWeChatLogin">
        微信授权登录
      </el-button>
      <p v-if="triedAuto && !loading" class="login-note">若自动登录失败，可使用上方账号密码登录。</p>
    </div>

    <p class="login-note login-note-foot">
      <template v-if="inWeChat">
        当前在微信内置浏览器中，如未配置网页授权回调，请使用账号密码登录。
      </template>
      <template v-else>
        登录后即可浏览菜单、管理地址并查看订单。
      </template>
    </p>
  </div>
</template>

<style scoped>
.login-page {
  min-height: 100vh;
  padding: 40px 20px;
  background: #fff;
}

.login-brand {
  margin-bottom: 28px;
  text-align: center;
}

.login-brand img {
  width: 72px;
  height: 72px;
  margin: 0 auto 10px;
  object-fit: contain;
}

.login-brand h1 {
  margin: 0;
  font-size: 22px;
  color: var(--sky-primary);
}

.login-brand p {
  margin: 4px 0 0;
  color: var(--sky-text-weak);
  font-size: 13px;
}

.login-card {
  max-width: 480px;
  margin: 0 auto 16px;
  padding: 24px;
  background: #fff;
  border: 1px solid var(--sky-border);
  border-radius: 12px;
  box-shadow: 0 4px 12px rgb(0 0 0 / 5%);
}

/* 登录 / 注册 切换 */
.login-tabs {
  display: flex;
  gap: 24px;
  padding-bottom: 12px;
  margin-bottom: 16px;
  border-bottom: 1px solid var(--sky-border);
}

.login-tab {
  padding: 0 0 6px;
  border: 0;
  border-bottom: 2px solid transparent;
  background: transparent;
  color: var(--sky-text-sub);
  font-size: 15px;
  cursor: pointer;
}

.login-tab.active {
  border-bottom-color: var(--sky-primary);
  color: var(--sky-primary);
  font-weight: 600;
}

.login-form {
  display: block;
}

.login-label {
  display: block;
  margin: 12px 0 6px;
  font-size: 14px;
  font-weight: 600;
}

.login-label:first-child {
  margin-top: 0;
}

/* 验证码行 */
.login-captcha {
  display: flex;
  align-items: center;
  gap: 10px;
}

.login-captcha-input {
  flex: 1;
  min-width: 0;
}

.captcha-box {
  display: flex;
  flex: 0 0 120px;
  align-items: center;
  justify-content: center;
  width: 120px;
  height: 32px;
  padding: 0;
  overflow: hidden;
  border: 1px solid var(--sky-border);
  border-radius: 6px;
  background: #f7f8fa;
  cursor: pointer;
}

.captcha-box img {
  width: 120px;
  height: 32px;
  object-fit: cover;
}

.captcha-empty {
  color: var(--sky-text-weak);
  font-size: 12px;
}

.login-submit {
  width: 100%;
  margin-top: 16px;
}

.login-tip {
  margin: 0;
  font-size: 13px;
  color: var(--sky-text-sub);
}

.login-note {
  margin: 10px 0 0;
  color: var(--sky-text-weak);
  font-size: 12px;
  line-height: 1.7;
}

.login-note code {
  padding: 0 4px;
  background: #f4f4f5;
  border-radius: 3px;
}

.login-note-foot {
  max-width: 480px;
  margin: 0 auto;
  text-align: center;
}
</style>
