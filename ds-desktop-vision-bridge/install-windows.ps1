<#
.SYNOPSIS
为 Windows 版 DSH Desktop 安装 vision-bridge：让 DeepSeek 等纯文本模型也能接收图片。

.DESCRIPTION
把 plugins/vision-bridge 复制到 %USERPROFILE%\.dsh\profiles\desktop\plugins\，
并在该 profile 的 cordis.patch.yml 里注册 "deepseek-vision" 路由。
幂等：已存在 vision-bridge 行时不会重复写入。

.PARAMETER ProfileDir
DSH Desktop 的 desktop profile 目录，默认 %USERPROFILE%\.dsh\profiles\desktop。

.PARAMETER Route
对外路由 id，默认 deepseek-vision（模型选择器里会出现）。

.PARAMETER DisplayName
选择器里显示的分组名。

.PARAMETER Target
真正执行对话的模型路由（纯文本即可），默认 deepseek-official。

.PARAMETER VisionApiKeyEnv
视觉服务密钥的 credential ref（.credentials.yaml 键名或环境变量名）。

.PARAMETER VisionBaseURL
OpenAI 兼容视觉接口 base URL（不要带 /chat/completions）。

.PARAMETER VisionModel
视觉模型 id。

.PARAMETER ApiKey
可选：直接把密钥写进 %USERPROFILE%\.dsh\.credentials.yaml（键名 = VisionApiKeyEnv）。

.EXAMPLE
.\install-windows.ps1

.EXAMPLE
.\install-windows.ps1 -VisionBaseURL https://api.siliconflow.cn/v1 -VisionModel Qwen/Qwen2.5-VL-72B-Instruct -VisionApiKeyEnv SILICONFLOW_API_KEY -ApiKey sk-xxxx
#>
param(
    [string]$ProfileDir = "$env:USERPROFILE\.dsh\profiles\desktop",
    [string]$Route = "deepseek-vision",
    [string]$DisplayName = "DeepSeek + 视觉桥接",
    [string]$Target = "deepseek-official",
    [string]$VisionApiKeyEnv = "OPENCODE_GO_API_KEY",
    [string]$VisionBaseURL = "https://opencode.ai/zen/go/v1",
    [string]$VisionModel = "kimi-k2.6",
    [string]$ApiKey = ""
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path -LiteralPath $ProfileDir)) {
    Write-Error "找不到 DSH Desktop profile 目录：$ProfileDir`n请确认 Windows 版 DSH Desktop 已安装并至少启动过一次。"
    exit 1
}

Write-Host "== 1/3 复制插件文件 ==" -ForegroundColor Cyan
$pluginDir = Join-Path $ProfileDir "plugins\vision-bridge"
New-Item -ItemType Directory -Force -Path $pluginDir | Out-Null
$src = Join-Path $PSScriptRoot "plugins\vision-bridge"
Copy-Item -Force -LiteralPath (Join-Path $src "index.js") -Destination $pluginDir
Copy-Item -Force -LiteralPath (Join-Path $src "package.json") -Destination $pluginDir
Write-Host "  已复制到 $pluginDir"

Write-Host "== 2/3 注册路由（cordis.patch.yml） ==" -ForegroundColor Cyan
$patchPath = Join-Path $ProfileDir "cordis.patch.yml"
$block = @"
- insert:
    - id: vision-bridge
      name: ./plugins/vision-bridge/index.js
      inject: [llm, attachments, credentials]
      config:
        route: $Route
        displayName: "$DisplayName"
        target: $Target
        visionApiKeyEnv: $VisionApiKeyEnv
        visionBaseURL: "$VisionBaseURL"
        visionModel: $VisionModel
"@

$content = ""
if (Test-Path -LiteralPath $patchPath) { $content = Get-Content -Raw -LiteralPath $patchPath }
if ($content -match '(?m)^\s*- id: vision-bridge\b') {
    Write-Host "  cordis.patch.yml 已包含 vision-bridge，跳过写入。" -ForegroundColor Yellow
} elseif ([string]::IsNullOrWhiteSpace($content) -or $content.Trim() -eq "[]") {
    Set-Content -LiteralPath $patchPath -Value $block -Encoding UTF8
    Write-Host "  已写入 $patchPath（空模板替换）。"
} else {
    $merged = $content.TrimEnd() + "`r`n" + $block + "`r`n"
    Set-Content -LiteralPath $patchPath -Value $merged -Encoding UTF8
    Write-Host "  已追加 vision-bridge 到 $patchPath。"
}

Write-Host "== 3/3 配置视觉服务密钥 ==" -ForegroundColor Cyan
$homeDir = Split-Path $ProfileDir -Parent   # %USERPROFILE%\.dsh
$credPath = Join-Path $homeDir ".credentials.yaml"
if ($ApiKey) {
    $line = "$VisionApiKeyEnv: $ApiKey"
    if (Test-Path -LiteralPath $credPath) {
        $cred = Get-Content -Raw -LiteralPath $credPath
        if ($cred -match "(?m)^$([regex]::Escape($VisionApiKeyEnv)):") {
            Write-Host "  $credPath 已存在 $VisionApiKeyEnv，未覆盖（如需更新请手动编辑）。" -ForegroundColor Yellow
        } else {
            Add-Content -LiteralPath $credPath -Value $line -Encoding UTF8
            Write-Host "  已把 $VisionApiKeyEnv 追加到 $credPath。"
        }
    } else {
        Set-Content -LiteralPath $credPath -Value $line -Encoding UTF8
        Write-Host "  已创建 $credPath 并写入 $VisionApiKeyEnv。"
    }
} else {
    Write-Host "  未提供 -ApiKey。请自行配置密钥（二选一）：" -ForegroundColor Yellow
    Write-Host "    a) 编辑 $credPath，加入一行：$VisionApiKeyEnv: <你的密钥>"
    Write-Host "    b) 系统环境变量（setx $VisionApiKeyEnv <你的密钥> 后重启 DSH Desktop）"
}

Write-Host ""
Write-Host "安装完成！接下来：" -ForegroundColor Green
Write-Host "  1. 完全退出并重新启动 DSH Desktop"
Write-Host "  2. 输入框左侧的模型选择器里选「$DisplayName」分组下的模型（如 DeepSeek-V4-Pro（可读图））"
Write-Host "  3. 直接粘贴 / 拖入图片发送即可。"
Write-Host ""
Write-Host "提示：视觉服务当前指向 $VisionBaseURL / $VisionModel，若报 401（余额/密钥）请更换为"
Write-Host "有余额的 OpenAI 兼容视觉接口，或给该服务充值。详见随包 README.md。"
