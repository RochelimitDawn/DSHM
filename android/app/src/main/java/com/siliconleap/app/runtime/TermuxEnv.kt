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

    /**
     * pnpm 全局 store 固定路径（host 视角）。
     *
     * 背景（ERR_PNPM_UNEXPECTED_STORE）：pnpm 在 node_modules/.modules.yaml 记录
     * 上次安装的 storeDir，启动时按当前环境重新解析 store，二者经 path.relative
     * 词法比较不等即拒绝安装。以往 host（HOME=files/home → files/home/.local/...）
     * 与 guest（HOME=/root → /root/.local/...）两套视角混用同一 profile，必然对不上。
     *
     * 方向一：全局钉死一个 host 绝对路径字符串（host 与 guest 都用它），并在 guest
     * 内把该物理目录 bind 到同一字符串路径，使两侧解析结果逐字节一致。
     */
    fun pnpmStoreDir(context: Context): File =
        File(home(context), ".local/share/pnpm/store")

    /**
     * 把 pnpm store 物理目录 bind 进 guest 的**同一绝对路径字符串**：
     * guest 内 `npm_config_store_dir` 指向 host 绝对路径，pnpm 在 guest 视图下
     * 按该字符串寻址，恰好命中此 bind，解析出的 storeDir 与 host 逐字节一致。
     * 目录不存在时先创建（bind 源必须存在）。
     */
    private fun bindPnpmStore(context: Context, bind: (String, String) -> Unit) {
        val store = pnpmStoreDir(context)
        runCatching { store.mkdirs() }
        if (store.isDirectory) bind(store.absolutePath, store.absolutePath)
    }

    /** 工作区：默认应用私有目录 workspace，用户可在设置中改为公共存储路径。 */
    fun workspace(context: Context): File = File(AppSettings.workspacePath(context))

    // 日志放 dsh-home/logs（guest 内 /root/dsh/logs/* 可见）——AI 会话能直接读
    // server/subsystem/addon 全部日志（files/logs 在 guest 视图不存在）
    fun logs(context: Context): File = File(dshHome(context), "logs")
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

    /** 单引号转义（shell-ready，含空格/特殊字符的路径安全传递）。 */
    private fun shellEscape(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /**
     * 预构造 proot 命令行（shell-ready，以 /bin/bash 结尾）：混合调度器的 proot
     * 路由用 dispatch wrapper eval 执行。proot 不可用时返回 null（wrapper 降级）。
     */
    private fun prootCmdLine(context: Context): String? {
        val argv = prootArgvJson(context) ?: return null
        val arr = org.json.JSONArray(argv)
        val parts = mutableListOf<String>()
        // proot（Termux 构建）编译期默认临时目录硬编码 /data/data/com.termux/files/usr/tmp/，
        // 且 dsh 会话会把 TMPDIR 覆盖为 Termux 路径 → PROOT_TMP_DIR 未传入时 glue rootfs
        // canonicalize 失败，全部命令瘫痪。
        // 经 env(1) 前缀传递（dispatch 为 eval "exec $PROOT_CMD"，bash/dash 的 exec
        // 不接受前缀赋值，env 是唯一可靠的传参方式），随 PROOT_CMD 优先级高于会话内 TMPDIR。
        val tmpPath = tmp(context).absolutePath
        parts.add("env")
        parts.add("PROOT_TMP_DIR=${shellEscape(tmpPath)}")
        parts.add("TMPDIR=${shellEscape(tmpPath)}")
        for (i in 0 until arr.length()) {
            parts.add(shellEscape(arr.getString(i)))
        }
        return parts.joinToString(" ")
    }

    /** 启动 node 服务进程时的环境变量。 */
    /**
     * libnode.so（Termux 构建）编译期 OPENSSLDIR 硬编码 /data/data/com.termux/files/usr/etc/tls。
     * 装有 Termux 的设备：目录存在但属 Termux 应用（0700）→ 本应用 fopen EACCES →
     * OpenSSL configuration error，node 服务启动即死；未装 Termux 的设备为 ENOENT，
     * OpenSSL 容忍跳过——这就是「装了 Termux 的用户必崩」的根因。
     * 返回本应用运行时的 openssl.cnf（缺失则写最小配置），经 OPENSSL_CONF 绕开 Termux 路径探测。
     */
    internal fun ensureOpensslConf(context: Context): String {
        val f = File(prefix(context), "etc/tls/openssl.cnf")
        if (!f.exists()) {
            f.parentFile?.mkdirs()
            f.writeText("# minimal OpenSSL config (DSHM)\n[openssl_init]\n")
        }
        return f.absolutePath
    }

    fun serverEnv(context: Context): Map<String, String> {
        val prefix = prefix(context).absolutePath
        val nativeLib = nativeLibDir(context).absolutePath
        val binLinks = binLinks(context).absolutePath
        val env = LinkedHashMap(
            mapOf(
                "PREFIX" to prefix,
                "HOME" to home(context).absolutePath,
                "TMPDIR" to tmp(context).absolutePath,
                // proot（Termux 构建）编译期默认临时目录硬编码 /data/data/com.termux/files/usr/tmp/，
                // 本应用下不存在。放到服务进程 env：所有 dsh 会话（含 dispatch wrapper、
                // 会话内手动跑 proot、glue 脚本）都继承，仅靠 PROOT_CMD 的 env 前缀覆盖不全
                "PROOT_TMP_DIR" to tmp(context).absolutePath,
                "DSH_HOME" to dshHome(context).absolutePath,
                "PATH" to "$binLinks:$nativeLib:$prefix/bin:$prefix/bin/node_modules/.bin",
                "LD_LIBRARY_PATH" to "$nativeLib:$prefix/lib",
                "TERM" to "xterm-256color",
                "LANG" to "en_US.UTF-8",
                // V8 堆上限：node 服务内存防无限增长（12GB 设备后台挤压场景下
                // 可用内存有限，512MB 足够 dsh 服务与会话使用）
                "NODE_OPTIONS" to "--max-old-space-size=512",
                // Termux curl 编译期 CA 路径指向 /data/data/com.termux/...，本应用下不存在；
                // 显式指定运行时证书（避免 curl 证书校验失败）
                "CURL_CA_BUNDLE" to "$prefix/etc/tls/cert.pem",
                // libnode.so（Termux 构建）编译期 OPENSSLDIR 指向 /data/data/com.termux/...：
                // 装有 Termux 的设备上该目录存在但属 Termux 应用（0700），fopen EACCES →
                // "OpenSSL configuration error" node 启动即死；未装 Termux 为 ENOENT 可容忍。
                // 显式指定本应用运行时的 openssl.cnf（缺失则写最小配置）绕开 Termux 路径
                "OPENSSL_CONF" to ensureOpensslConf(context),
                // dsh plugin 装配（dsh-mobile）需 pnpm；app 数据目录 noexec，pnpm 脚本与
                // PATH 中 node 符号链接均无法 exec。PNPM_NODE/PNPM_CJS 让 dsh plugin
                // 用 node 绝对路径直接运行 pnpm.cjs（Patch 14 读取）。
                "PNPM_NODE" to "$nativeLib/libnode.so",
                "PNPM_CJS" to "$prefix/lib/node_modules/pnpm/bin/pnpm.cjs",
                // pnpm store 固定（ERR_PNPM_UNEXPECTED_STORE）：host 与 guest 统一用
                // 同一 host 绝对路径字符串，guest 侧由 bind 挂载同一物理目录。
                // 优先级高于 pnpm 自身 HOME 推导（guest HOME=/root 会推出 /root/.local/...）
                "npm_config_store_dir" to pnpmStoreDir(context).absolutePath,
                // 可执行文件直接用 nativeLibraryDir 绝对路径（app 数据目录被 SELinux 禁止执行，
                // filesDir/bin 符号链接 exec 会 EACCES；nativeLibraryDir 与 node 服务同样可执行）
                "DSH_RG_PATH" to "$nativeLib/librg.so",
                "DSH_BASH_PATH" to "$nativeLib/libbash.so",
                "DSH_SH_PATH" to "$nativeLib/libsh.so",
                // SHELL：subprocess-local 的 terminalEnvironment() 以 process.env.SHELL
                // 作为默认 shell（dsh-terminal-bash 的 shellPath 只是显式覆盖）。运行时
                // 源自 Termux bootstrap，其 profile 把 SHELL 设成编译期前缀
                // /data/data/com.termux/files/usr/bin/bash（本应用下不存在），dsh 终端
                // 兜底用 SHELL 时 exec 该路径 → "command ... is not an executable file"。
                // 显式指向本应用可执行的 bash（nativeLibraryDir，SELinux 放行），彻底
                // 消除 Termux 前缀残留。
                "SHELL" to "$nativeLib/libbash.so",
                // 运行时自带 npmrc（update-notifier=false）：pnpm 的更新提示会把用户
                // 引向 pnpm add -g pnpm，而 12.x 的启动器包在 --ignore-scripts 下不可用
                "NPM_CONFIG_USERCONFIG" to "$prefix/etc/npmrc",
                // Debian 子系统（proot）：DSH shell/terminal 的 bash argv 前缀。
                // patch_runtime.js 的 Patch 10 读 DSH_SUBSYSTEM_ARGV（JSON 数组）包裹 bash；
                // 开关关闭或子系统未安装时为空，回退原生 bash。
                "DSH_SUBSYSTEM_ARGV" to (subsystemArgvJson(context) ?: ""),
                // patch_runtime.js 的 Patch 11 读 DSH_SUBSYSTEM_ENV（JSON 对象）合并进
                // bash spawn env：guest 视角 PATH/HOME/TMPDIR。缺失时 guest 继承宿主
                // PATH（files/bin、usr/bin，guest 内不存在），coreutils 全部 127
                // （ls/cat/df command not found）。子系统包裹生效才注入（非空 JSON）。
                "DSH_SUBSYSTEM_ENV" to (subsystemEnvJson(context) ?: ""),
                // UML 引擎：umarm-cmd 命令通道共享目录（libumarm-cmd.so wrapper 读取）
                "DSH_UMARM_SHARE" to SubsystemManager.shareDir(context).absolutePath,
                // 混合调度器（hybrid）：预构造 proot 命令行 + umarm 通道 + UML 运行状态
                "DSH_DISPATCH_PROOT_CMD" to (prootCmdLine(context) ?: ""),
                "DSH_DISPATCH_UMARM_CMD" to SubsystemManager.umarmCmdBin(context).absolutePath,
                "DSH_DISPATCH_UML_READY" to if (SubsystemManager.isUmlEngine(context) &&
                    SubsystemManager.umlAvailable(context) &&
                    SubsystemManager.umlRunning(context)
                ) "1" else "0",
            ),
        )
        return env
    }

    /** 构造子系统包裹 argv（[引擎, 挂载参数…, /bin/bash]）；引擎不可用时返回 null。 */
    internal fun subsystemArgvJson(context: Context): String? {
        // root shell 优先：已启用且授权后不再进子系统
        if (rootMode(context)) return null
        if (!AppSettings.subsystemShellEnabled(context)) return null
        // 混合调度引擎：dispatch wrapper 按命令特征路由 proot/UML。
        // auto 同样走 dispatch（脚本对非 UML 命令自动降级 proot）——DSH_SUBSYSTEM_ARGV
        // 是服务启动时的静态快照，UML 预启动在 server 启动后异步完成，若此处按
        // umlRunning 判定会永久钉死 proot，UML 路由永远不生效。
        if ((AppSettings.subsystemEngine(context) == AppSettings.SUBSYSTEM_ENGINE_HYBRID ||
                AppSettings.subsystemEngine(context) == AppSettings.SUBSYSTEM_ENGINE_AUTO) &&
            SubsystemManager.isInstalled(context) &&
            SubsystemManager.umlAvailable(context)
        ) {
            return JSONArray(listOf(SubsystemManager.dispatchBin(context).absolutePath)).toString()
        }
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
        val rootfs = SubsystemManager.rootfsDir(context)
        // rootfs 必须齐全才启用子系统包裹；缺任一则回退原生 bash，
        // 避免 bash 命令通道整体失效造成引导死锁（bash 是 agent 唯一执行通道）。
        if (!File(rootfs, "etc").isDirectory || !File(rootfs, "bin/bash").exists()) {
            return null
        }
        return tawcrootArgvJson(context) ?: legacyProotArgvJson(context)
    }

    /**
     * tawcroot 包裹 argv（优先）：seccomp systrap 方案，单进程无 ptrace，
     * 性能比 proot 快 2.5-7.5 倍。CLI 仅 -r/-b/--；fake-root 恒开、hardlink
     * 仿真内建（对应 proot 的 --link2symlink）。guest cwd = tawcroot 进程 cwd
     * （node 服务 cwd = workspace，已 bind 到 guest /workspace，getcwd 反向翻译成立）。
     */
    private fun tawcrootArgvJson(context: Context): String? {
        val tawcroot = SubsystemManager.tawcrootBin(context)
        if (!tawcroot.exists()) return null
        val rootfs = SubsystemManager.rootfsDir(context)
        val ws = workspace(context)
        val argv = mutableListOf<String>()
        argv += tawcroot.absolutePath
        argv += "-r"; argv += rootfs.absolutePath
        argv += "-b"; argv += "/dev:/dev"
        argv += "-b"; argv += "/dev/pts:/dev/pts"
        argv += "-b"; argv += "/proc:/proc"
        argv += "-b"; argv += "/sys:/sys"
        argv += "-b"; argv += "/proc/self/fd:/dev/fd"
        argv += "-b"; argv += "${dshHome(context).absolutePath}:/root/dsh"
        if (ws.exists() && ws.canRead()) {
            argv += "-b"; argv += "${ws.absolutePath}:/workspace"
            // 缓存层：重目录 bind 到原生 fs（最长前缀匹配覆盖工作区 bind 的子路径）
            WorkspaceCacheManager.heavyBinds(context).forEach { (src, dst) ->
                argv += "-b"; argv += "$src:$dst"
            }
        }
        // pnpm store 固定：guest 内同一 host 字符串路径（ERR_PNPM_UNEXPECTED_STORE）
        bindPnpmStore(context) { src, dst -> argv += "-b"; argv += "$src:$dst" }
        argv += "-b"; argv += "${tmp(context).absolutePath}:/tmp"
        argv += "--"
        argv += "/bin/bash"
        return JSONArray(argv).toString()
    }

    /** proot 包裹 argv（回退，Termux 构建 ptrace 方案）。 */
    private fun legacyProotArgvJson(context: Context): String? {
        val proot = SubsystemManager.prootBin(context)
        // 依赖库必须齐全才启用 proot 包裹
        val libtalloc = File(prefix(context), "lib/libtalloc.so.2")
        val shmem = File(nativeLibDir(context), "libandroid-shmem.so")
        if (!proot.exists() || !libtalloc.exists() || !shmem.exists()) {
            return null
        }
        val rootfs = SubsystemManager.rootfsDir(context)
        val ws = workspace(context)
        val argv = mutableListOf<String>()
        argv += proot.absolutePath
        // DSHA 验证过的稳健参数：link2symlink + 跟随链接 + kill-on-exit + fake root
        argv += "--link2symlink"; argv += "-L"; argv += "--kill-on-exit"; argv += "-0"
        argv += "-r"; argv += rootfs.absolutePath
        argv += "--cwd=/root"
        argv += "-b"; argv += "/dev"
        argv += "-b"; argv += "/dev/pts"
        argv += "-b"; argv += "/proc"
        argv += "-b"; argv += "/sys"
        argv += "-b"; argv += "/proc/self/fd:/dev/fd"
        argv += "-b"; argv += "${dshHome(context).absolutePath}:/root/dsh"
        if (ws.exists() && ws.canRead()) {
            argv += "-b"; argv += "${ws.absolutePath}:/workspace"
            // 缓存层：重目录 bind 到原生 fs（最长前缀匹配覆盖工作区 bind 的子路径）
            WorkspaceCacheManager.heavyBinds(context).forEach { (src, dst) ->
                argv += "-b"; argv += "$src:$dst"
            }
        }
        // pnpm store 固定：guest 内同一 host 字符串路径（ERR_PNPM_UNEXPECTED_STORE）
        bindPnpmStore(context) { src, dst -> argv += "-b"; argv += "$src:$dst" }
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
            // "command not found"。必须覆盖为 Debian rootfs 路径；
            // /opt/node/bin 是 rootfs 内 node 引导安装点（AI 会话跑 node 工具必需）
            put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/opt/node/bin")
            put("HOME", "/root")
            // guest 内的默认 shell：宿主 SHELL 指向 nativeLibraryDir 的 libbash.so，
            // 该路径在 guest 视图不存在；子系统会话应指向 guest 自身的 /bin/bash，
            // 避免 subprocess-local 在 guest 视角解析默认 shell 失败
            put("SHELL", "/bin/bash")
        }.toString()
    }

    /**
     * 装配命令的子系统 argv（rootfs 内原生 Linux 工具链，DSH-Folk 方案）：
     * 在通用 bind 之外额外挂载 dsh 运行时（prefix/lib/node_modules → /opt/node_modules）
     * 与插件下载缓存（filesDir/downloads，guest 内同路径 bind），
     * 命令以 /bin/bash -c 执行。子系统未安装或引擎不可用时返回 null。
     */
    internal fun assemblyArgv(context: Context, cmd: String): List<String>? {
        // 装配通道走 Debian rootfs 布局：装配命令引用独有挂载点
        // （files/downloads 下载缓存、/opt/node_modules 运行时、rootfs /opt/node）。
        // 优先 tawcroot（systrap，性能 2.5-7.5 倍），回退 proot。
        val rootfs = SubsystemManager.rootfsDir(context)
        if (!SubsystemManager.isInstalled(context)) return null
        val tawcroot = SubsystemManager.tawcrootBin(context)
        val proot = SubsystemManager.prootBin(context)
        if (!tawcroot.exists() && !proot.exists()) return null
        val ws = workspace(context)
        val libs = File(prefix(context), "lib/node_modules").absolutePath
        val downloads = File(filesDir(context), "downloads").absolutePath
        val argv = mutableListOf<String>()
        // tawcroot CLI 仅 -r/-b/--；proot 需要 link2symlink 等附加参数
        if (tawcroot.exists()) argv += tawcroot.absolutePath else argv += proot.absolutePath
        if (!tawcroot.exists()) {
            argv += "--link2symlink"; argv += "-L"; argv += "--kill-on-exit"; argv += "-0"
        }
        argv += "-r"; argv += rootfs.absolutePath
        if (!tawcroot.exists()) argv += "--cwd=/root"
        // bind 支持 -b <src>:<dst> 冒号格式；同路径也统一写全
        fun bind(src: String, dst: String) {
            argv += "-b"
            argv += "$src:$dst"
        }
        bind("/dev", "/dev")
        bind("/proc/self/fd", "/dev/fd")
        bind("/proc", "/proc")
        bind("/sys", "/sys")
        bind(dshHome(context).absolutePath, "/root/dsh")
        if (ws.exists() && ws.canRead()) bind(ws.absolutePath, "/workspace")
        // 缓存层：重目录 bind 到原生 fs（最长前缀匹配覆盖工作区 bind 的子路径）
        WorkspaceCacheManager.heavyBinds(context).forEach { (src, dst) -> bind(src, dst) }
        // pnpm store 固定：guest 内同一 host 字符串路径（ERR_PNPM_UNEXPECTED_STORE）
        bindPnpmStore(context) { src, dst -> bind(src, dst) }
        bind(tmp(context).absolutePath, "/tmp")
        bind(libs, "/opt/node_modules")
        // 下载缓存：bind 到**同一 host 绝对路径**（不再用 /siliconleap-downloads 别名）。
        // 别名路径会被 pnpm 以 `file:` spec 持久化进 profile manifest，而市场/装配在
        // host 视角读该 spec 时别名不存在（ENOENT）——同 store 修法：host 与 guest 用
        // 同一字符串，两套视角都能解析。
        if (File(downloads).exists()) bind(downloads, downloads)
        // tawcroot 以 -- 分隔命令；proot 直接跟命令
        if (tawcroot.exists()) argv += "--"
        argv += "/bin/bash"
        argv += "-c"
        argv += cmd
        return argv
    }

    /** 装配命令的子系统进程 env（TMPDIR / proot loader / guest PATH）。 */
    internal fun assemblyEnv(context: Context): Map<String, String> {
        val env = mutableMapOf(
            "TMPDIR" to "/tmp",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/opt/node/bin",
            "HOME" to "/root",
            // guest 视角默认 shell（宿主 SHELL=libbash.so 在 rootfs 内不存在）
            "SHELL" to "/bin/bash",
            "PROOT_TMP_DIR" to tmp(context).absolutePath,
        )
        return env
    }
}
