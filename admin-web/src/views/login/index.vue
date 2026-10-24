<template>
  <main class="login-shell">
    <section class="brand-panel" aria-label="智享云膳品牌介绍">
      <div class="brand-panel-content">
        <div class="brand-lockup" aria-label="智享云膳">
          <img src="@/assets/brand/brand-mark.svg" alt="">
          <span>智享云膳</span>
        </div>

        <div class="brand-message">
          <h1 class="brand-headline">
            <span class="brand-headline-line">让每一家门店</span>
            <span class="brand-headline-highlight">运营更简单</span>
          </h1>
          <p class="brand-description">
            订单管理 · 门店运营 · 数据分析
          </p>
          <svg class="brand-illustration" viewBox="0 0 420 190" fill="none" aria-hidden="true">
            <rect class="illustration-frame" x="28" y="24" width="364" height="142" rx="8" />
            <path class="illustration-line" d="M50 143H370" />
            <rect class="illustration-register" x="62" y="76" width="94" height="66" rx="5" />
            <path class="illustration-line" d="M76 76V61C76 53 82 47 90 47H128C136 47 142 53 142 61V76M91 96H128M91 109H116M86 142V151M132 142V151" />
            <rect class="illustration-screen" x="83" y="88" width="53" height="32" rx="3" />
            <path class="illustration-line" d="M95 103H124M95 112H117" />
            <path class="illustration-receipt" d="M191 51H260V137L251 132L242 137L233 132L224 137L215 132L206 137L197 132L191 135V51Z" />
            <path class="illustration-line" d="M205 69H246M205 82H239M205 95H245M205 108H232" />
            <rect class="illustration-chart" x="287" y="60" width="73" height="82" rx="5" />
            <path class="illustration-line" d="M300 126V112H310V126M320 126V98H330V126M340 126V83H350V126M299 74H348" />
            <circle class="illustration-accent" cx="343" cy="74" r="3" />
            <path class="illustration-flow" d="M156 109H178M260 109H279" />
            <path class="illustration-arrow" d="M173 104L179 109L173 114M274 104L280 109L274 114" />
          </svg>
        </div>

        <p class="brand-caption">面向餐饮商家，打通门店、后厨、供应链、经营数据</p>
      </div>
    </section>

    <section class="login-workspace" aria-labelledby="login-title">
      <div class="login-content">
        <header class="login-header">
          <h1 id="login-title">智享云膳｜管理后台</h1>
          <p>欢迎回来，请输入账号登录</p>
        </header>

        <el-form
          ref="loginForm"
          class="login-form"
          :model="loginForm"
          :rules="loginRules"
          label-position="top"
          @submit.prevent="handleLogin"
        >
          <el-form-item label="用户名" prop="username">
            <el-input
              v-model="loginForm.username"
              type="text"
              autocomplete="username"
              placeholder="请输入管理员账号"
            />
            <template #error="{ error }">
              <span class="field-error"><span aria-hidden="true">⚠</span>{{ error }}</span>
            </template>
          </el-form-item>
          <el-form-item label="密码" prop="password">
            <el-input
              v-model="loginForm.password"
              type="password"
              autocomplete="current-password"
              placeholder="请输入密码"
              show-password
            />
            <template #error="{ error }">
              <span class="field-error"><span aria-hidden="true">⚠</span>{{ error }}</span>
            </template>
          </el-form-item>
          <div class="form-options">
            <el-checkbox v-model="rememberAccount">记住账号</el-checkbox>
            <button class="forgot-link" type="button" @click="handleForgotPassword">
              忘记密码？
            </button>
          </div>
          <el-button
            class="login-btn"
            type="primary"
            native-type="submit"
            :loading="loading"
            :disabled="loading || !loginForm.username.trim() || !loginForm.password"
          >
            {{ loading ? '登录中...' : '登录' }}
          </el-button>
        </el-form>
        <p class="security-note">本系统仅限内部管理员使用，禁止非法访问</p>
      </div>
      <footer class="workspace-footer">
        <span>©2026 智享云膳 版权所有</span>
        <span>版本 V1.0.0 · 《用户协议》 · 《隐私政策》</span>
      </footer>
    </section>
  </main>
</template>

