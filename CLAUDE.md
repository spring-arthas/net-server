# Claude 研发助理配置
> 适用项目：Java Spring Boot
> 开发工具：IntelliJ IDEA / VSCode + Claude Code 插件 / Antigravity
> 当前已配置 Skill：场景1（PR描述）、场景2（接口文档）

---

## 场景1：PR 描述生成

当我描述了代码改动、或粘贴了 git diff 内容时，
按以下规则生成 PR 描述。

### 输入类型判断

| 类型 | 识别特征 | 处理方式 |
|------|----------|----------|
| 口语描述 | 自然语言，如"我改了XX，做了XX" | 从句子中提取文件名、改动类型、原因 |
| git diff | 包含 `diff --git`、`@@`、`+/-` 开头的行 | 从 diff 行解析文件名和改动内容 |
| 混合输入 | 既有 diff 又有口语补充说明 | 以口语说明为主，diff 作细节补充 |

**git diff 解析规则：**
- 从 `diff --git a/路径/文件名` 提取文件名，去掉路径只保留文件名
- `+` 开头的行 = 新增逻辑，从中推断"做了什么"
- `-` 开头的行 = 删除逻辑，从中推断"改了什么原有逻辑"
- 如果从 diff 无法推断改动原因，在输出末尾加 ⚠️ 提示补充

### 改动类型判断规则

| 标签 | 判断标准 | Java 场景示例 |
|------|----------|--------------|
| `[Feat]` | 原来没有此能力，本次新增 | 新增接口、新增业务逻辑、新增校验 |
| `[Fix]` | 原来行为是错的，本次修正 | 修复 NPE、修复逻辑漏洞、修复超时 |
| `[Refactor]` | 行为不变，只改代码结构 | 抽取方法、重命名、拆分 Service |
| `[Chore]` | 配置、依赖、构建相关 | 改 pom.xml、改 application.yml |
| `[Docs]` | 只改了注释或文档 | 补充 Javadoc、改 README |

### 输出模板（严格按此格式）

---
**标题：** [类型] 简短描述（不超过50字，只用一个动词）

## 📋 改动内容
（条目式，每条一句话，动词开头）

## 🎯 改动原因
（为什么改，解决了什么问题）

## 📁 涉及文件
（只写文件名，不写完整路径）

## ✅ 自测清单
- [ ] 本地启动通过（Spring Boot 正常运行）
- [ ] 相关接口/方法测试通过
- [ ] 无明显性能影响
---

### 语言规范

- 全程中文，技术术语保留英文（NPE、Redis、MQ、DTO）
- 标题只用一个动词：新增/修复/优化/移除/调整/重构
- Java 类名、方法名保留原始大小写
- 改动原因无法判断时，输出 ⚠️ 提示补充

### 示例A：口语输入

**输入：**
帮我写PR，改了PetServiceImpl的petChange方法，
加了升降级联动，之前更换宠物不触发升降级

**输出：**

---
**标题：** [Feat] PetServiceImpl.petChange 新增宠物更换后升降级联动

## 📋 改动内容
- `petChange()` 新增调用升降级计算服务
- 旧宠物更换后自动触发升降级计算
- 新宠物落库后自动执行升级操作

## 🎯 改动原因
原方法仅完成宠物替换，未联动升降级服务，状态无法自动同步

## 📁 涉及文件
- `PetServiceImpl.java`

## ✅ 自测清单
- [ ] 本地启动通过（Spring Boot 正常运行）
- [ ] 相关接口/方法测试通过
- [ ] 无明显性能影响
---

### 示例B：git diff 输入

**输入：**
```diff
diff --git a/src/main/java/com/example/OrderController.java b/src/...
@@ -32,6 +32,10 @@ public class OrderController {
     public Result createOrder(OrderDTO dto) {
+        if (dto.getStock() <= 0) {
+            return Result.fail("库存不足");
+        }
         orderService.create(dto);
         return Result.ok();
     }
}
```

**输出：**

---
**标题：** [Feat] OrderController.createOrder 新增下单前库存校验

## 📋 改动内容
- `createOrder()` 方法新增库存数量前置校验
- 库存不足时提前返回失败响应，阻断下单流程

## 🎯 改动原因
> ⚠️ 建议补充：此次加入库存校验的业务背景

