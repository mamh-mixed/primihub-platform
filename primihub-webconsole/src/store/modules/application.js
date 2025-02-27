import data from '@/views/applicationMarket/mock/data.json'
import { readNumber } from '@/api/market'

const state = {
  data,
  appTitle: '',
  appDescription: '',
  detailImg: '',
  type: ''
}

const mutations = {
  SET_TITLE: (state, title) => {
    state.appTitle = title
  },
  SET_DESCRIPTION: (state, des) => {
    state.appDescription = des
  },
  SET_IMG: (state, src) => {
    state.detailImg = src
  },
  SET_LAYOUT: (state, layout) => {
    state.layout = layout
  },
  SET_DATA: (state, data) => {
    state.data = data
  }
}

const actions = {
  getAppInfo({ commit }, name) {
    const currentApp = state.data.find(item => item.appName === name)
    const { appTitle, description, detailImg, layout } = currentApp
    commit('SET_TITLE', appTitle)
    commit('SET_DESCRIPTION', description)
    commit('SET_LAYOUT', layout)
    commit('SET_IMG', require('../../assets/' + detailImg))
  },
  getReadNumber({ commit }, params) {
    // type key PIR,PSI,reasoning,ADPrediction,UserPortrait
    return new Promise((resolve, reject) => {
      readNumber(params).then(res => {
        if (res.code === 0 && Object.keys(res.result).length > 0) {
          data.map(item => {
            if (res.result[item.appName]) {
              item.readNumber = res.result[item.appName]
            }
          })
          commit('SET_DATA', data)
          resolve(res.result)
        } else {
          resolve(null)
        }
      }).catch(error => {
        console.log(error)
        reject(error)
      })
    })
  }
}

export default {
  namespaced: true,
  state,
  mutations,
  actions
}

