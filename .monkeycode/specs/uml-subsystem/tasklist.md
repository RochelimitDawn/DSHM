# uml-subsystem 实施任务

- [x] 1. spec 文档（requirements/design/tasklist）
- [x] 2. runtime-builder：config/uml-base.config + build_uml.sh + build_umnetx.sh + build_umrootfs.sh
- [x] 3. guest 脚本：launcher/umarm-init + umarm-daemon.sh（rootfs 内置）
- [x] 4. AppSettings：UML 引擎常量与校验
- [x] 5. SubsystemManager：UML 路径/可用性/启动/停止/资产下载
- [x] 6. TermuxEnv：UML argv 分支（shell + assembly）
- [x] 7. jniLibs：libumarm-cmd.so wrapper 脚本
- [x] 8. CI workflow：UML 产物注入 jniLibs
- [x] 9. 精确验证：脚本语法检查、umarm-cmd 协议本地回环测试、Kotlin 源检查、现有工具
