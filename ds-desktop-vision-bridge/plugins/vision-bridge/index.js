/**
 * vision-bridge：给一个纯文本模型套上"眼睛"，让它也能接收图片。
 *
 * ## 为什么需要它
 *
 * DSH 的图片链路本身是完整的：输入框粘贴／整页拖放（ui-conversation）→ 内容寻址持久化
 * （attachments）→ 提供方适配器序列化 → 历史画廊。真正的闸门有两道，都看**当前路由声明的
 * 输入模态**（`LlmModelInfo.inputModalities`）：
 *
 *   1. `dsh-host-apiproxy` 的 `prompt` RPC：消息带图而当前模型未声明 image 时，直接拒收
 *      （`attachment-error` / `MODEL_DOES_NOT_SUPPORT_IMAGES`），图片根本到不了模型；
 *      `selectModel` 同理，含图会话不允许切到纯文本模型。
 *   2. `dsh-tool-fs` 的 `read_image`：同一条模态闸门。
 *
 * 而 `dsh-llm-deepseek` 这条 chat-completions 线路是纯文本的（`inputModalities: ['text']`，
 * 遇到 ImageBlock 抛 `UNSUPPORTED_CONTENT`）。所以在 deepseek-official 上，图片"发不出去"。
 *
 * ## 做法
 *
 * 本插件注册一条**新的提供方路由**（默认 `deepseek-vision`），它：
 *   - 把目标路由（默认 `deepseek-official`）的模型目录整份对外声明，并额外声明 `image` 输入
 *     模态 —— 于是上面两道闸门放行，粘贴／拖放／`read_image` 全部可用；
 *   - `stream()` 时先读取附件字节，把每张图片直连一个 OpenAI 兼容的视觉接口转述成文字
 *     （不经过 harness 的 LLM 注册表，因此不依赖任何 `llm-pi-ai` 设置），
 *     用文字块替换 ImageBlock，再把改写后的请求委派给目标路由。
 *
 * 会话日志与前端历史保存的仍是真实图片（只改写"发给提供方的那一份"）。转述结果按
 * attachmentId 缓存，同一张图在多轮 agent step 里只转述一次，请求前缀保持稳定，
 * 不会反复失效 KV 缓存。
 *
 * @module dsh-plugin-vision-bridge
 */

import z from '@deepseek-ai/schemastery'
import { credentialRef } from '@deepseek-ai/dsh-credentials'
import { LlmAdapter, contentHasImage, freezeMessage } from '@deepseek-ai/dsh-llm'

/** Cordis 插件名，出现在 Loader 诊断里。 */
export const name = 'vision-bridge'

/**
 * 依赖的 seam：llm（注册桥接路由并委派）、attachments（读取持久化图片字节）、
 * credentials（解析视觉服务密钥，兼容 .credentials.yaml 与环境变量）。
 */
export const inject = ['llm', 'attachments', 'credentials']

const DEFAULT_PROMPT = [
	'请把这张图片的全部可见信息完整、客观地转述成文字，供一个无法看图的模型使用。要求：',
	'1. 逐字转录图中所有文字（代码、报错、日志、命令、表格数据保持原样，保留行号与缩进）；',
	'2. 说明画面结构与布局（区域划分、层级、控件、箭头、连线、选中状态）；',
	'3. 描述关键颜色、图标、状态标记；',
	'4. 如是图表，给出坐标轴、图例与可读数值；',
	'5. 只描述看得见的内容：不要推测意图，不要给建议，不要输出与图片无关的话。',
].join('\n')

export const Config = z.object({
	/** 本插件对外提供的新路由 id（出现在模型选择器里）。 */
	route: z.string().default('deepseek-vision'),
	/** 该路由在选择器里的分组名。 */
	displayName: z.string().default('DeepSeek + 视觉桥接'),
	/** 真正执行对话的目标路由（纯文本亦可）。 */
	target: z.string().default('deepseek-official'),
	/** 模型名后缀，便于在选择器里区分同名模型。 */
	modelSuffix: z.string().default('（可读图）'),
	/** 视觉服务密钥的 credential ref（.credentials.yaml 键名或环境变量名）。 */
	visionApiKeyEnv: z.string().role('credential-ref').default('OPENCODE_GO_API_KEY'),
	/** OpenAI 兼容视觉接口的 base URL（末尾不要带 /chat/completions）。 */
	visionBaseURL: z.string().default('https://opencode.ai/zen/go/v1'),
	/** 视觉模型 id。 */
	visionModel: z.string().default('kimi-k2.6'),
	/** 一次转述的输出上限（token）。 */
	visionMaxTokens: z.number().step(1).min(1).default(4096),
	/** 单张图片转述的超时（毫秒）。 */
	visionTimeoutMs: z.number().min(1).default(180000),
	/** 单个请求最多转述多少张图片，超出的只留占位说明。 */
	maxImages: z.number().step(1).min(1).default(8),
	/** 转述缓存条目上限（按 attachmentId）。 */
	cacheSize: z.number().step(1).min(1).default(256),
	/** 转述指令。 */
	prompt: z.string().default(DEFAULT_PROMPT),
})

