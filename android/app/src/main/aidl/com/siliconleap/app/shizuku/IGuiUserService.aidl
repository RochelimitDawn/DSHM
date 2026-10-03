package com.siliconleap.app.shizuku;

// Shizuku UserService（shell uid 进程内执行，demo 官方模式；
// Shizuku.newProcess 是私有 API，公开路径 = bindUserService）
interface IGuiUserService {

    // 首行 = 退出码，其后 = stdout+stderr 合并输出
    String exec(String cmd);

    // 在 shell uid 进程内创建真实虚拟屏（隐藏 flag 组合，LittleWhale 验证方案）
    int createDisplay(String name, int width, int height, int dpi);

    // 销毁 createDisplay 创建的虚拟屏
    void releaseDisplay(int displayId);

    void exit();
}
