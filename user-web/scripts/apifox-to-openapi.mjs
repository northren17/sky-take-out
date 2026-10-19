/**
 * Apifox 导出 JSON -> OpenAPI 3.0 转换器
 *
 * 用途：`src/types/openapi.d.ts` 由 openapi-typescript 生成，而 openapi-typescript
 * 只吃 OpenAPI/Swagger 文档。本项目手上只有 Apifox 导出的 JSON，所以这里做一次
 * **纯机械的格式重组**，不改写、不补全任何业务信息。
 *
 * 用法：
 *   node scripts/apifox-to-openapi.mjs            # 用默认的两份 day01 文档
 *   node scripts/apifox-to-openapi.mjs a.json b.json
 *
 * 已知的文档缺陷（如实透传到产物，不做美化）：
 *  1. 所有 query 参数都没有 `type` 字段，只有 `example`/`desc`。这里按 `example`
 *     是否纯数字推断 integer/number，推断不出来的一律 string，并在 description 里
 *     标注「[文档未给出类型]」。
 *  2. 所有路径参数（req_params）的 `required`/`type` 均为空，一律按 string + 必填处理，
 *     并标注「[文档未给出类型]」。
 *  3. `required` 的原始值是数字 1/0（不是布尔），这里按 `1` 视为必填。
 *  4. 部分接口的响应 `data` 只有 `{}`（无 properties），会产出空的 object schema。
 *  5. 分页类响应的 `msg` 类型在文档里是字符串 `"null"`，会被归一化成 `type: 'string'`
 *     并在 description 标注原文，避免生成非法 JSON Schema。
 */
import { readFileSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const projectRoot = resolve(here, '..')

const DEFAULT_SOURCES = [
  'D:/资料/苍穹外卖/1、黑马程序员Java项目《苍穹外卖》企业级开发实战/资料/外卖后端资料/day01/项目接口/苍穹外卖-用户端接口.json',
  'D:/资料/苍穹外卖/1、黑马程序员Java项目《苍穹外卖》企业级开发实战/资料/外卖后端资料/day01/项目接口/苍穹外卖-管理端接口.json',
]

const sources = process.argv.slice(2)
const inputPaths = sources.length > 0 ? sources : DEFAULT_SOURCES

/** 保守的 JSON Schema 清洗：只处理会产出非法 schema 的情况 */
function parseSchema(raw) {
  if (raw === null || raw === undefined) return null
  if (typeof raw === 'object') return raw
  const text = String(raw).trim()
  if (!text) return null
  try {
    return JSON.parse(text)
  } catch {
    // 例如 /admin/report/export 的 res_body 就是字符串 "OK"
    return { __raw: text }
  }
}

function normalizeType(t) {
  if (Array.isArray(t)) {
    const list = t.filter((x) => typeof x === 'string' && x !== 'null')
    if (list.length === 0) return { type: 'string', note: '原文类型为 null' }
    if (list.length === 1) return { type: list[0] }
    return { type: list }
  }
  if (t === 'null') return { type: 'string', note: '原文类型为 null' }
  return { type: t }
}

/** 递归清洗 JSON Schema，产出合法 OpenAPI 3.0 schema */
function cleanSchema(schema, depth = 0) {
  if (!schema || typeof schema !== 'object') return { type: 'object' }
  if (depth > 30) return { type: 'object' }

  if (schema.__raw !== undefined) {
    return { type: 'string', description: `原始响应体不是 JSON Schema，原文：${schema.__raw}` }
  }

  const out = {}
  const { type, note } = normalizeType(schema.type)
  if (type) out.type = type
  if (schema.format) out.format = schema.format
  if (schema.enum) out.enum = schema.enum

  let description = typeof schema.description === 'string' ? schema.description : undefined
  if (note) description = description ? `${description}（${note}）` : note
  if (schema.title && !description) description = undefined
  if (description) out.description = description

  if (out.type === 'array' || (Array.isArray(out.type) && out.type.includes('array'))) {
    out.items = cleanSchema(schema.items, depth + 1)
  }

  if (schema.properties && typeof schema.properties === 'object') {
    const props = {}
    for (const [key, value] of Object.entries(schema.properties)) {
      props[key] = cleanSchema(value, depth + 1)
    }
    out.type = Array.isArray(out.type) ? out.type : 'object'
    out.properties = props
    if (Array.isArray(schema.required) && schema.required.length > 0) {
      out.required = schema.required.filter((k) => k in props)
    }
    if (Object.keys(props).length === 0) {
      // data 只有 {} 的情况：如实产出空对象
      out.properties = {}
    }
  }

  if (!out.type && !out.properties) out.type = 'object'
  return out
}

/** 路径 {id} -> OpenAPI 的 {id}（原样保留） */
function toOpenApiPath(path) {
  return path.startsWith('/') ? path : `/${path}`
}

function methodOf(api) {
  return String(api.method || 'GET').toLowerCase()
}

/** query 参数：文档没有 type，按 example 推断并标注 */
function buildQueryParams(api) {
  const list = Array.isArray(api.req_query) ? api.req_query : []
  const params = []
  for (const q of list) {
    if (!q || !q.name) continue
    const example = q.example === undefined || q.example === null ? '' : String(q.example)
    let type = 'string'
    let format
    if (example !== '') {
      if (/^-?\d+$/.test(example)) {
        type = 'integer'
        format = 'int32'
      } else if (/^-?\d+\.\d+$/.test(example)) {
        type = 'number'
      } else if (example === 'true' || example === 'false') {
        type = 'boolean'
      }
    }
    const descParts = []
    if (q.desc) descParts.push(String(q.desc))
    descParts.push(`example=${example === '' ? '(空)' : example}`)
    descParts.push('文档未给出类型')
    params.push({
      name: q.name,
      in: 'query',
      required: String(q.required) === '1',
      schema: format ? { type, format } : { type },
      description: descParts.join('；'),
    })
  }
  return params
}

/** 路径参数：文档 required/type 全为空，一律按 string + 必填，并标注 */
function buildPathParams(api) {
  const list = Array.isArray(api.req_params) ? api.req_params : []
  const params = []
  const seen = new Set()
  // 先按 path 里的占位符建，保证每个占位符都有定义
  const placeholders = [...String(api.path || '').matchAll(/\{([^}]+)\}/g)].map((m) => m[1])
  for (const name of placeholders) {
    const meta = list.find((p) => p && p.name === name) || {}
    seen.add(name)
    const descParts = []
    if (meta.desc) descParts.push(String(meta.desc))
    if (meta.example) descParts.push(`example=${meta.example}`)
    descParts.push('文档未标注必填性与类型')
    params.push({
      name,
      in: 'path',
      required: true,
      schema: { type: 'string' },
      description: descParts.join('；'),
    })
  }
  for (const p of list) {
    if (!p || !p.name || seen.has(p.name)) continue
    params.push({
      name: p.name,
      in: 'path',
      required: true,
      schema: { type: 'string' },
      description: `${p.desc || ''}${p.desc ? '；' : ''}文档未标注必填性与类型`,
    })
  }
  return params
}

function buildRequestBody(api) {
  const type = String(api.req_body_type || '')
  if (type === 'form') {
    const form = Array.isArray(api.req_body_form) ? api.req_body_form : []
    const props = {}
    const required = []
    for (const f of form) {
      if (!f || !f.name) continue
      const isFile = String(f.type) === 'file'
      props[f.name] = isFile
        ? { type: 'string', format: 'binary', description: f.desc || undefined }
        : { type: 'string', description: f.desc || undefined }
      if (String(f.required) === '1') required.push(f.name)
    }
    const schema = { type: 'object', properties: props }
    if (required.length > 0) schema.required = required
    return { required: true, content: { 'multipart/form-data': { schema } } }
  }

  const schema = parseSchema(api.req_body_other)
  if (!schema) return undefined
  const cleaned = cleanSchema(schema)
  return { required: true, content: { 'application/json': { schema: cleaned } } }
}