/** 按出现顺序收集去重后的图片引用（含 tool-result 内嵌的图片）。 */
function collectImages(messages) {
	const ordered = []
	const seen = new Set()
	const walk = (blocks) => {
		for (const block of blocks) {
			if (block.type === 'image') {
				const key = block.attachment.attachmentId
				if (seen.has(key)) continue
				seen.add(key)
				ordered.push(block.attachment)
			} else if (block.type === 'tool-result') walk(block.content)
		}
	}
	for (const message of messages) walk(message.content)
	return ordered
}

/** 图片的人类可读标注，作为替换文本的表头。 */
function imageLabel(ref, index, route) {
	const size = ref.width === undefined || ref.height === undefined ? '' : ` ${ref.width}x${ref.height}`
	const named = ref.name === undefined ? '' : ` "${ref.name}"`
	return `[图片 #${index + 1}${named} · ${ref.mediaType}${size} · 由视觉模型 ${route} 转述]`
}

/** 用转述文本替换全部 ImageBlock；tool-result 内的图片一并递归替换。 */
function rewriteBlocks(blocks, texts) {
	let changed = false
	const rewritten = []
	for (const block of blocks) {
		if (block.type === 'image') {
			rewritten.push({ type: 'text', text: texts.get(block.attachment.attachmentId) ?? '' })
			changed = true
			continue
		}
		if (block.type === 'tool-result' && contentHasImage(block.content)) {
			rewritten.push({ ...block, content: rewriteBlocks(block.content, texts) })
			changed = true
			continue
		}
		rewritten.push(block)
	}
	return changed ? rewritten : blocks
}

/**
 * 改写整个消息列表：图片换成文字，并把本路由产出的 assistant 消息重新署名为目标路由，
 * 这样目标适配器仍然认领自己的 replay state（thinking 回放不会被裁掉）。
 */
function rewriteMessages(messages, texts, route, target) {
	return messages.map((message) => {
		const source = message.source
		const restamp =
			message.role === 'assistant' && source?.kind === 'model' && source.provider === route
				? { ...source, provider: target }
				: undefined
		const hasImage = contentHasImage(message.content)
		if (restamp === undefined && !hasImage) return message
		return freezeMessage({
			...message,
			...(hasImage ? { content: rewriteBlocks(message.content, texts) } : {}),
			...(restamp === undefined ? {} : { source: restamp }),
		})
	})
}

/** 有界的转述缓存：同一张图在整个进程生命周期内只转述一次。 */
function makeCache(limit) {
	const entries = new Map()
	return {
		get(key) {
			return entries.get(key)
		},
		set(key, value) {
			if (entries.has(key)) entries.delete(key)
			entries.set(key, value)
			while (entries.size > limit) {
				const oldest = entries.keys().next()
				if (oldest.done === true) break
				entries.delete(oldest.value)
			}
		},
	}
}

/** 组合调用方取消与本次转述的超时预算。 */
function describeSignal(signal, timeoutMs) {
	const budget = AbortSignal.timeout(timeoutMs)
	return signal === undefined ? budget : AbortSignal.any([signal, budget])
}

/** 把一条 ModelModality 列表补上 image（去重、保持顺序）。 */
function withImage(modalities) {
	const list = modalities === undefined || modalities.length === 0 ? ['text'] : [...modalities]
	return list.includes('image') ? list : [...list, 'image']
}

/** 委派型适配器：对外声明可读图，对内把图片转述成文字后交给目标路由。 */
class VisionBridgeAdapter extends LlmAdapter {
	constructor(ctx, config) {
		super()
		this.ctx = ctx
		this.config = config
		this.cache = makeCache(config.cacheSize)
	}

	get visionRoute() {
		return `${this.config.visionBaseURL} ${this.config.visionModel}`
	}

	providerInfo(provider) {
		return { id: provider, name: this.config.displayName }
	}

	async listModels(provider) {
		const models = await this.ctx.llm.listModels(this.config.target)
		return models.map((model) => ({
			provider,
			id: model.id,
			name: `${model.name}${this.config.modelSuffix}`,
			description: `${model.description ?? model.name}｜图片经 ${this.config.visionModel} 转述后交给 ${this.config.target}`,
			inputModalities: withImage(model.inputModalities),
		}))
	}

	async resolveModel(provider, model, signal) {
		const info = await this.ctx.llm.resolveModelInfo(this.config.target, model, signal)
		return {
			...info,
			provider,
			id: model,
			name: `${info.name}${this.config.modelSuffix}`,
			inputModalities: withImage(info.inputModalities),
		}
	}

	/** 解析视觉服务密钥：环境变量优先，其次 .credentials.yaml。 */
	async resolveVisionKey() {
		const ref = credentialRef(this.config.visionApiKeyEnv)
		const resolved = await this.ctx.credentials.resolve(ref)
		if (resolved === undefined || resolved.value.length === 0) {
			throw new Error(
				`未配置视觉服务密钥（credential ref "${ref}"）：请在 $DSH_HOME/.credentials.yaml 中写入该键，或设置同名环境变量`,
			)
		}
		return resolved.value
	}

