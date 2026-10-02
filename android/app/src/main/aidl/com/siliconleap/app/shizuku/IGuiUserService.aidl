package com.siliconleap.app.shizuku;

// Shizuku UserService（shell uid 进程内执行，demo 官方模式；
// Shizuku.newProcess 是私有 API，公开路径 = bindUserService）
interface IGuiUserService {

    // 首行 = 退出码，其后 = stdout+stderr 合并输出
    String exec(String cmd);

    void exit();
}
