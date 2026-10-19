import type { Module } from 'vuex'
import type { IRootState } from '@/store'
import { DeviceType, type IAppState } from './app'
import { setSidebarStatus } from '@/utils/cookies'

const app: Module<IAppState, IRootState> = {
  namespaced: true,
  state: () => ({
    sidebar: {
      opened: true,
      withoutAnimation: false
    },
    device: DeviceType.Desktop,
    statusNumber: 0
  }),
  mutations: {
    TOGGLE_SIDEBAR(state, withoutAnimation: boolean) {
      state.sidebar.opened = !state.sidebar.opened
      state.sidebar.withoutAnimation = withoutAnimation
      setSidebarStatus(state.sidebar.opened ? 'opened' : 'closed')
    },
    CLOSE_SIDEBAR(state, withoutAnimation: boolean) {
      state.sidebar.opened = false
      state.sidebar.withoutAnimation = withoutAnimation
      setSidebarStatus('closed')
    },
    STATUS_NUMBER(state, statusNumber: number) {
      state.statusNumber = statusNumber
    },
    TOGGLE_DEVICE(state, device: DeviceType) {
      state.device = device
    }
  },
  actions: {
    ToggleSideBar({ commit }, withoutAnimation: boolean) {
      commit('TOGGLE_SIDEBAR', withoutAnimation)
    },
    CloseSideBar({ commit }, withoutAnimation: boolean) {
      commit('CLOSE_SIDEBAR', withoutAnimation)
    },
    ToggleDevice({ commit }, device: DeviceType) {
      commit('TOGGLE_DEVICE', device)
    },
    StatusNumber({ commit }, statusNumber: number) {
      commit('STATUS_NUMBER', statusNumber)
    }
  }
}

export default app