	/** 直连 OpenAI 兼容视觉接口，把一张图片转述成纯文本。 */
	async describe(ref, options) {
		const apiKey = await this.resolveVisionKey()
		const stored = await this.ctx.attachments.readImage(ref, describeSignal(options.signal, this.config.visionTimeoutMs))
		const dataUri = `data:${stored.ref.mediaType};base64,${Buffer.from(stored.data).toString('base64')}`
		const base = this.config.visionBaseURL.replace(/\/+$/, '')
		const body = {
			model: this.config.visionModel,
			max_tokens: this.config.visionMaxTokens,
			messages: [
				{
					role: 'user',
					content: [
						{ type: 'text', text: this.config.prompt },
						{ type: 'image_url', image_url: { url: dataUri } },
					],
				},
			],
		}

		let response
		try {
			response = await fetch(`${base}/chat/completions`, {
				method: 'POST',
				headers: {
					'content-type': 'application/json',
					authorization: `Bearer ${apiKey}`,
				},
				body: JSON.stringify(body),
				signal: describeSignal(options.signal, this.config.visionTimeoutMs),
			})
		} catch (error) {
			throw new Error(`视觉接口请求失败：${error instanceof Error ? error.message : String(error)}`)
		}

		let payload
		try {
			payload = await response.json()
		} catch {
			payload = undefined
		}
		if (!response.ok) {
			const message = payload?.error?.message ?? payload?.message ?? `HTTP ${response.status}`
			throw new Error(`${response.status}: ${message}`)
		}

		const content = payload?.choices?.[0]?.message?.content
		let text = ''
		if (typeof content === 'string') {
			text = content
		} else if (Array.isArray(content)) {
			text = content
				.filter((part) => part?.type === 'text' && typeof part.text === 'string')
				.map((part) => part.text)
				.join('')
		}
		text = text.trim()
		if (text.length === 0) throw new Error('视觉模型没有返回任何文字')
		return text
	}

	/** 逐张拿到转述文本；单张失败退化为显式说明，不让整个请求崩掉。 */
	async transcribe(images, options) {
		const texts = new Map()
		for (const [index, ref] of images.entries()) {
			if (index >= this.config.maxImages) {
				texts.set(
					ref.attachmentId,
					`[图片 #${index + 1} 未转述：本次请求的图片数超过 vision-bridge 的 maxImages=${this.config.maxImages}]`,
				)
				continue
			}
			const header = imageLabel(ref, index, this.visionRoute)
			const cached = this.cache.get(ref.attachmentId)
			if (cached !== undefined) {
				texts.set(ref.attachmentId, `${header}\n${cached}`)
				continue
			}
			try {
				const description = await this.describe(ref, options)
				this.cache.set(ref.attachmentId, description)
				texts.set(ref.attachmentId, `${header}\n${description}`)
				this.ctx.logger.info(
					`vision-bridge: ${this.config.visionModel} 已转述 ${ref.attachmentId.slice(0, 14)}…（${description.length} 字）`,
				)
			} catch (error) {
				const reason = error instanceof Error ? error.message : String(error)
				this.ctx.logger.warn(`vision-bridge: 转述失败（${this.visionRoute}）：${reason}`)
				texts.set(
					ref.attachmentId,
					`[图片 #${index + 1} 无法转述：${reason}。当前模型不支持图片输入，vision-bridge 的视觉服务不可用，请把这一情况直接告知用户，让其检查 vision-bridge 配置（密钥、余额、接口地址）或改用原生支持图片的模型；不要凭猜测描述图片内容。]`,
				)
			}
		}
		return texts
	}

	async *stream(options) {
		const images = collectImages(options.messages)
		const texts = images.length === 0 ? new Map() : await this.transcribe(images, options)
		if (images.length > 0) {
			this.ctx.logger.info(
				`vision-bridge: 本次请求含 ${images.length} 张图片，转述后委派给 ${this.config.target}/${options.model}`,
			)
		}
		yield* this.ctx.llm.stream({
			...options,
			provider: this.config.target,
			messages: rewriteMessages(options.messages, texts, this.config.route, this.config.target),
		})
	}
}

/**
 * 注册桥接路由。
 * @param ctx - 拥有该注册的插件上下文。
 * @param config - 已校验的路由、视觉服务与转述策略。
 */
export function apply(ctx, config) {
	if (config.route === config.target) {
		throw new Error('vision-bridge: route 与 target 不能相同，否则会自我委派')
	}
	ctx.llm.registerAdapter([config.route], new VisionBridgeAdapter(ctx, config))
	ctx.logger.info(
		`vision-bridge: 已注册路由 "${config.route}" → ${config.target}，视觉服务 ${config.visionBaseURL} ${config.visionModel}`,
	)
}
