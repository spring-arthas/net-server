# vision-bridge —— 让 Windows 版 DSH Desktop 支持图片分析

把本目录整体拷到 Windows 机器上，按下面步骤执行即可。只改动用户目录下的**配置文件**，
不需要改 DSH Desktop 程序本体，Mac / Windows 同一套方案。

## 原理（一句话）

DSH 的前端图片链路（粘贴、拖放、附件存储、历史画廊）本来就是全的，卡点只有一个：
**host 端有模态闸门**——只有当前模型路由声明了 `image` 输入模态才允许发图，而
DeepSeek 官方线路是纯文本的。本方案注册一条新路由 `deepseek-vision`：

```
粘贴图片 → 闸门放行（路由声明 image）→ 插件把图片直连视觉接口转述成文字
        → 文字替换图片 → 委派给 DeepSeek 回答（会话历史里仍存真实图片）
```

## 目录结构

```
ds-desktop-vision-bridge/
├── install-windows.ps1      # Windows 一键安装（幂等，可重复执行）
├── README.md
└── plugins/
    └── vision-bridge/
        ├── index.js         # 插件本体
        └── package.json
```

## Windows 安装

1. 把整个 `ds-desktop-vision-bridge` 文件夹拷到 Windows 机器（U 盘 / 局域网均可）。
2. 打开 PowerShell（无需管理员），进入该文件夹执行：

   ```powershell
   cd ds-desktop-vision-bridge
   .\install-windows.ps1
   ```

   脚本会：
   - 把插件复制到 `%USERPROFILE%\.dsh\profiles\desktop\plugins\vision-bridge\`
   - 在 `%USERPROFILE%\.dsh\profiles\desktop\cordis.patch.yml` 注册路由（已存在则跳过）
3. 配置视觉服务密钥（见下节）。
4. **完全退出并重新启动 DSH Desktop**。
5. 输入框左侧模型选择器 → 选 **「DeepSeek + 视觉桥接」** 分组下的模型
   （如 `DeepSeek-V4-Pro（可读图）`）→ 粘贴图片发送。

> 手动安装（不想跑脚本）：把 `plugins\vision-bridge` 复制到
> `%USERPROFILE%\.dsh\profiles\desktop\plugins\`，再在
> `%USERPROFILE%\.dsh\profiles\desktop\cordis.patch.yml` 里追加：

```yaml
- insert:
    - id: vision-bridge
      name: ./plugins/vision-bridge/index.js
      inject: [llm, attachments, credentials]
      config:
        route: deepseek-vision
        displayName: DeepSeek + 视觉桥接
        target: deepseek-official
        visionApiKeyEnv: OPENCODE_GO_API_KEY
        visionBaseURL: https://opencode.ai/zen/go/v1
        visionModel: kimi-k2.6
```

## 配置视觉服务密钥（必做）

转述用的视觉服务需要一个有效密钥，二选一：

- **编辑 `%USERPROFILE%\.dsh\.credentials.yaml`**，加一行：
  `OPENCODE_GO_API_KEY: <你的密钥>`
- 或设置同名环境变量后重启。

密钥也可以随安装脚本一步写入：`.\install-windows.ps1 -ApiKey <你的密钥>`。

## 视觉服务选哪个（重点）

转述接口是 **OpenAI 兼容**的，`visionBaseURL` / `visionModel` / `visionApiKeyEnv`
三个配置项决定用哪家。当前默认指向 opencode.ai（`https://opencode.ai/zen/go/v1`，
模型 `kimi-k2.6`）——如果报 **401 / CreditsError（余额不足）**，去
opencode.ai 工作台充值，或换成你手里有余额/有密钥的服务：

| 服务 | visionBaseURL | visionModel（示例） | 说明 |
|---|---|---|---|
| opencode.ai（默认） | https://opencode.ai/zen/go/v1 | kimi-k2.6 | 需充值，Mac 上已在用 |
| 硅基流动 SiliconFlow | https://api.siliconflow.cn/v1 | Qwen/Qwen2.5-VL-72B-Instruct | 便宜、国内直连 |
| 智谱 GLM | https://open.bigmodel.cn/api/paas/v4 | glm-4v-plus | 国内直连 |
| 月之暗面 Kimi | https://api.moonshot.cn/v1 | moonshot-v1-8k-vision-preview | 国内直连 |
| OpenAI | https://api.openai.com/v1 | gpt-4o-mini | 海外 |

换服务只需改 `cordis.patch.yml` 里对应三个配置（或重跑安装脚本传参）：
`.\install-windows.ps1 -VisionBaseURL https://api.siliconflow.cn/v1 -VisionModel Qwen/Qwen2.5-VL-72B-Instruct -VisionApiKeyEnv SILICONFLOW_API_KEY -ApiKey sk-xxxx`

## 可选：直接用原生视觉模型

不经过桥接也可以：`opencode-go` 分组下 `Kimi K2.6` / `Kimi K3` / `MiMo V2.5` /
`Qwen3.6 Plus` 等模型本身就支持图片输入，选它们就是无损识图（但同样需要该服务有余额）。

## 故障排查

| 现象 | 原因与处理 |
|---|---|
| 选不到「DeepSeek + 视觉桥接」 | 插件未加载：确认 `cordis.patch.yml` 已写入、插件文件在 `plugins\vision-bridge\`、已重启 App |
| 发送后出现「无法转述：401/403…」 | 视觉服务密钥无效或余额不足：检查 `.credentials.yaml` / 环境变量，或换服务 |
| 出现「未配置视觉服务密钥」 | `visionApiKeyEnv` 对应的密钥没配 |
| 出现「视觉接口请求失败」 | 网络不通或 `visionBaseURL` 写错 |

## 卸载

删除 `%USERPROFILE%\.dsh\profiles\desktop\plugins\vision-bridge\`，并去掉
`cordis.patch.yml` 里的 `vision-bridge` 条目，重启即可。
