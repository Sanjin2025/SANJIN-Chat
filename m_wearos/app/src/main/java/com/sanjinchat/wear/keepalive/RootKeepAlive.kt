package com.sanjinchat.wear.keepalive

import java.util.concurrent.TimeUnit

/**
 * Root 一键保活（深度版，面向小天才等定制 ROM）。
 *
 * 普通前台服务 + AlarmManager 在小天才上仍可能被强杀。
 * 这里用 su 做三件事：
 *   1) 系统级放开：Doze 白名单、后台运行、待机分组 active、关闭「离开即销毁 Activity」
 *   2) 通知权限直接授予
 *   3) 写入 /data/local/tmp/sanjin_keepalive.sh 并用 nohup 后台运行：
 *      每 30 秒用 am startservice 把 KeepAliveService 拉起来，即使主进程被杀也能自愈。
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
        val svc = "$pkg/.KeepAliveService"
        val script = buildString {
            // —— 1) 系统级放开 ——
            appendLine("pm grant $pkg android.permission.POST_NOTIFICATIONS 2>/dev/null")
            appendLine("appops set $pkg POST_NOTIFICATION allow 2>/dev/null")
            appendLine("dumpsys deviceidle whitelist +$pkg 2>/dev/null")
            appendLine("cmd deviceidle whitelist +$pkg 2>/dev/null")
            appendLine("cmd appops set $pkg RUN_IN_BACKGROUND allow 2>/dev/null")
            appendLine("cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow 2>/dev/null")
            appendLine("am set-standby-bucket $pkg active 2>/dev/null")
            appendLine("am set-inactive $pkg false 2>/dev/null")
            // 小天才部分版本开了「离开即销毁 Activity」，关掉
            appendLine("settings put global always_finish_activities 0 2>/dev/null")
            // 省电策略白名单（部分 ROM）
            appendLine("dumpsys power whitelist add $pkg 2>/dev/null")

            // —— 2) 写一个 nohup 守护脚本，每 30 秒拉起服务 ——
            // 注意：只拉起保活服务（后台），绝不启动 Activity，
            // 否则用户使用其他应用时会被突然弹出的聊天界面打断。
            val daemon = "/data/local/tmp/sanjin_keepalive.sh"
            appendLine("cat > $daemon <<'EOF'")
            appendLine("#!/system/bin/sh")
            appendLine("while true; do")
            appendLine("  am start-foreground-service -n $svc >/dev/null 2>&1 || am startservice -n $svc >/dev/null 2>&1")
            appendLine("  sleep 30")
            appendLine("done")
            appendLine("EOF")
            appendLine("chmod 755 $daemon")
            // 杀掉旧的守护进程（如果有），再启动新的
            appendLine("pkill -f sanjin_keepalive.sh 2>/dev/null")
            appendLine("nohup $daemon >/dev/null 2>&1 &")
            appendLine("echo SANJIN_ROOT_DONE")
        }
        val p = ProcessBuilder("su").redirectErrorStream(true).start()
        p.outputStream.bufferedWriter().use { w ->
            w.write(script)
            w.flush()
        }
        val out = p.inputStream.bufferedReader().readText()
        val finished = p.waitFor(25, TimeUnit.SECONDS)
        if (!finished) {
            p.destroy()
            return@runCatching Result(false, "root 命令超时，请在授权弹窗中点击允许")
        }
        if (out.contains("SANJIN_ROOT_DONE")) {
            Result(
                true,
                "已开启：Doze 白名单 + 后台运行 + 活跃待机分组 + 关闭销毁Activity + 30s 守护拉活"
            )
        } else {
            val detail = out.trim().take(180)
            Result(false, "执行失败：${detail.ifBlank { "su 被拒绝" }}")
        }
    }.getOrElse { Result(false, "执行异常：${it.message ?: it.javaClass.simpleName}") }

    /** 关闭保活：停止守护脚本 */
    fun disable(pkg: String): Result = runCatching {
        val script = "pkill -f sanjin_keepalive.sh 2>/dev/null\nrm -f /data/local/tmp/sanjin_keepalive.sh\necho SANJIN_ROOT_DONE"
        val p = ProcessBuilder("su").redirectErrorStream(true).start()
        p.outputStream.bufferedWriter().use { w -> w.write(script); w.flush() }
        val out = p.inputStream.bufferedReader().readText()
        val finished = p.waitFor(10, TimeUnit.SECONDS)
        if (!finished) { p.destroy(); return@runCatching Result(false, "超时") }
        if (out.contains("SANJIN_ROOT_DONE")) Result(true, "已停止 root 守护拉活")
        else Result(false, out.trim().take(120))
    }.getOrElse { Result(false, "异常：${it.message ?: it.javaClass.simpleName}") }
}
