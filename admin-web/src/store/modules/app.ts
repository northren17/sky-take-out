import store from '@/store'

export enum DeviceType {
  Mobile,
  Desktop
}

export interface IAppState {
  device: DeviceType
  sidebar: {
    opened: boolean
    withoutAnimation: boolean
  }
  statusNumber: number
}

export const AppModule = {
  get device() {
    return store.state.app.device
  },
  get sidebar() {
    return store.state.app.sidebar
  },
  get statusNumber() {
    return store.state.app.statusNumber
  },
  ToggleSideBar(withoutAnimation: boolean) {
    return store.dispatch('app/ToggleSideBar', withoutAnimation)
  },
  CloseSideBar(withoutAnimation: boolean) {
    return store.dispatch('app/CloseSideBar', withoutAnimation)
  },
  ToggleDevice(device: DeviceType) {
    return store.dispatch('app/ToggleDevice', device)
  },
  StatusNumber(device: number) {
    return store.dispatch('app/StatusNumber', device)
  }
}
