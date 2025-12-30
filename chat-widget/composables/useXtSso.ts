import CryptoJS from 'crypto-js'

// ============================================================================
// 协同系统 SSO 免登录工具
// 接口文档：通过手机号查询账号 + 免登录
// ============================================================================

export interface XtUser {
  MEMBER_NAME: string  // 公司名称
  LOGIN_ID: string     // 协同用户账号
}

export interface XtSsoConfig {
  baseUrl: string      // 协同系统地址，如 http://10.128.141.168:1202
  secretKey?: string   // AES密钥，默认 wlerp
}

export interface XtApiResponse<T = any> {
  status: string       // 0-成功 1-失败
  message: string
  data?: T
}

// 默认配置
const DEFAULT_CONFIG: XtSsoConfig = {
  baseUrl: 'http://10.128.141.168:1202',
  secretKey: 'wlerp'
}

/**
 * 协同系统 SSO 工具
 */
export function useXtSso(config: Partial<XtSsoConfig> = {}) {
  const cfg = { ...DEFAULT_CONFIG, ...config }
  const secretKey = cfg.secretKey!

  // --------------------------------------------------------------------------
  // AES 加密/解密
  // --------------------------------------------------------------------------

  const encrypt = (str: string): string => {
    return CryptoJS.AES.encrypt(str, secretKey).toString()
  }

  const decrypt = (encrypted: string): string => {
    const bytes = CryptoJS.AES.decrypt(encrypted, secretKey)
    return bytes.toString(CryptoJS.enc.Utf8)
  }

  // --------------------------------------------------------------------------
  // 工具函数
  // --------------------------------------------------------------------------

  /** 格式化当前时间为 yyyy-MM-dd HH:mm:ss */
  const formatNow = (): string => {
    const d = new Date()
    const pad = (n: number) => n.toString().padStart(2, '0')
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
  }

  /** 生成请求头 */
  const buildHeaders = (): Record<string, string> => ({
    'Content-Type': 'application/json',
    'wlToken': encrypt(formatNow())
  })

  /** 加密请求体 */
  const buildBody = (data: Record<string, any>): string => {
    return JSON.stringify({ data: encrypt(JSON.stringify(data)) })
  }

  // --------------------------------------------------------------------------
  // API 调用
  // --------------------------------------------------------------------------

  /**
   * 查询用户账号列表
   * @param mobile 手机号
   * @returns 账号列表
   */
  async function getUserList(mobile: string): Promise<XtUser[]> {
    const url = `${cfg.baseUrl}/gdpaas/comp/selectXtUser.htm`

    const res = await fetch(url, {
      method: 'POST',
      headers: buildHeaders(),
      body: buildBody({ MOBILE: mobile })
    })

    const json: XtApiResponse = await res.json()

    if (json.status !== '0') {
      throw new Error(json.message || '查询用户信息失败')
    }

    // data 可能是加密的，也可能直接是数组（根据文档返参示例是直接数组）
    let users: XtUser[] = []
    if (typeof json.data === 'string') {
      // 加密的情况
      users = JSON.parse(decrypt(json.data))
    } else if (Array.isArray(json.data)) {
      users = json.data
    }

    return users
  }

  /**
   * 免登录获取URL
   * @param mobile 手机号
   * @param loginId 账号
   * @returns 免登录URL
   */
  async function getLoginUrl(mobile: string, loginId: string): Promise<string> {
    const url = `${cfg.baseUrl}/gdpaas/comp/oss.htm`

    const res = await fetch(url, {
      method: 'POST',
      headers: buildHeaders(),
      body: buildBody({ MOBILE: mobile, LOGIN_ID: loginId })
    })

    const json: XtApiResponse<{ url: string }> = await res.json()

    if (json.status !== '0') {
      const errorMap: Record<string, string> = {
        '1': '令牌错误',
        '2': '手机号错误',
        '3': '账号错误'
      }
      throw new Error(errorMap[json.status] || json.message || '登录失败')
    }

    return json.data?.url || ''
  }

  // --------------------------------------------------------------------------
  // 完整流程
  // --------------------------------------------------------------------------

  /**
   * SSO 登录完整流程
   * @param mobile 手机号
   * @param onSelectAccount 当有多个账号时的选择回调，返回选中的 LOGIN_ID
   * @returns 免登录URL
   */
  async function login(
    mobile: string,
    onSelectAccount?: (users: XtUser[]) => Promise<string> | string
  ): Promise<string> {
    // 1. 查询账号列表
    const users = await getUserList(mobile)

    if (users.length === 0) {
      throw new Error('该手机号未绑定任何账号')
    }

    let loginId: string

    if (users.length === 1) {
      // 只有一个账号，直接用
      loginId = users[0].LOGIN_ID
    } else {
      // 多个账号，需要选择
      if (!onSelectAccount) {
        throw new Error(`该手机号绑定了 ${users.length} 个账号，请选择一个`)
      }
      loginId = await onSelectAccount(users)
    }

    // 2. 获取免登录URL
    return getLoginUrl(mobile, loginId)
  }

  /**
   * 跳转到协同系统（完整流程 + 自动跳转）
   */
  async function loginAndRedirect(
    mobile: string,
    onSelectAccount?: (users: XtUser[]) => Promise<string> | string
  ): Promise<void> {
    const url = await login(mobile, onSelectAccount)
    window.location.href = url
  }

  // --------------------------------------------------------------------------
  // 导出
  // --------------------------------------------------------------------------

  return {
    // 底层工具
    encrypt,
    decrypt,

    // API
    getUserList,
    getLoginUrl,

    // 完整流程
    login,
    loginAndRedirect
  }
}
