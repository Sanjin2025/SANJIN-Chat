package com.sanjinchat.wear.keepalive

import java.util.concurrent.TimeUnit

/**
 * Root 一键保活。
 *
 * 部分手表没有系统设置界面（无法手动关闭电池优化 / 开启自启动），
 * 这里通过 su 直接完成：加入 Doze 白名单、允许后台运行、待机分组设为 active、
 * 并尝试补授通知权限，配合前台常驻通知服务实现"点一次就保活"。
 */
object RootKeepAlive {

    data class Result(val ok: Boolean, val message: String)

    /** 检测是否已获得 root（su 能拿到 uid=0） */
    fun isAvailable(): Boolean = runCatching {
        val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        val finished = p.waitFor(3, TimeUnit.SECONDS)
        if (!finished) p.destroy()
        finished && out.contains("uid=0")
    }.getOrDefault(false)

    /**
     * 一键开启保活（阻塞调用，请在 IO 线程执行）。
     * @param pkg 应用包名
     */
    fun enable(pkg: String): Result = runCatching {
        val script = buildString {
            // 通知权限（Android 13+，root 可直接授予，无需进系统设置）
            appendLine("pm grant $pkg android.permission.POST_NOTIFICATIONS 2>/dev/null")
            appendLine("appops set $pkg POST_NOTIFICATION allow 2>/dev/null")
            // 加入 Doze 白名单（忽略电池优化）
            appendLine("dumpsys deviceidle whitelist +$pkg 2>/dev/null")
            appendLine("cmd deviceidle whitelist +$pkg 2>/dev/null")
            // 允许后台运行
            appendLine("cmd appops set $pkg RUN_IN_BACKGROUND allow 2>/dev/null")
            appendLine("cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow 2>/dev/null")
            // 待机分组设为 active，避免进入 restricted / rare
            appendLine("am set-standby-bucket $pkg active 2>/dev/null")
            appendLine("am set-inactive $pkg false 2>/dev/null")
            appendLine("echo SANJIN_ROOT_DONE")
        }
        val p = ProcessBuilder("su").redirectErrorStream(true).start()
        p.outputStream.bufferedWriter().use { w ->
            w.write(script)
            w.flush()
        }
        val out = p.inputStream.bufferedReader().readText()
        val finished = p.waitFor(20, TimeUnit.SECONDS)
        if (!finished) {
            p.destroy()
            return@runCatching Result(false, "root 命令超时，请在授权弹窗中点击允许")
        }
        if (out.contains("SANJIN_ROOT_DONE")) {
            Result(true, "已开启：Doze 白名单 + 后台运行 + 活跃待机分组")
        } else {
            val detail = out.trim().take(180)
            Result(false, "执行失败：${detail.ifBlank { "su 被拒绝" }}")
        }
    }.getOrElse { Result(false, "执行异常：${it.message ?: it.javaClass.simpleName}") }
}
