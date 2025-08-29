# ✈️ Spring AI Alibaba 智能机票助手增强版

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-1.0.0-green)](https://spring.io/projects/spring-ai)
[![JDK](https://img.shields.io/badge/JDK-17+-orange)](https://adoptium.net)

> **示例源码**：[spring-ai-alibaba-examples](https://github.com/xxx/spring-ai-alibaba-examples)

本项目基于 **Spring AI Alibaba** 框架，实现一个可对话的智能机票助手，支持机票预订、退改签、问题解答等功能，并针对原示例的痛点做了以下增强：

| 🎯 原示例目标 | ✅ 本仓库改进 |
| --- | --- |
| 基于大模型理解自然语言需求 | ✅ 保持兼容 |
| 支持多轮对话 | 🔄 **持久化会话** |
| 遵守航空规范 | ✅ 引入规则引擎 |
| 可调用工具完成任务 | ✅ 支持函数调用 |

---

## 📑 目录
- [快速开始](#-快速开始)
- [功能亮点](#-功能亮点)
- [问题修复](#-问题修复)
- [技术栈](#-技术栈)
- [参与贡献](#-参与贡献)
- [许可证](#-许可证)

---

## 🚀 快速开始
1. 克隆仓库  
   ```bash
   git clone https://github.com/xxx/spring-ai-alibaba-examples.git
   cd spring-ai-alibaba-examples/ticket-assistant