function buildResponses(api) {
  const schema = parseSchema(api.res_body)
  const cleaned = schema ? cleanSchema(schema) : { type: 'object' }
  return {
    200: {
      description: api.title ? String(api.title) : '成功',
      content: { 'application/json': { schema: cleaned } },
    },
  }
}

function buildTagName(groupName, docLabel) {
  return `${docLabel} · ${groupName}`
}

function main() {
  const paths = {}
  const tags = []
  const stats = { docs: 0, apis: 0, emptyData: [] }

  for (const inputPath of inputPaths) {
    const docLabel = /用户端/.test(inputPath) ? 'C端' : /管理端/.test(inputPath) ? '管理端' : '文档'
    const raw = readFileSync(resolve(inputPath), 'utf8')
    const groups = JSON.parse(raw)
    stats.docs += 1

    for (const group of groups) {
      const tag = buildTagName(group.name ?? '未命名分组', docLabel)
      if (!tags.some((t) => t.name === tag)) {
        tags.push({ name: tag, description: `${docLabel} · ${group.name ?? ''}（${group.desc ?? ''}）` })
      }
      for (const api of group.list ?? []) {
        stats.apis += 1
        const method = methodOf(api)
        const path = toOpenApiPath(api.path)
        if (!paths[path]) paths[path] = {}
        const parameters = [...buildQueryParams(api), ...buildPathParams(api)]
        const operation = {
          tags: [tag],
          summary: api.title || api.path,
          operationId: `${method}_${path.replace(/[^A-Za-z0-9]+/g, '_').replace(/^_|_$/g, '')}`,
          deprecated: String(api.status) === 'undone' ? undefined : undefined,
        }
        if (parameters.length > 0) operation.parameters = parameters
        const requestBody = buildRequestBody(api)
        if (requestBody) operation.requestBody = requestBody
        operation.responses = buildResponses(api)
        paths[path][method] = operation

        // 记录 data 为空的接口，便于人工复核
        const resSchema = parseSchema(api.res_body)
        const dataSchema = resSchema?.properties?.data
        if (
          dataSchema &&
          dataSchema.type === 'object' &&
          dataSchema.properties &&
          Object.keys(dataSchema.properties).length === 0
        ) {
          stats.emptyData.push(`${method.toUpperCase()} ${path}`)
        }
      }
    }
  }

  const document = {
    openapi: '3.0.3',
    info: {
      title: '苍穹外卖接口（由 Apifox 导出机械转换）',
      version: '1.0.0',
      description: [
        '本文件由 scripts/apifox-to-openapi.mjs 从 Apifox 导出的 JSON 机械转换而来。',
        '规则：只做格式重组，不新增任何字段；查询参数与路径参数的类型在源文档中缺失，',
        '脚本按 example 做了保守推断并在 description 中标注。',
      ].join(''),
    },
    servers: [{ url: 'http://localhost:8080', description: '本机后端（原小程序 env.js 注释中的 nginx 地址）' }],
    tags,
    paths,
  }

  const outPath = resolve(projectRoot, 'openapi.json')
  writeFileSync(outPath, `${JSON.stringify(document, null, 2)}\n`, 'utf8')

  console.log(`[apifox-to-openapi] 源文档 ${stats.docs} 份，接口 ${stats.apis} 个 -> ${outPath}`)
  console.log(`[apifox-to-openapi] 路径数 ${Object.keys(paths).length}，tag 数 ${tags.length}`)
  if (stats.emptyData.length > 0) {
    console.log(`[apifox-to-openapi] 响应 data 为空对象的接口 ${stats.emptyData.length} 个（类型会退化为空 object）：`)
    for (const item of stats.emptyData) console.log(`  - ${item}`)
  }
}

main()
