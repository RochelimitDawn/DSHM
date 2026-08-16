package com.siliconleap.app.runtime

import android.content.Context
import android.system.Os
import java.io.File
import org.json.JSONArray

/** 运行时目录与环境变量约定（Termux-style prefix + 原生库目录）。 */
object TermuxEnv {
    const val RUNTIME_ASSET = "runtime.zip"

    fun filesDir(context: Context): File = context.filesDir

    /** Termux prefix，即运行时根目录 `filesDir/usr`。 */
    fun prefix(context: Context): File = File(filesDir(context), "usr")

    /** 应用原生库目录（jniLibs 解包处，SELinux 允许应用执行）。 */
    fun nativeLibDir(context: Context): File = File(context.applicationInfo.nativeLibraryDir)

    /** node 从原生库目录启动（app_data_file 已被禁止执行）。 */
    fun nodeBin(context: Context): File = File(nativeLibDir(context), "libnode.so")

    /** filesDir/bin：bash/sh/rg 的符号链接目录，exec 跟随到原生库目录。 */
    fun binLinks(context: Context): File = File(filesDir(context), "bin")

    fun isRuntimeReady(context: Context): Boolean = dshEntry(context).exists()

    fun home(context: Context): File = File(filesDir(context), "home")
    fun tmp(context: Context): File = File(filesDir(context), "tmp")
    fun dshHome(context: Context): File = File(filesDir(context), "dsh-home")

    /** 工作区：默认应用私有目录 workspace，用户可在设置中改为公共存储路径。 */
    fun workspace(context: Context): File = File(AppSettings.workspacePath(context))

    fun logs(context: Context): File = File(filesDir(context), "logs")
    fun serverLog(context: Context): File = File(logs(context), "server.log")

    /** dsh CLI 入口（npm 包安装于 `$PREFIX/lib/node_modules`）。 */
    fun dshEntry(context: Context): File = File(prefix(context), "lib/node_modules/@deepseek-ai/dsh/lib/bin.js")

    fun dshEntryExists(context: Context): Boolean = dshEntry(context).exists()

    /** 建立 bash/sh/rg -> 原生库目录 的符号链接（幂等）。 */
    fun ensureBinLinks(context: Context) {
        val dir = binLinks(context)
        runCatching { dir.mkdirs() }
        val nativeLib = nativeLibDir(context).absolutePath
        val links = mapOf(
            "bash" to "libbash.so",
            "sh" to "libsh.so",
            "rg" to "librg.so",
            "node" to "libnode.so",
        )
        for ((name, so) in links) {
            runCatching {
                val link = File(dir, name)
                val target = File(nativeLib, so).absolutePath
                if (Os.readlink(link.absolutePath) != target) {
                    runCatching { link.delete() }
                    Os.symlink(target, link.absolutePath)
                }
            }
        }
    }

    /** 启动 node 服务进程时的环境变量。 */
    fun serverEnv(context: Context): Map<String, String> {
        val prefix = prefix(context).absolutePath
        val nativeLib = nativeLibDir(context).absolutePath
        val binLinks = binLinks(context).absolutePath
        return mapOf(
            "PREFIX" to prefix,
            "HOME" to home(context).absolutePath,
            "TMPDIR" to tmp(context).absolutePath,
            "DSH_HOME" to dshHome(context).absolutePath,
            "PATH" to "$binLinks:$nativeLib:$prefix/bin:$prefix/bin/node_modules/.bin",
            "LD_LIBRARY_PATH" to "$nativeLib:$prefix/lib",
            "TERM" to "xterm-256color",
            "LANG" to "en_US.UTF-8",
            // 可执行文件直接用 nativeLibraryDir 绝对路径（app 数据目录被 SELinux 禁止执行，
            // filesDir/bin 符号链接 exec 会 EACCES；nativeLibraryDir 与 node 服务同样可执行）
            "DSH_RG_PATH" to "$nativeLib/librg.so",
            "DSH_BASH_PATH" to "$nativeLib/libbash.so",
            "DSH_SH_PATH" to "$nativeLib/libsh.so",
            // Debian 子系统（proot）：DSH shell/terminal 的 bash argv 前缀。
            // patch_runtime.js 的 Patch 10 读 DSH_SUBSYSTEM_ARGV（JSON 数组）包裹 bash；
            // 开关关闭或子系统未安装时为空，回退原生 bash。
            "DSH_SUBSYSTEM_ARGV" to (subsystemArgvJson(context) ?: ""),
        )
    }

    /** 构造 proot 包裹 argv（[proot, 挂载参数…, /bin/bash]）；未启用/未安装时返回 null。 */
    private fun subsystemArgvJson(context: Context): String? {
        if (!AppSettings.subsystemShellEnabled(context)) return null
        val proot = SubsystemManager.prootBin(context)
        val rootfs = SubsystemManager.rootfsDir(context)
        if (!proot.exists() || !File(rootfs, "etc").isDirectory) return null
        val resolv = SubsystemManager.resolvConf(context).absolutePath
        val argv = listOf(
            proot.absolutePath,
            "-0",
            "-r",
            rootfs.absolutePath,
            "-b", "/dev",
            "-b", "/dev/pts",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "$resolv:/etc/resolv.conf",
            "-b", "${dshHome(context).absolutePath}:/root/dsh",
            "-b", "${workspace(context).absolutePath}:/workspace",
            "-b", "${tmp(context).absolutePath}:/tmp",
            "/bin/bash",
        )
        return JSONArray(argv).toString()
    }
}