<script lang="ts">
import { Component, Vue } from 'vue-facing-decorator'
import type { FormInstance } from 'element-plus'
import { ElMessage } from 'element-plus'
import Cookies from 'js-cookie'
import { UserModule } from '@/store/modules/user'

@Component({
  name: 'Login',
})
export default class Login extends Vue {
  private loginForm = {
    username: '',
    password: '',
  }

  private rememberAccount = Boolean(UserModule.username)

  private loginRules = {
    username: [{ required: true, whitespace: true, message: '账号不能为空', trigger: 'blur' }],
    password: [
      { required: true, message: '密码不能为空', trigger: 'blur' },
      { min: 6, message: '密码不能少于6位', trigger: 'blur' },
    ],
  }

  private loading = false

  created() {
    this.loginForm.username = UserModule.username
  }

  private handleForgotPassword() {
    ElMessage.info('请联系系统管理员重置密码')
  }

  private handleLogin() {
    if (this.loading) {
      return
    }

    const form = this.$refs.loginForm as FormInstance
    form.validate(async (valid: boolean) => {
      if (!valid || this.loading) {
        return
      }

      this.loading = true
      try {
        const response = await UserModule.Login(this.loginForm)
        if (String(response.code) === '1') {
          await this.$router.push('/')
        }
      } catch (error) {
        ElMessage.error(error instanceof Error ? error.message : '登录失败，请稍后重试')
      } finally {
        if (!this.rememberAccount) {
          Cookies.remove('username')
        }
        this.loading = false
      }
    })
  }
}
</script>

<style lang="scss" scoped>
.login-shell {
  box-sizing: border-box;
  display: grid;
  grid-template-columns: 40% minmax(0, 1fr);
  min-height: 100vh;
  background: #fff;
}

.brand-panel {
  box-sizing: border-box;
  display: flex;
  min-width: 0;
  min-height: 100vh;
  overflow: hidden;
  padding: 48px clamp(32px, 4vw, 72px);
  background:
    radial-gradient(ellipse at 82% 74%, rgba(47, 125, 91, 0.28), transparent 38%),
    #173d30;
  color: #f4f7f4;
}

.brand-panel-content {
  display: flex;
  width: 100%;
  max-width: 560px;
  min-height: calc(100vh - 96px);
  flex-direction: column;
  justify-content: flex-start;
  margin: 0 auto;
}

.brand-lockup {
  display: inline-flex;
  align-items: center;
  gap: 12px;
  color: #fff;
  font-size: 22px;
  font-weight: 600;
  letter-spacing: 0.02em;
  text-decoration: none;

  img {
    display: block;
    width: 42px;
    height: 42px;
    border-radius: 10px;
  }
}

.brand-message {
  margin: clamp(64px, 8vh, 88px) 0 0;
  padding: 0;
}

.brand-headline {
  display: flex;
  width: fit-content;
  max-width: 100%;
  flex-direction: column;
  align-items: flex-start;
  gap: 5px;
  margin: 0;
  color: #fff;
  font-size: clamp(38px, 3.2vw, 46px);
  font-weight: 700;
  letter-spacing: 0;
  line-height: 1.38;
}

.brand-headline-line,
.brand-headline-highlight {
  display: block;
  white-space: nowrap;
}

.brand-headline-highlight {
  position: relative;
  margin-left: 8px;
  padding: 2px 12px 5px;
  border-radius: 4px;
  background: #e8be76;
  color: #173d30;
}

.brand-description {
  margin: 20px 0 0;
  color: rgba(255, 255, 255, 0.8);
  font-size: 15px;
  line-height: 1.7;
}

.brand-illustration {
  display: block;
  width: min(100%, 330px);
  height: auto;
  margin-top: 28px;
  opacity: 0.72;
}

.illustration-frame,
.illustration-register,
.illustration-screen,
.illustration-receipt,
.illustration-chart {
  fill: rgba(255, 255, 255, 0.025);
  stroke: rgba(255, 255, 255, 0.7);
  stroke-width: 1.5;
}

.illustration-screen {
  fill: rgba(255, 255, 255, 0.12);
}

.illustration-line {
  stroke: rgba(255, 255, 255, 0.68);
  stroke-linecap: round;
  stroke-linejoin: round;
  stroke-width: 1.5;
}

