// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

import Cookies from 'js-cookie'
import { axios, sourceToken } from '@/utils/request'
import { message, notification } from 'ant-design-vue'
import { vueProps } from '@/vue-app'
import {
  ACCESS_TOKEN
} from '@/store/mutation-types'

const getAPICommandsRegex = /^(get|list|query|find)\w+$/i
const additionalGetAPICommandsList = [
  'isaccountallowedtocreateofferingswithtags',
  'readyforshutdown',
  'cloudianisenabled',
  'quotabalance',
  'quotasummary',
  'quotatarifflist',
  'quotaisenabled',
  'quotastatement',
  'verifyoauthcodeandgetuser'
]

export function getAPI (command, args = {}) {
  args.command = command
  args.response = 'json'

  const sessionkey = vueProps.$localStorage.get(ACCESS_TOKEN) || Cookies.get('sessionkey')
  if (sessionkey) {
    args.sessionkey = sessionkey
  }

  return axios({
    params: {
      ...args
    },
    url: '/',
    method: 'GET'
  })
}

export function postAPI (command, data = {}) {
  const params = new URLSearchParams()
  params.append('command', command)
  params.append('response', 'json')
  if (data) {
    Object.entries(data).forEach(([key, value]) => {
      if (value !== undefined && value !== null) {
        params.append(key, value)
      }
    })
  }

  const sessionkey = vueProps.$localStorage.get(ACCESS_TOKEN) || Cookies.get('sessionkey')
  if (sessionkey) {
    params.append('sessionkey', sessionkey)
  }
  return axios({
    url: '/',
    method: 'POST',
    data: params
  })
}

export function callAPI (command, args = {}) {
  const isGetAPICommand = getAPICommandsRegex.test(command) || additionalGetAPICommandsList.includes(command.toLowerCase())
  const call = isGetAPICommand ? getAPI : postAPI
  return call(command, args)
}

export function login (arg) {
  if (!sourceToken.checkExistSource()) {
    sourceToken.init()
  }

  // Logout before login is called to purge any duplicate sessionkey cookies
  postAPI('logout')

  const params = new URLSearchParams()
  params.append('command', 'login')
  params.append('username', arg.username || arg.email)
  params.append('password', arg.password)
  params.append('domain', arg.domain)
  params.append('response', 'json')
  return axios({
    url: '/',
    method: 'POST',
    data: params,
    headers: {
      'content-type': 'application/x-www-form-urlencoded'
    }
  })
}

export async function logout () {
  const result = await postAPI('logout').finally(() => {
    sourceToken.cancel()
    message.destroy()
    notification.destroy()
  })
  return result
}

export function oauthlogin (arg) {
  if (!sourceToken.checkExistSource()) {
    sourceToken.init()
  }

  // Logout before login is called to purge any duplicate sessionkey cookies
  postAPI('logout')

  const params = new URLSearchParams()
  params.append('command', 'oauthlogin')
  params.append('email', arg.email)
  params.append('secretcode', arg.secretcode)
  params.append('provider', arg.provider)
  params.append('domain', arg.domain)
  params.append('response', 'json')
  return axios({
    url: '/',
    method: 'post',
    data: params,
    headers: {
      'content-type': 'application/x-www-form-urlencoded'
    }
  })
}
