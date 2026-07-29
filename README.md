# Sultan's Game Mod Manager

[English](#english) | [中文](#chinese)

---

<a id="chinese"></a>

## 苏丹的游戏 Mod 管理器

为《苏丹的游戏》Android 版（il2cpp）提供官方 Windows Mod 兼容支持的**开源 Android 应用**。

### 概述

Windows 版《苏丹的游戏》有官方 Mod 支持，Android 版没有。本项目在 Android 上实现了与 Windows 版兼容的官方 Mod 加载机制。

### 工作原理

1. **Extract** — 从已安装游戏中提取 APK
2. **Sign** — 使用本机 Android KeyStore（RSA-4096，不可导出）对 base APK + loader split 重签
3. **Install** — 通过 Android PackageInstaller 单会话多 APK 安装
4. **Load** — 同签名 loader split 中的原生 `libmodloader.so` 在游戏启动时自动加载，注入 Mod 支持

**不需要 root。** 用户设备即可操作。

### 项目结构

```
├── android/                    # Android 应用
│   ├── bootstrap/              # Loader split (ContentProvider + libmodloader.so 载体)
│   ├── manager/                # Manager 应用
│   │   ├── app/                # 主应用 (Compose UI + patching)
│   │   └── core/               # 核心库
│   │       ├── apk/            # 只读 APK 检查
│   │       ├── game-bridge/    # 游戏通信桥
│   │       ├── loader-split/   # Split 工厂接口
│   │       ├── model/          # 数据模型
│   │       ├── steam-protocol/ # Steam CM 协议客户端
│   │       ├── storage/        # 本地存储抽象
│   │       └── workshop/       # 创意工坊访问层
├── native/                     # C++ 原生库
│   ├── include/modloader/      # 头文件
│   ├── src/                    # 实现
│   └── tests/                  # 单元测试
└── release/                    # 发布身份信息
```

### 构建要求

- **JDK 21** — Kotlin/JVM 编译
- **Android SDK** — API 35, NDK 27.0+
- **CMake 3.22+** + Ninja（原生库）
- **Gradle**（通过 wrapper）

#### 构建 Manager APK（debug）

```bash
cd android/manager
./gradlew :app:assembleDebug
```

#### 构建原生库

```bash
cmake -B native/build-android -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-21
cmake --build native/build-android
```

#### Release 构建

```bash
./gradlew :app:assembleRelease \
  -PmanagerCertificateSha256=<64-hex-chars> \
  -PmodloaderBinary=<path-to-libmodloader.so>
```

### 技术栈

- **UI**: Jetpack Compose + Miuix 主题
- **原生**: C++17 (ARM64), JNI, IL2CPP 运行时交互
- **构建**: Gradle (Kotlin DSL), CMake, Ninja
- **签名**: Android Keystore, APK Signature Scheme v1+v2
- **创意工坊**: Steam CM 协议, Protobuf

### 许可

本项目以 GNU General Public License v3.0 发布。详见 [LICENSE](LICENSE)。

本应用是独立的社区工具，不隶属、未经《苏丹的游戏》权利人、发行商或 Valve 认可。游戏名称、商标及内容权利归其各自权利人所有。

### 致谢

- [Dobby](https://github.com/jmpews/Dobby) — ARM64 动态二进制插桩框架
- [apksig](https://android.googlesource.com/platform/tools/apksig) — Android APK 签名库

---

<a id="english"></a>

## Sultan's Game Mod Manager

An **open-source Android application** that brings official Windows Mod compatibility to the Android version of *Sultan's Game* (il2cpp).

### Overview

*Sultan's Game* has official mod support on Windows, but not on Android. This project implements the official mod loading mechanism on Android, achieving compatibility with Windows mods.

### How It Works

1. **Extract** — Extract APKs from the installed game
2. **Sign** — Re-sign base APK + loader split using device-scoped Android KeyStore (RSA-4096, non-exportable)
3. **Install** — Single-session multi-APK installation via Android PackageInstaller
4. **Load** — The co-signed loader split's native `libmodloader.so` auto-loads at game startup, injecting mod support

**No root required.** Works on unmodified user devices.

### License

GNU General Public License v3.0. See [LICENSE](LICENSE).

This application is an independent community tool, not affiliated with or endorsed by the rights holders of *Sultan's Game*, its publishers, or Valve. Game names, trademarks, and content rights belong to their respective owners.

### Acknowledgments

- [Dobby](https://github.com/jmpews/Dobby) — ARM64 dynamic binary instrumentation framework
- [apksig](https://android.googlesource.com/platform/tools/apksig) — Android APK signing library
