package com.siliconleap.app.shizuku

import android.os.RemoteException

/**
 * Shizuku UserService（shell uid 进程内实例化，由 Shizuku 服务器加载本 APK 的类）：
 * exec 在 shell uid 下原生执行命令（虚拟屏创建/跨屏拉起/输入/截屏全部经此通道）。
 * 首行 = 退出码，其后 = stdout+stderr 合并输出；不记录命令内容与输出（日志脱敏）。
 */
class GuiUserService : IGuiUserService.Stub() {

    override fun exec(cmd: String): String {
        val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
        val out = StringBuilder()
        val err = StringBuilder()
        val t1 = Thread {
            runCatching { p.inputStream.bufferedReader().forEachLine { out.append(it).append('\n') } }
        }.apply { isDaemon = true; start() }
        val t2 = Thread {
            runCatching { p.errorStream.bufferedReader().forEachLine { err.append(it).append('\n') } }
        }.apply { isDaemon = true; start() }
        val code = runCatching { p.waitFor() }.getOrDefault(-1)
        t1.join(5_000)
        t2.join(5_000)
        return "$code\n$out$err"
    }

    override fun exit() {
        throw RemoteException("exit")
    }
}
