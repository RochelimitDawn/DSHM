package com.siliconleap.app.runtime

import android.content.Context
import android.system.Os
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

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
            // Termux curl 编译期 CA 路径指向 /data/data/com.termux/...，本应用下不存在；
            // 显式指定运行时证书（避免 curl 证书校验失败）
            "CURL_CA_BUNDLE" to "$prefix/etc/tls/cert.pem",
            // dsh plugin 装配（dsh-mobile）需 pnpm；app 数据目录 noexec，pnpm 脚本与
            // PATH 中 node 符号链接均无法 exec。PNPM_NODE/PNPM_CJS 让 dsh plugin
            // 用 node 绝对路径直接运行 pnpm.cjs（Patch 14 读取）。
            "PNPM_NODE" to "$nativeLib/libnode.so",
            "PNPM_CJS" to "$prefix/lib/node_modules/pnpm/bin/pnpm.cjs",
            // 可执行文件直接用 nativeLibraryDir 绝对路径（app 数据目录被 SELinux 禁止执行，
            // filesDir/bin 符号链接 exec 会 EACCES；nativeLibraryDir 与 node 服务同样可执行）
            "DSH_RG_PATH" to "$nativeLib/librg.so",
            "DSH_BASH_PATH" to "$nativeLib/libbash.so",
            "DSH_SH_PATH" to "$nativeLib/libsh.so",
            // 运行时自带 npmrc（update-notifier=false）：pnpm 的更新提示会把用户
            // 引向 pnpm add -g pnpm，而 12.x 的启动器包在 --ignore-scripts 下不可用
            "NPM_CONFIG_USERCONFIG" to "$prefix/etc/npmrc",
            // Debian 子系统（proot）：DSH shell/terminal 的 bash argv 前缀。
            // patch_runtime.js 的 Patch 10 读 DSH_SUBSYSTEM_ARGV（JSON 数组）包裹 bash；
            // 开关关闭或子系统未安装时为空，回退原生 bash。
            "DSH_SUBSYSTEM_ARGV" to (subsystemArgvJson(context) ?: ""),
            // UML 引擎：umarm-cmd 命令通道共享目录（libumarm-cmd.so wrapper 读取）
            "DSH_UMARM_SHARE" to SubsystemManager.shareDir(context).absolutePath,
            // root shell：DSH_ROOT_ARGV=[suPath, bashPath]，patch 构造 su -c "exec bash -c 'cmd'"
            "DSH_ROOT_ARGV" to (rootArgvJson(context) ?: ""),
            // proot glue 临时目录（DSH 可能把 TMPDIR 覆盖为 Termux 包名路径，Android 上不存在）
            "DSH_SUBSYSTEM_ENV" to (subsystemEnvJson(context) ?: ""),
        )
    }

    /** 构造子系统包裹 argv（[引擎, 挂载参数…, /bin/bash]）；引擎不可用时返回 null。 */
    private fun subsystemArgvJson(context: Context): String? {
        // root shell 优先：已启用且授权后不再进子系统
        if (rootMode(context)) return null
        if (!AppSettings.subsystemShellEnabled(context)) return null
        // UML 引擎优先：umarm-cmd FIFO/文件协议 wrapper（guest 内真 root 执行）
        if (SubsystemManager.isUmlEngine(context) &&
            SubsystemManager.umlAvailable(context) &&
            SubsystemManager.isUmlInstalled(context) &&
            SubsystemManager.umlRunning(context)
        ) {
            return JSONArray(listOf(SubsystemManager.umarmCmdBin(context).absolutePath)).toString()
        }
        return prootArgvJson(context)
    }

    /** root shell 是否生效（开关开启 + su 存在 + 已授权）。 */
    private fun rootMode(context: Context): Boolean =
        AppSettings.rootShellEnabled(context) &&
            RootManager.suPath() != null &&
            RootManager.isGranted()

    /** root shell argv（[suPath, bashPath]）；未生效时返回 null。 */
    private fun rootArgvJson(context: Context): String? {
        if (!rootMode(context)) return null
        val su = RootManager.suPath() ?: return null
        val bash = File(nativeLibDir(context), "libbash.so").absolutePath
        return JSONArray(listOf(su, bash)).toString()
    }

    private fun prootArgvJson(context: Context): String? {
        val proot = SubsystemManager.prootBin(context)
        val rootfs = SubsystemManager.rootfsDir(context)
        // 依赖库与 rootfs 必须齐全才启用 proot 包裹；缺任一则回退原生 bash，
        // 避免 bash 命令通道整体失效造成引导死锁（bash 是 agent 唯一执行通道）。
        val libtalloc = File(prefix(context), "lib/libtalloc.so.2")
        val shmem = File(nativeLibDir(context), "libandroid-shmem.so")
        if (!proot.exists() ||
            !libtalloc.exists() ||
            !shmem.exists() ||
            !File(rootfs, "etc").isDirectory ||
            !File(rootfs, "bin/bash").exists()
        ) {
            return null
        }
        val resolv = SubsystemManager.resolvConf(context).absolutePath
        val ws = workspace(context)
        val argv = mutableListOf<String>()
        argv += proot.absolutePath
        // DSHA 验证过的稳健参数：link2symlink + 跟随链接 + kill-on-exit + fake root
        argv += "--link2symlink"; argv += "-L"; argv += "--kill-on-exit"; argv += "-0"
        argv += "-r"; argv += rootfs.absolutePath
        argv += "--cwd=/root"
        argv += "-b"; argv += "/dev"
        argv += "-b"; argv += "/dev/urandom:/dev/random"
        argv += "-b"; argv += "/dev/pts"
        argv += "-b"; argv += "/proc"
        argv += "-b"; argv += "/sys"
        argv += "-b"; argv += "/proc/self/fd:/dev/fd"
        argv += "-b"; argv += "$resolv:/etc/resolv.conf"
        argv += "-b"; argv += "${dshHome(context).absolutePath}:/root/dsh"
        // GUI 控制通道 CLI（dsh 会话 Computer Use 入口）：单文件 bind 到 guest PATH
        val guiScript = File(prefix(context), "bin/gui")
        if (guiScript.exists()) {
            argv += "-b"; argv += "${guiScript.absolutePath}:/usr/local/bin/gui"
            argv += "-b"; argv += "/system/bin/sh:/system/bin/sh"
        }
        if (ws.exists() && ws.canRead()) {
            argv += "-b"; argv += "${ws.absolutePath}:/workspace"
        }
        argv += "-b"; argv += "${tmp(context).absolutePath}:/tmp"
        argv += "/bin/bash"
        return JSONArray(argv).toString()
    }

    /** 子系统相关进程 env（proot glue 临时目录；TMPDIR 指向 guest /tmp）。 */
    private fun subsystemEnvJson(context: Context): String? {
        if (subsystemArgvJson(context) == null) return null
        return JSONObject().apply {
            put("TMPDIR", "/tmp")
            // proot glue 临时目录（DSH 可能把 TMPDIR 覆盖为 Termux 包名路径，Android 上不存在）
            put("PROOT_TMP_DIR", tmp(context).absolutePath)
            // termux proot 内置 loader（ptrace 方案），无需 PROOT_LOADER
            // guest 视角 PATH/HOME：子系统子进程若继承宿主的 PATH
            //（/data/user/0/.../files/bin，guest 内不存在），bash 里命令全部
            // "command not found"。必须覆盖为 Debian rootfs 路径。
            put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            put("HOME", "/root")
        }.toString()
    }

    /**
     * 装配命令的子系统 argv（rootfs 内原生 Linux 工具链，DSH-Folk 方案）：
     * 在通用 bind 之外额外挂载 dsh 运行时（prefix/lib/node_modules → /opt/dsh-libs）
     * 与插件下载缓存（filesDir/downloads → /siliconleap-downloads），
     * 命令以 /bin/bash -c 执行。子系统未安装或引擎不可用时返回 null。
     */
    internal fun assemblyArgv(context: Context, cmd: String): List<String>? {
        // UML 引擎：umarm-cmd 文件协议通道（guest 内真 root 执行）
        if (SubsystemManager.isUmlEngine(context) &&
            SubsystemManager.umlAvailable(context) &&
            SubsystemManager.isUmlInstalled(context) &&
            SubsystemManager.umlRunning(context)
        ) {
            return listOf(SubsystemManager.umarmCmdBin(context).absolutePath, cmd)
        }
        val rootfs = SubsystemManager.rootfsDir(context)
        if (!SubsystemManager.isInstalled(context)) return null
        val proot = SubsystemManager.prootBin(context)
        if (!proot.exists()) return null
        val resolv = SubsystemManager.resolvConf(context).absolutePath
        val ws = workspace(context)
        val libs = File(prefix(context), "lib/node_modules").absolutePath
        val downloads = File(filesDir(context), "downloads").absolutePath
        val argv = mutableListOf<String>()
        argv += proot.absolutePath
        argv += "--link2symlink"; argv += "-L"; argv += "--kill-on-exit"; argv += "-0"
        argv += "-r"; argv += rootfs.absolutePath
        argv += "--cwd=/root"
        // bind 支持 -b <src>:<dst> 冒号格式；同路径也统一写全
        fun bind(src: String, dst: String) {
            argv += "-b"
            argv += "$src:$dst"
        }
        bind("/dev", "/dev")
        bind("/dev/urandom", "/dev/random")
        bind("/proc/self/fd", "/dev/fd")
        bind("/proc", "/proc")
        bind("/sys", "/sys")
        bind(resolv, "/etc/resolv.conf")
        bind(dshHome(context).absolutePath, "/root/dsh")
        if (ws.exists() && ws.canRead()) bind(ws.absolutePath, "/workspace")
        bind(tmp(context).absolutePath, "/tmp")
        bind(libs, "/opt/dsh-libs")
        if (File(downloads).exists()) bind(downloads, "/siliconleap-downloads")
        argv += "/bin/bash"
        argv += "-c"
        argv += cmd
        return argv
    }

    /** 装配命令的子系统进程 env（TMPDIR / proot loader / guest PATH）。 */
    internal fun assemblyEnv(context: Context): Map<String, String> = mapOf(
        "TMPDIR" to "/tmp",
        "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "HOME" to "/root",
        "PROOT_TMP_DIR" to tmp(context).absolutePath,
    )
}
