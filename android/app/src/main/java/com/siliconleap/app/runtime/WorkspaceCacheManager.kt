package com.siliconleap.app.runtime

import android.content.Context
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray

/**
 * 工作区缓存层分离：把「海量小文件依赖/缓存目录」从用户工作区（公共存储，
 * Android 11+ 走 FUSE，元数据 syscall 慢 5 倍以上）迁移到应用私有目录
 * （f2fs 原生文件系统），再以 tawcroot/proot 的 bind 覆盖回 guest 内的原路径。
 *
 * 为什么是 bind 而不是符号链接：Android 11+ 共享存储（FUSE）禁止创建符号
 * 链接（EPERM），宿主侧无法把 sdcard 目录替换成指向私有目录的 symlink；
 * 而 tawcroot 的 bind 路由是「最长前缀匹配」（route_through_binds），
 * `-b <私有缓存>/<key>:/workspace/<proj>/node_modules` 会覆盖外层
 * `-b <用户工作区>:/workspace` 的同名子路径，guest 内看到的仍是普通目录，
 * npm / node 模块解析完全透明。
 *
 * 收益：递归 stat 这类元数据密集操作的作用域从 FUSE 落到原生 fs；源码文件
 * 仍留在用户设置的 sdcard 路径里可被文件管理器直接访问。
 *
 * 数据边界：缓存目录内容视为可重建产物（依赖树、构建缓存），迁移失败时
 * 保持原目录不动并放弃该 bind，绝不因迁移丢源码。
 */
object WorkspaceCacheManager {

    /** 缓存层开关（默认开）。 */
    fun enabled(context: Context): Boolean = AppSettings.workspaceCacheEnabled(context)

    fun setEnabled(context: Context, enabled: Boolean) {
        AppSettings.setWorkspaceCacheEnabled(context, enabled)
    }

    /** 原生缓存根：files/workspace-cache（f2fs，无 FUSE 层）。 */
    fun cacheRoot(context: Context): File = File(TermuxEnv.filesDir(context), "workspace-cache")

    /**
     * 可迁移的重目录名（依赖树与构建缓存）。
     * 刻意不含 dist / build —— 那是用户交付物，需在 sdcard 直接可见。
     */
    private val HEAVY_DIRS = setOf(
        "node_modules", ".pnpm-store", ".npm", ".yarn", ".cache",
        "__pycache__", ".mypy_cache", ".pytest_cache", ".ruff_cache", ".tox",
        ".venv", "venv", ".gradle", ".cargo", ".m2",
        ".next", ".nuxt", ".svelte-kit", ".turbo", ".parcel-cache", ".angular",
        ".expo", ".dart_tool", "target",
    )

    /** 项目根标志文件：命中即认为该目录是项目根，才在其下探测重目录。 */
    private val PROJECT_MARKERS = setOf(
        "package.json", "pnpm-workspace.yaml", "pyproject.toml", "requirements.txt",
        "go.mod", "Cargo.toml", "build.gradle", "build.gradle.kts", "pom.xml",
        "composer.json", "Gemfile",
    )

    private const val MAX_SCAN_DEPTH = 3
    private const val MAX_SCAN_DIRS = 4000
    private const val SCAN_BUDGET_MS = 4_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun cacheLogFile(context: Context): File =
        File(TermuxEnv.logs(context), "workspace-cache.log")

    private fun appendLog(context: Context, line: String) {
        runCatching { LogStore.named(cacheLogFile(context)).append(line) }
    }

    /**
     * 工作区是否位于公共存储（需要缓存层）。应用私有目录（filesDir 下）本身
     * 就是 f2fs 原生文件系统，无 FUSE 元数据放大，无需也不应做缓存层迁移。
     */
    fun needed(context: Context): Boolean {
        val ws = TermuxEnv.workspace(context)
        val privateRoot = TermuxEnv.filesDir(context).absolutePath
        return !ws.absolutePath.startsWith(privateRoot)
    }

    /**
     * 供 argv 组装使用的 bind 列表（host 绝对缓存目录 → guest 工作区内路径）。
     * 只读注册表，不做扫描；扫描/迁移由 [refreshAsync] 在服务启动时后台完成。
     */
    fun heavyBinds(context: Context): List<Pair<String, String>> {
        if (!enabled(context) || !needed(context)) return emptyList()
        val rels = readRegistry(context) ?: return emptyList()
        val root = cacheRoot(context)
        return rels.mapNotNull { rel ->
            val dir = File(root, cacheKey(rel))
            if (dir.isDirectory) dir.absolutePath to "/workspace/$rel" else null
        }
    }

    /** 后台刷新：扫描工作区、迁移新发现的重目录、清理失效项（服务启动时调用）。 */
    fun refreshAsync(context: Context) {
        if (!enabled(context) || !needed(context)) return
        val ctx = context.applicationContext
        scope.launch { runCatching { refresh(ctx) } }
    }

    /** 立即扫描并迁移（供设置页「立即优化」调用，需在 IO 线程）。 */
    fun refreshNow(context: Context) {
        if (!enabled(context) || !needed(context)) return
        refresh(context.applicationContext)
    }

