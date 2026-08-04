const TOKEN_KEY = 'hify_token'

// token 仅存 localStorage / 请求头 Authorization；禁止塞进 URL query（§7.11）。
export function getToken() {
  return localStorage.getItem(TOKEN_KEY) || ''
}

export function setToken(token) {
  localStorage.setItem(TOKEN_KEY, token)
}

export function removeToken() {
  localStorage.removeItem(TOKEN_KEY)
}
