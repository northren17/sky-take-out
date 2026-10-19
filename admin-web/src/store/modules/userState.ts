import { ElMessage } from 'element-plus'
import Cookies from 'js-cookie'
import type { Module } from 'vuex'
import type { IRootState } from '@/store'
import { login, userLogout } from '@/api/employee'
import {
  getToken,
  setToken,
  removeToken,
  getStoreId,
  setStoreId,
  removeUserInfo,
  getUserInfo
} from '@/utils/cookies'
import type { IUserState } from './user'

const user: Module<IUserState, IRootState> = {
  namespaced: true,
  state: () => ({
    token: getToken() || '',
    name: '',
    avatar: '',
    storeId: getStoreId() || '',
    introduction: '',
    userInfo: {},
    roles: [],
    username: Cookies.get('username') || ''
  }),
  mutations: {
    SET_TOKEN(state, token: string) {
      state.token = token
    },
    SET_NAME(state, name: string) {
      state.name = name
    },
    SET_USERINFO(state, userInfo: any) {
      state.userInfo = { ...userInfo }
    },
    SET_AVATAR(state, avatar: string) {
      state.avatar = avatar
    },
    SET_INTRODUCTION(state, introduction: string) {
      state.introduction = introduction
    },
    SET_ROLES(state, roles: string[]) {
      state.roles = roles
    },
    SET_STOREID(state, storeId: string) {
      state.storeId = storeId
    },
    SET_USERNAME(state, name: string) {
      state.username = name
    }
  },
  actions: {
    async Login({ commit }, userInfo: { username: string, password: string }) {
      const username = userInfo.username.trim()
      const { password } = userInfo
      commit('SET_USERNAME', username)
      Cookies.set('username', username)
      const { data } = await login({ username, password })
      if (String(data.code) === '1') {
        commit('SET_TOKEN', data.data.token)
        setToken(data.data.token)
        commit('SET_USERINFO', data.data)
        Cookies.set('user_info', data.data)
        return data
      }
      return ElMessage.error(data.msg)
    },
    ResetToken({ commit }) {
      removeToken()
      commit('SET_TOKEN', '')
      commit('SET_ROLES', [])
    },
    changeStore({ commit }, data: any) {
      commit('SET_STOREID', data.data)
      commit('SET_TOKEN', data.authorization)
      setStoreId(data.data)
      setToken(data.authorization)
    },
    GetUserInfo({ state, commit }) {
      if (state.token === '') {
        throw Error('GetUserInfo: token is undefined!')
      }

      const data = JSON.parse(String(getUserInfo()))
      if (!data) {
        throw Error('Verification failed, please Login again.')
      }

      const {
        roles,
        name,
        avatar,
        introduction,
        applicant,
        storeManagerName,
        storeId = ''
      } = data
      if (!roles || roles.length <= 0) {
        throw Error('GetUserInfo: roles must be a non-null array!')
      }

      commit('SET_ROLES', roles)
      commit('SET_USERINFO', data)
      commit('SET_NAME', name || applicant || storeManagerName)
      commit('SET_AVATAR', avatar)
      commit('SET_INTRODUCTION', introduction)
      if (storeId) {
        commit('SET_STOREID', storeId)
      }
    },
    async LogOut({ commit }) {
      await userLogout({})
      removeToken()
      commit('SET_TOKEN', '')
      commit('SET_ROLES', [])
      Cookies.remove('username')
      Cookies.remove('user_info')
      removeUserInfo()
    }
  }
}

export default user
