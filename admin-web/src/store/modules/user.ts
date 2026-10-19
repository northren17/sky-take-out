import store from '@/store'

export interface IUserState {
  token: string
  name: string
  avatar: string
  storeId: string
  introduction: string
  userInfo: any
  roles: string[]
  username: string
}

export const UserModule = {
  get token() {
    return store.state.user.token
  },
  get name() {
    return store.state.user.name
  },
  get avatar() {
    return store.state.user.avatar
  },
  get storeId() {
    return store.state.user.storeId
  },
  get introduction() {
    return store.state.user.introduction
  },
  get userInfo() {
    return store.state.user.userInfo
  },
  get roles() {
    return store.state.user.roles
  },
  get username() {
    return store.state.user.username
  },
  Login(userInfo: { username: string, password: string }) {
    return store.dispatch('user/Login', userInfo)
  },
  ResetToken() {
    return store.dispatch('user/ResetToken')
  },
  changeStore(data: any) {
    return store.dispatch('user/changeStore', data)
  },
  GetUserInfo() {
    return store.dispatch('user/GetUserInfo')
  },
  LogOut() {
    return store.dispatch('user/LogOut')
  }
}
