# Ixeris 1.7.10 — 离线事件轮询移植版

## 这是什么？

原版 Ixeris 是一个 Minecraft 客户端性能优化 mod，通过将 `glfwPollEvents()`（GLFW 事件轮询）移到单独的线程来提升移动鼠标时的 FPS。

**但原版只支持 Minecraft 1.20+（使用 LWJGL 3.x / GLFW）。** Minecraft 1.7.10 使用的是 LWJGL 2.x，架构完全不同，所以无法直接移植。

这个项目是从零重写的 **1.7.10 专用版本**，实现了相同的核心思路：将 `Display.processMessages()`（LWJGL 2.x 的事件轮询）从渲染线程移到单独的线程。

## 工作原理

```
原版 1.7.10:
  主线程: processMessages() → 游戏逻辑 → 渲染 → Display.update() → 循环
  ↑ processMessages() 会阻塞渲染线程，移动鼠标时 FPS 下降

Ixeris 1.7.10:
  主线程(轮询线程): processMessages() → 排空输入事件 → 睡眠 → 循环
  渲染线程: 游戏逻辑 → 渲染 → Display.update() → 循环
  ↑ processMessages() 不再阻塞渲染线程，移动鼠标时 FPS 更平滑
```

### 技术细节

1. **线程拆分**: 在 `Minecraft.run()` 开头，释放主线程的 GL 上下文，创建新的渲染线程来运行游戏循环
2. **GL 上下文转移**: `Display.releaseContext()` → `Display.makeCurrent()`
3. **输入同步**: 轮询线程排空 LWJGL 的 Mouse/Keyboard 事件队列到线程安全缓冲区，渲染线程从缓冲区读取
4. **ASM 字节码修改**: 通过 Forge 核心模组 (coremod) 修改 Mouse、Keyboard、Display 类的方法

## 安装方法

1. 确保你已安装 **Minecraft Forge 1.7.10**（推荐 10.13.4.1614 或更高版本）
2. 将 `ixeris-1.7.10.jar` 放入 `.minecraft/mods/` 目录
3. 启动 Minecraft
4. 日志中应该能看到 `[Ixeris]` 开头的信息

## 配置

配置文件位于 `config/ixeris.cfg`：

```properties
# 是否启用 mod
enabled = true

# 轮询间隔（微秒 µs），1000 µs = 1 ms
# 0 = 忙等（最丝滑但 CPU 占用最高）
# 推荐范围: 100-2000 µs，默认 500 µs
pollingIntervalUs = 500

# 旋转等待模式
# yield — (默认/推荐) 自旋时主动让出 CPU 给渲染线程，最丝滑
# park  — 始终用 parkNanos，省电但精度略低
# busy  — 纯忙等，最大精度但可能拖慢渲染（不推荐）
spinMode = yield
```

如果遇到问题，将 `enabled` 设为 `false` 即可禁用 mod。

## 注意事项

- 这是 **客户端 mod**，不需要安装在服务端
- 需要与 `@TransformerExclusions` 配合避免自引用问题
- 如果与其他修改 Mouse/Keyboard/Display 的核心模组冲突，可能需要调整
- GL 上下文转移在 Windows 上经过充分测试，在其他平台上可能不稳定

## 已知限制

- 轮询间隔越短越丝滑，但 CPU 占用越高（0µs 忙等 ≈ 一个核心满载）
- 使用 `LockSupport.parkNanos()` + 自旋等待实现微秒级精度，避免 `Thread.sleep()` 的 ~15ms 系统时钟限制
- 不支持 LWJGL 3.x（请使用原版 Ixeris）
- 未在所有模组包中测试兼容性

## 技术栈

- Java 8 (class version 52)
- Forge 1.7.10 (ForgeGradle 1.2 compatible)
- ASM 5.0.3 (随 Forge 提供)
- LWJGL 2.9.1 (随 Minecraft 1.7.10 提供)

## 原始项目

- 原版 Ixeris by decce: https://modrinth.com/mod/ixeris
- 原版支持 1.20+ (Fabric/Forge/NeoForge)