## 📁 涉及文件
- `OrderController.java`

## ✅ 自测清单
- [ ] 本地启动通过（Spring Boot 正常运行）
- [ ] 接口联调通过
- [ ] 无明显性能影响
---

---

## 场景2：接口文档生成

当我描述接口功能、或粘贴 Controller/DTO 代码时，
帮我同时生成 JavaDoc 注释和 Markdown 接口文档两种格式。

### 输入类型判断

| 类型 | 识别特征 | 处理方式 |
|------|----------|----------|
| 口语描述 | 自然语言，如"有个XX接口，传XX，返回XX" | 推断补全字段，标注⚠️缺失项 |
| Controller代码 | 包含 @PostMapping/@GetMapping 等注解 | 从注解和方法签名提取信息 |
| DTO/VO代码 | 包含字段定义的 Java 类 | 逐字段生成说明表格 |
| 混合输入 | 代码+口语补充 | 以口语说明为主，代码作补充 |

### 推断补全规则

口语输入时，按以下规则自动补全：

**请求方式：**
- 含"查询/获取/列表" → GET
- 含"新增/创建/添加" → POST
- 含"修改/更新/编辑" → PUT
- 含"删除/移除" → DELETE
- 明确说了方法 → 直接使用

**响应结构（Spring Boot 标准）：**
若未说明，默认：
```json
{
  "code": 200,
  "message": "success",
  "data": {}
}
```

**字段类型推断：**
- XX的ID / XX编号 → Long
- 名称/标题/描述 → String
- 金额/价格 → BigDecimal
- 时间/日期 → LocalDateTime
- 是否XX / 状态 → Integer（0/1）
- 列表/集合 → List<Object>

推断不确定时标注 `⚠️ 请确认类型` 或 `⚠️ 请补充字段含义`

### 输出格式（严格按此顺序输出两个部分）

---
#### 📌 Part 1：JavaDoc 注释
（粘贴到 Controller 方法上方，供 Easy Yapi 插件解析推送）

```java
/**
 * 接口名称
 * 接口功能简述
 *
 * @param 参数名 参数说明
 * @return 返回说明
 */
```

#### 📌 Part 2：Markdown 接口文档

## 接口名称

**请求地址：** `请求方式 /路径`
**Content-Type：** `application/json`

### 请求参数

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| 字段名 | 类型 | 是/否 | 说明 |

### 响应参数

| 参数名 | 类型 | 说明 |
|--------|------|------|
| code | Integer | 状态码，200为成功 |
| message | String | 提示信息 |
| data | 类型 | 返回数据说明 |

### 响应示例

```json
{
  "code": 200,
  "message": "success",
  "data": 示例值
}
```
---

### 语言规范

- 接口名称用中文
- 字段名保留英文原始命名（驼峰）
- 路径用小写中划线，如 `/pet/change`
- 说明列写清楚业务含义，不写"该字段"这种废话

### 示例

**输入：**
有个宠物更换接口，POST请求，
需要传用户ID和新宠物ID，返回是否成功

**输出：**

---
#### 📌 Part 1：JavaDoc 注释

```java
/**
 * 宠物更换
 * 用户更换当前宠物，更换后自动触发升降级计算
 *
 * @param userId   用户ID
 * @param newPetId 新宠物ID
 * @return 是否更换成功
 */
```

#### 📌 Part 2：Markdown 接口文档

## 宠物更换

**请求地址：** `POST /pet/change`
**Content-Type：** `application/json`

### 请求参数

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| userId | Long | 是 | 用户ID |
| newPetId | Long | 是 | 新宠物ID |

### 响应参数

| 参数名 | 类型 | 说明 |
|--------|------|------|
| code | Integer | 状态码，200为成功 |
| message | String | 提示信息 |
| data | Boolean | true=更换成功，false=更换失败 |

### 响应示例

```json
{
  "code": 200,
  "message": "success",
  "data": true
}
```
---

---

## 项目基本信息

- 语言：Java 17+
- 框架：Spring Boot
- 构建：Maven
- 架构：Service / Controller / Mapper 三层
- 接口文档平台：Easy Yapi（IDEA 插件）→ YApi
- 代码规范：驼峰命名，统一响应结构 {code, message, data}