.illustration-flow,
.illustration-arrow {
  stroke: #9ce0c0;
  stroke-linecap: round;
  stroke-linejoin: round;
  stroke-width: 1.5;
}

.illustration-accent {
  fill: #9ce0c0;
}

.brand-caption {
  margin: 32px 0 0;
  color: rgba(255, 255, 255, 0.6);
  font-size: 13px;
  letter-spacing: 0.04em;
}

.login-workspace {
  box-sizing: border-box;
  position: relative;
  display: flex;
  min-width: 0;
  min-height: 100vh;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 64px clamp(28px, 4vw, 64px) 100px clamp(48px, 6vw, 92px);
  background: #faf9f5;
  color: #333;
}

.login-content {
  box-sizing: border-box;
  width: 100%;
  max-width: 440px;
  padding: 40px;
  border: 1px solid #e8ebe6;
  border-radius: 6px;
  background: #fff;
}

.login-header {
  margin-bottom: 34px;
}

.login-header h1 {
  margin: 0 0 10px;
  color: #333;
  font-size: 24px;
  font-weight: 600;
  line-height: 1.45;
}

.login-header > p:last-child {
  margin: 0;
  color: #666;
  font-size: 14px;
  line-height: 1.7;
}

.login-form {
  width: 100%;

  :deep(.el-form-item) {
    margin-bottom: 22px;
  }

  :deep(.el-form-item__label) {
    margin-bottom: 6px;
    color: #333 !important;
    font-size: 14px;
    font-weight: 500;
    line-height: 20px;
  }

  :deep(.el-form-item__content) {
    line-height: normal;
  }

  :deep(.el-input__wrapper) {
    min-height: 50px;
    padding: 0 14px;
    border-radius: 6px;
    background: #fff !important;
    box-shadow: 0 0 0 1px #dcdee6 inset !important;
    transition: box-shadow 160ms ease;
  }

  :deep(.el-input__wrapper:hover) {
    box-shadow: 0 0 0 1px #b8c4bd inset !important;
  }

  :deep(.el-input__wrapper.is-focus) {
    box-shadow: 0 0 0 1px #0f7b55 inset, 0 0 0 3px rgba(15, 123, 85, 0.12) !important;
  }

  :deep(.el-form-item.is-error .el-input__wrapper) {
    box-shadow: 0 0 0 1px #e54d42 inset !important;
  }

  :deep(.el-input__inner) {
    height: 48px;
    border: 0 !important;
    box-shadow: none !important;
    color: #333;
    font-size: 14px;
    line-height: 48px;
  }

  :deep(.el-input__inner:focus) {
    border: 0 !important;
    box-shadow: none !important;
  }

  :deep(.el-input__inner::placeholder) {
    color: #999;
  }

  :deep(.el-input__inner:focus-visible) {
    outline: none;
  }

  :deep(.el-input__suffix-inner .el-input__icon) {
    color: #666;
  }
}

.field-error {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  color: #e54d42;
  font-size: 12px;
  line-height: 18px;

  > span {
    font-size: 13px;
  }
}

.form-options {
  display: flex;
  min-height: 22px;
  align-items: center;
  justify-content: space-between;
  margin: -5px 0 22px;

  :deep(.el-checkbox__label) {
    color: #666;
    font-size: 14px;
  }

  :deep(.el-checkbox__input.is-checked .el-checkbox__inner) {
    border-color: #0f7b55;
    background: #0f7b55;
  }
}

.forgot-link {
  padding: 2px 0;
  border: 0;
  background: transparent;
  color: #0f7b55;
  font: inherit;
  font-size: 13px;
  cursor: pointer;

  &:hover {
    color: #349e78;
  }

  &:focus-visible {
    outline: 2px solid rgba(15, 123, 85, 0.28);
    outline-offset: 3px;
  }
}

.login-btn {
  width: 100%;
  height: 50px;
  margin-top: 0;
  border: 1px solid #0f7b55 !important;
  border-radius: 6px !important;
  background: #0f7b55 !important;
  color: #fff !important;
  font-size: 14px;
  font-weight: 600;
  transition: background-color 160ms ease, transform 120ms ease, box-shadow 160ms ease;

  &:hover:not(.is-disabled) {
    border-color: #349e78 !important;
    background: #349e78 !important;
  }

  &:active:not(.is-disabled) {
    border-color: #0a5e41 !important;
    background: #0a5e41 !important;
  }

  &:focus-visible {
    outline: 3px solid rgba(15, 123, 85, 0.24);
    outline-offset: 3px;
  }

  &.is-disabled,
  &.is-disabled:hover {
    border-color: #9bb0a7 !important;
    background: #9bb0a7 !important;
    color: #fff !important;
    opacity: 0.64;
  }
}

