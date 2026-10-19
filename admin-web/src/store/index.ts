import { createStore } from 'vuex'
import type { IAppState } from './modules/app'
import type { IUserState } from './modules/user'
import app from './modules/appState'
import user from './modules/userState'

export interface IRootState {
  app: IAppState
  user: IUserState
}

export default createStore<IRootState>({
  modules: {
    app,
    user
  }
})
