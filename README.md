# DsLR

**DesktopLitematicRender**

一个用于投影文件本地渲染图生成的个人项目。

目前项目仍在开发和整理中，随时可能调整。

## Requirements

目前开发与运行环境主要涉及：

- Node.js 20+
- Java
- Minecraft
- Fabric Loader

Minecraft、Fabric Loader 及相关组件之兼容版本，以具体项目实现和对应说明为准。

## Getting Started

克隆项目：

```bash
git clone <repository-url>
cd <repository-directory>
```

安装 Node.js 依赖：

```bash
npm install
```

查看当前可用的 npm 命令：

```bash
npm run
```

运行测试：

```bash
npm test
```

Java Desktop Controller 与 Fabric Worker 位于各自目录中，并拥有独立的源码与构建流程。

Minecraft 安装目录等请根据本机情况进行配置。

## How it works

DsLR 的基本工作流程：

Web UI 提供本地操作入口，Node.js 负责控制与任务逻辑，Java Desktop Controller 和 Fabric Worker 则负责与桌面环境和 Minecraft 交互环节

## Building

Java 与 Fabric 相关组件提供对应的本地构建方式。

以下内容属于构建产物或运行时数据，不应提交到 Git 仓库：

```text
build/
.build-tmp.*/
runtime/
```

## Status

这是一个基于突发奇想和闲得无聊的个人项目。

所有功能、接口仍可能变化，不同版本之间不保证100%完全兼容。

遇到可以稳定复现的问题，欢迎提交 Issue。提供以下信息通常有利于定位问题：

- 操作系统
- Java 版本
- Node.js 版本
- Minecraft 版本
- Fabric Loader 版本
- 问题复现步骤
- 相关log日志

上传日志前，请自行检查其中是否包含个人路径、用户名等不希望进行公开的信息。

## Development

This project was developed through an AI-assisted workflow, with manual testing, integration, and maintenance.

本项目使用AI辅助开发，同时由项目作者进行实际测试、整合、调试与维护。

## Contributing

欢迎 Fork、修改和提交 Pull Request。

如果提交代码，希望：

- 理解自己修改的内容；
- 对修改后的功能进行基本测试；
- 不提交本地构建产物和运行时数据；
- 请勿提交密码、Token、私钥或其他隐私相关数据。

使用AI辅助完成的提交无需额外标记，但提交者应能够理解并检查自己提交的代码。

## License

This project is licensed under the MIT License.

Copyright (c) 2026 iLiLni

See [LICENSE](./LICENSE) for details.
