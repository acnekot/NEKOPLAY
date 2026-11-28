package cc.catsuki.nekoplay

/**
 * 发布签名：git 提交和 GitHub Release 安装包都用这把 OpenPGP 钥匙签名。
 * 公钥放在官网和仓库 docs/nekoplay.asc，安装包旁边有 .asc 分离签名和 SHA256SUMS，可用 gpg --verify 校验。
 */
object Signing {
    const val FINGERPRINT = "66F73825E5F5423038074ABE836EEEEEEEEEEEEE"
    const val PUBKEY_URL = "https://nekoplay.catsuki.cc/nekoplay.asc"
    /** 按 4 位一组显示，方便和 gpg --fingerprint 的输出逐段比对 */
    val pretty: String get() = FINGERPRINT.chunked(4).joinToString(" ")
    val longId: String get() = FINGERPRINT.takeLast(16)
}
