package com.fucksh.update

/**
 * 更新流程中的错误。每个分支都带可直接展示给用户的中文说明。
 */
sealed class UpdateException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** 网络不可达、HTTP 状态异常等。 */
    class Network(message: String, cause: Throwable? = null) : UpdateException(message, cause)

    /** 远端清单不合法（缺字段、JSON 非法等）。 */
    class Manifest(message: String, cause: Throwable? = null) : UpdateException(message, cause)

    /** 下载地址不在允许的 HTTPS 域名白名单内。 */
    class HostBlocked(val url: String) : UpdateException("下载地址不在允许列表内：$url")

    /** 安装包超出体积上限。 */
    class TooLarge(val size: Long, val limit: Long) :
        UpdateException("安装包超出大小上限（$size > $limit 字节）")

    /** SHA-256 不匹配。 */
    class ChecksumMismatch(val expected: String, val actual: String) :
        UpdateException("安装包 SHA-256 校验失败（期望 $expected，实际 $actual）")

    /** 包名不是本应用。 */
    class PackageMismatch(val expected: String, val actual: String?) :
        UpdateException("安装包包名不匹配（期望 $expected，实际 $actual）")

    /** 签名证书与本机已安装版本不一致。 */
    class SignatureMismatch(val expected: String, val actual: String) :
        UpdateException("安装包签名与已安装版本不一致，拒绝安装")

    /** 安装包结构异常（无法解析、版本号对不上等）。 */
    class Unverified(message: String, cause: Throwable? = null) : UpdateException(message, cause)

    /** 超时。 */
    class Timeout(message: String) : UpdateException(message)
}