.security-note {
  margin: 18px 0 0;
  color: #999;
  font-size: 12px;
  line-height: 1.6;
  text-align: center;
}

.workspace-footer {
  position: absolute;
  right: clamp(32px, 7vw, 112px);
  bottom: 32px;
  left: clamp(32px, 7vw, 112px);
  display: flex;
  justify-content: space-between;
  gap: 16px;
  color: #999;
  font-size: 12px;
  letter-spacing: 0.02em;
}

@media (max-width: 1023px) {
  .login-shell {
    grid-template-columns: minmax(0, 1fr);
  }

  .brand-panel {
    min-height: 260px;
    padding: 30px 48px;
  }

  .brand-panel-content {
    min-height: 200px;
  }

  .brand-lockup {
    gap: 11px;
    font-size: 20px;

    img {
      width: 38px;
      height: 38px;
      border-radius: 9px;
    }
  }

  .brand-message {
    margin: 10px 0 0;
    padding: 0;
  }

  .brand-headline {
    font-size: 30px;
    line-height: 1.35;
  }

  .brand-headline-highlight {
    margin-left: 6px;
    padding: 1px 9px 4px;
  }

  .brand-description {
    margin-top: 12px;
    font-size: 14px;
  }

  .brand-illustration {
    width: min(48%, 250px);
    margin-top: 14px;
  }

  .brand-caption {
    display: none;
  }

  .login-workspace {
    min-height: calc(100vh - 260px);
    padding: 48px 32px 88px;
  }

  .workspace-footer {
    right: 24px;
    bottom: 24px;
    left: 24px;
  }
}

@media (max-width: 767px) {
  .brand-panel {
    min-height: 200px;
    padding: 20px 24px;
  }

  .brand-panel-content {
    min-height: 160px;
  }

  .brand-lockup {
    font-size: 18px;

    img {
      width: 34px;
      height: 34px;
    }
  }

  .brand-message {
    display: block;
    margin-top: 8px;
    padding: 0;
  }

  .brand-illustration,
  .brand-caption {
    display: none;
  }

  .brand-headline {
    gap: 3px;
    font-size: clamp(24px, 6.2vw, 29px);
    line-height: 1.35;
  }

  .brand-headline-highlight {
    margin-left: 4px;
    padding: 1px 8px 4px;
  }

  .brand-description {
    margin-top: 9px;
    font-size: 12px;
  }

  .login-workspace {
    min-height: calc(100vh - 200px);
    padding: 32px 20px 72px;
  }

  .login-content {
    padding: 28px 24px;
  }

  .login-header {
    margin-bottom: 24px;
  }

  .login-header h1 {
    font-size: 21px;
  }

  .security-note {
    font-size: 11px;
  }

  .workspace-footer {
    right: 20px;
    bottom: 20px;
    left: 20px;
    justify-content: center;

    span:nth-child(2) {
      display: none;
    }
  }
}

@media (min-width: 768px) and (max-width: 1023px) {
  .brand-message {
    display: grid;
    grid-template-columns: minmax(0, 1fr) minmax(160px, 32%);
    align-items: center;
    column-gap: 24px;
    row-gap: 6px;
    margin: 8px 0 0;
  }

  .brand-headline {
    grid-column: 1;
    grid-row: 1;
    font-size: 29px;
  }

  .brand-headline-highlight {
    margin-left: 6px;
    padding: 1px 9px 4px;
  }

  .brand-description {
    grid-column: 1;
    grid-row: 2;
    margin: 0;
    font-size: 13px;
  }

  .brand-illustration {
    grid-column: 2;
    grid-row: 1 / span 2;
    width: 100%;
    margin: 0;
  }
}

@media (min-width: 1024px) and (max-height: 820px) {
  .brand-illustration {
    width: min(100%, 360px);
    margin-bottom: 14px;
  }

  .brand-message {
    margin-top: 54px;
  }
}
</style>
