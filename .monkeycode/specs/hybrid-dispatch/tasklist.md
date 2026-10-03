# hybrid-dispatch 实施任务

- [x] 1. spec 文档（requirements/design/tasklist）
- [x] 2. jniLibs：libdsh-dispatch.so 调度器 wrapper（分类 + 路由 + 粘性 + 降级）
- [x] 3. AppSettings：SUBSYSTEM_ENGINE_HYBRID 新默认
- [x] 4. TermuxEnv：dispatch 分支 + prootCmdLine + serverEnv 注入
- [x] 5. SubsystemManager：预启动 + 空闲回收 + 粘性标记
- [x] 6. RuntimeScreen：引擎选择新增自动调度选项
- [x] 7. 精确验证：分类路由回环测试（proot/UML/降级三路径）、语法、Kotlin 检查
- [x] 8. 文档 + 提交推送
