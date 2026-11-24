package com.neptine.mimir.agent

import android.util.Log

/**
 * 功能：打印一次模型请求的 prompt 体量
 * 参数：role 请求角色（executor / spec / plan / replan）；systemChars 系统提示字符数；
 *       userChars 用户提示字符数；imageBytes 附图字节数（无图为 0）；extra 细分段的补充信息
 * 返回：无
 *
 * 只用于记忆与 token 取证，不改变请求内容；统一走 MimirPrompt 标签，便于一次过滤。
 * 字符数不等于 token，但中文场景下可作为稳定的相对指标；截图单独按字节记录。
 */
fun logPromptMetrics(
    role: String,
    systemChars: Int,
    userChars: Int,
    imageBytes: Long = 0,
    extra: String = "",
) {
    Log.d(
        TAG,
        "role=$role system=$systemChars user=$userChars total=${systemChars + userChars} " +
            "imageKB=${imageBytes / 1024}" + if (extra.isEmpty()) "" else " $extra",
    )
}

private const val TAG = "MimirPrompt"