    /** 扫描 + 迁移 + 落注册表。返回保留的 bind 数量。 */
    fun refresh(context: Context): Int {
        val ws = TermuxEnv.workspace(context)
        if (!ws.isDirectory) return 0
        val found = scanHeavyDirs(ws)
        val root = cacheRoot(context)
        root.mkdirs()
        val keep = mutableListOf<String>()
        var migrated = 0
        for (rel in found) {
            val src = File(ws, rel)
            val dst = File(root, cacheKey(rel))
            if (dst.isDirectory) {
                // 上次已迁移：sdcard 侧若残留旧副本，移除（内容是可重建产物）
                if (src.isDirectory && !isSymlink(src)) runCatching { src.deleteRecursively() }
                keep.add(rel)
            } else if (src.isDirectory && !isSymlink(src)) {
                if (migrate(root, rel, src, dst)) { keep.add(rel); migrated++ } else keep.add(rel)
            }
        }
        // 清理缓存层中已无对应项的残留（项目被删）
        runCatching {
            val keepKeys = keep.map { cacheKey(it) }.toSet()
            root.listFiles()?.forEach { f ->
                if (f.isDirectory && f.name != "tmp" && f.name !in keepKeys) f.deleteRecursively()
            }
        }
        writeRegistry(context, keep)
        if (migrated > 0) {
            appendLog(
                context,
                "> 工作区缓存层：迁移 $migrated 个重目录到原生 fs，重启服务后生效（bind ${keep.size} 项）",
            )
        }
        return keep.size
    }

    /** 计算重目录集合（相对工作区根）。带深度/数量/时间预算，超限即停。 */
    private fun scanHeavyDirs(ws: File): List<String> {
        val out = mutableListOf<String>()
        val start = System.currentTimeMillis()
        var visited = 0
        fun walk(dir: File, rel: String, depth: Int) {
            if (depth > MAX_SCAN_DEPTH || visited >= MAX_SCAN_DIRS) return
            if (System.currentTimeMillis() - start > SCAN_BUDGET_MS) return
            val children = runCatching { dir.listFiles() }.getOrNull() ?: return
            val isProject = children.any { it.isFile && it.name in PROJECT_MARKERS }
            for (c in children) {
                if (visited >= MAX_SCAN_DIRS || System.currentTimeMillis() - start > SCAN_BUDGET_MS) return
                if (!c.isDirectory || isSymlink(c)) continue
                visited++
                val childRel = if (rel.isEmpty()) c.name else "$rel/${c.name}"
                if (isProject && c.name in HEAVY_DIRS) {
                    out.add(childRel)
                } else {
                    // 非重目录才继续下钻（重目录整体迁移，不递归进其内部）
                    walk(c, childRel, depth + 1)
                }
            }
        }
        walk(ws, "", 0)
        return out
    }

    /**
     * 迁移单个重目录：优先同盘 rename（缓存内 tmp 后改名），跨文件系统回退
     * 递归复制 + 删源。任一步失败即清理半成品并返回 false（源保持不动）。
     */
    private fun migrate(root: File, rel: String, src: File, dst: File): Boolean {
        val tmp = File(root, "tmp/${cacheKey(rel)}")
        return runCatching {
            if (dst.exists()) return true
            tmp.parentFile?.mkdirs()
            runCatching { tmp.deleteRecursively() }
            if (src.renameTo(tmp)) {
                return tmp.renameTo(dst) || run { tmp.deleteRecursively(); false }
            }
            // 跨 fs：复制后删源
            if (!copyRecursively(src, tmp)) { tmp.deleteRecursively(); return false }
            if (!tmp.renameTo(dst)) { tmp.deleteRecursively(); return false }
            runCatching { src.deleteRecursively() }
            true
        }.getOrDefault(false)
    }

    private fun copyRecursively(src: File, dst: File): Boolean = runCatching {
        if (src.isDirectory) {
            dst.mkdirs()
            src.listFiles()?.forEach { if (!copyRecursively(it, File(dst, it.name))) return false }
        } else {
            dst.parentFile?.mkdirs()
            src.copyTo(dst, overwrite = true)
        }
        true
    }.getOrDefault(false)

    private fun isSymlink(f: File): Boolean = runCatching {
        f.canonicalPath != f.absolutePath
    }.getOrDefault(false)

    /** 稳定 key：可读叶子名 + rel 路径 SHA-1 前 16 位（同层级重名不冲突）。 */
    private fun cacheKey(rel: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        val h = md.digest(rel.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
        val leaf = rel.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_").take(32)
        return "$leaf-$h"
    }

    private fun registryFile(context: Context): File =
        File(cacheRoot(context), "registry.json")

    private fun readRegistry(context: Context): List<String>? = runCatching {
        val f = registryFile(context)
        if (!f.exists()) return null
        val arr = JSONArray(f.readText())
        (0 until arr.length()).map { arr.getString(it) }
    }.getOrNull()

    private fun writeRegistry(context: Context, rels: List<String>) {
        runCatching {
            val f = registryFile(context)
            f.parentFile?.mkdirs()
            val arr = JSONArray()
            rels.forEach { arr.put(it) }
            f.writeText(arr.toString())
        }
    }
}
