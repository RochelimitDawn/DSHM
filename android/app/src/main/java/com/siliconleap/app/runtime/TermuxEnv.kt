package com.siliconleap.app.runtime

import android.content.Context
import java.io.File

/** 运行时目录与环境变量约定（Termux-style prefix）。 */
object TermuxEnv {
    const val RUNTIME_ASSET = "runtime.zip"

    fun filesDir(context: Context): File = context.filesDir

    /** Termux prefix，即运行时根目录 `filesDir/usr`。 */
    fun prefix(context: Context): File = File(filesDir(context), "usr")

    fun nodeBin(context: Context): File = File(prefix(context), "bin/node")

    fun isRuntimeReady(context: Context): Boolean = nodeBin(context).exists()

    fun home(context: Context): File = File(filesDir(context), "home")
    fun tmp(context: Context): File = File(filesDir(context), "tmp")
    fun dshHome(context: Context): File = File(filesDir(context), "dsh-home")
    fun workspace(context: Context): File = File(filesDir(context), "workspace")
    fun logs(context: Context): File = File(filesDir(context), "logs")
    fun serverLog(context: Context): File = File(logs(context), "server.log")

    /** dsh CLI 入口（npm 包安装于 `$PREFIX/lib/node_modules`）。 */
    fun dshEntry(context: Context): File = File(prefix(context), "lib/node_modules/@deepseek-ai/dsh/lib/bin.js")

    fun dshEntryExists(context: Context): Boolean = dshEntry(context).exists()

    /** 启动 node 服务进程时的环境变量。 */
    fun serverEnv(context: Context): Map<String, String> {
        val prefix = prefix(context).absolutePath
        return mapOf(
            "PREFIX" to prefix,
            "HOME" to home(context).absolutePath,
            "TMPDIR" to tmp(context).absolutePath,
            "DSH_HOME" to dshHome(context).absolutePath,
            "PATH" to "$prefix/bin:$prefix/bin/node_modules/.bin",
            "LD_LIBRARY_PATH" to "$prefix/lib",
            "TERM" to "xterm-256color",
            "LANG" to "en_US.UTF-8",
            "DSH_RG_PATH" to "$prefix/bin/rg",
        )
    }
}
