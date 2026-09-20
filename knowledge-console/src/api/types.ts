// 与 rag-api 后端 DTO / 实体严格对齐的类型定义

/** 后端统一响应体：code=0 成功 */
export interface ApiResult<T> {
  code: number;
  message: string;
  data: T;
}

/** MyBatis-Plus 分页结构 */
export interface PageResult<T> {
  records: T[];
  total: number;
  current: number;
  size: number;
  pages: number;
}

// ---- 鉴权 ----
export interface LoginReq {
  username: string;
  password: string;
  /** 多租户同名账号时填租户编码；平台管理员为 SUPER_TENANT */
  tenantCode?: string;
}

export interface ChangePasswordReq {
  oldPassword: string;
  newPassword: string;
}

export interface UserInfo {
  id: number;
  username: string;
  tenantId: string;
  /** 0=平台超级管理员 1=租户管理员 2=租户普通用户 */
  userType: 0 | 1 | 2;
  tenantName: string | null;
  /** 已授权功能菜单码（仅普通用户，如 chat / image-studio） */
  menuCodes: string[];
}

/** 租户内普通用户视图 */
export interface TenantUser {
  id: number;
  username: string;
  /** 1正常 0停用 */
  status: number;
  menuCodes: string[];
  toolCodes: string[];
  kbIds: number[];
  promptIds: number[];
}

export interface UserCredentialResp {
  user: TenantUser;
  initialPassword: string;
}

export interface ResetUserPasswordResp {
  userId: number;
  initialPassword: string;
}

export interface UpdateUserReq {
  status?: number;
  menuCodes?: string[];
  toolCodes?: string[];
}

export interface LoginResp {
  token: string;
  user: UserInfo;
}

// ---- 租户 ----
export interface Tenant {
  id: string;
  /** 租户编码（创建后不可改） */
  code: string;
  name: string;
  /** 1启用 0停用 */
  status: number | null;
  maxStorageMb: number | null;
  maxMqConcurrency: number | null;
  maxLlmTokensMonth: number | null;
  maxSseConnections: number | null;
}

export interface TenantCreateReq {
  name: string;
  code: string;
  adminUsername?: string;
  maxStorageMb?: number;
  maxMqConcurrency?: number;
  maxLlmTokensMonth?: number;
  maxSseConnections?: number;
}

export interface TenantUpdateReq {
  name?: string;
  status?: number;
  maxStorageMb?: number;
  maxMqConcurrency?: number;
  maxLlmTokensMonth?: number;
  maxSseConnections?: number;
}

export interface ResetAdminResp {
  userId: number;
  username: string;
  initialPassword: string;
}

// ---- 模型池 ----
export type ModelType = 'CHAT' | 'VISION' | 'EMBEDDING' | 'IMAGE';

/** 列表/详情返回的脱敏视图（密钥仅回传是否已配置） */
export interface ModelItem {
  id: number;
  name: string;
  type: ModelType;
  baseUrl: string | null;
  model: string | null;
  apiKeyConfigured: boolean;
  /** 仅详情接口返回明文；列表接口为 null */
  apiKey: string | null;
  temperature: number | null;
  topP: number | null;
  maxTokens: number | null;
  embeddingDim: number | null;
  enabled: boolean;
  createdAt: string;
}

/** 新增/编辑请求；编辑时 apiKey 留空表示不修改 */
export interface ModelReq {
  name: string;
  type: ModelType;
  baseUrl?: string;
  apiKey?: string;
  model?: string;
  temperature?: number;
  topP?: number;
  maxTokens?: number;
  embeddingDim?: number;
}

// ---- 知识库 ----
export interface KnowledgeBase {
  id: number;
  tenantId: string;
  name: string;
  description: string | null;
  milvusCollection: string | null;
  esIndex: string | null;
  chunkSize: number | null;
  chunkOverlap: number | null;
  parentChunkSize: number;
  childChunkSize: number;
  childOverlap: number;
  chunkStrategy: string;
  /** 分隔符 JSON 数组字符串，如 "[\"\\n\\n\",\"。\"]" */
  separators: string | null;
}

export interface KbCreateReq {
  name: string;
  description?: string;
  parentChunkSize?: number;
  childChunkSize?: number;
  childOverlap?: number;
  chunkStrategy?: string;
  separators?: string[];
}

export type KbUpdateReq = Partial<Omit<KbCreateReq, 'name'>> & { name?: string };

// ---- 文档 ----
export type DocumentStatus =
  | 'PARSING'
  | 'CHUNKING'
  | 'EMBEDDING'
  | 'INDEXING'
  | 'READY'
  | 'FAILED';

export interface DocumentItem {
  id: number;
  kbId: number;
  tenantId: string;
  fileName: string;
  objectKey: string;
  fileSize: number | null;
  mimeType: string | null;
  status: DocumentStatus | string;
  /** 处理进度 0~100 */
  progress: number;
  pageCount: number | null;
  warning: string | null;
  errorMsg: string | null;
}

// ---- 切片 ----
export type ChunkStatus = 'AUTO' | 'MANUAL' | 'DELETED';

export interface Chunk {
  id: number;
  kbId: number;
  documentId: number;
  tenantId: string;
  seq: number | null;
  content: string;
  page: number | null;
  status: ChunkStatus | string;
  /** PARENT/CHILD */
  chunkType: string;
  parentChunkId: number | null;
  sectionTitle: string | null;
  sectionPath: string | null;
}

// ---- 工具配置 ----
export interface ToolConfigMasked {
  weatherEnabled: boolean | null;
  tavilyEnabled: boolean | null;
  tavilyApiKeyConfigured: boolean;
}

export interface ToolConfigReq {
  weatherEnabled?: boolean;
  tavilyEnabled?: boolean;
  tavilyApiKey?: string;
}

// ---- 菜单 / 工具目录（后端下发）----
export interface SidebarMenu {
  code: string;
  name: string;
  path: string;
  icon: string | null;
}

export interface GrantableMenu {
  code: string;
  name: string;
  /** 租户总开关是否开启 */
  enabled: boolean;
}

export interface ToolCatalogItem {
  code: string;
  name: string;
  /** LLM function 名，如 query_weather */
  fnName: string;
  description: string;
  requiresKey: boolean;
  /** 租户总开关 */
  enabled: boolean;
  apiKeyConfigured: boolean;
}

// ---- 配额 ----
export interface QuotaUsage {
  storageUsedMb: number;
  storageMaxMb: number | null;
  tokensUsedThisMonth: number;
  tokensMaxThisMonth: number | null;
  sseCurrentConnections: number;
  sseMaxConnections: number | null;
  mqConcurrencyMax: number | null;
}

// ---- SSE 问答 ----
export interface Citation {
  seq: number;
  chunkId: number;
  parentChunkId: number | null;
  documentId: number;
  documentName: string;
  page: number;
  sectionPath: string | null;
  contentType: string | null;
  previewUrl: string | null;
  content: string;
}

export interface ChatStreamReq {
  kbId: number;
  question: string;
  promptId?: number;
  /** 空表示开启新会话，由后端创建并通过 session 事件回传 */
  sessionId?: number;
}

// ---- 问答会话 ----
export interface ChatSession {
  id: number;
  kbId: number;
  title: string;
  createdAt: string;
  updatedAt: string;
}

export interface ChatSessionMessage {
  dbId: number;
  role: 'user' | 'assistant';
  content: string;
  citations: Citation[] | null;
  tokenUsage: number | null;
  createdAt: string;
}

export interface ChatSessionDetail {
  session: ChatSession;
  messages: ChatSessionMessage[];
}

export interface PromptTemplate {
  id: number;
  kbId: number;
  name: string;
  content: string;
  isDefault: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface PromptReq {
  kbId: number;
  name: string;
  content: string;
  /** true 时设为所属知识库的默认模板 */
  isDefault?: boolean;
}

// ---- 文生图 ----
export interface ImageGenerateReq {
  prompt: string;
  size?: string;
  seed?: number | null;
}

export interface GeneratedImage {
  id: number;
  prompt: string;
  model: string;
  size: string | null;
  seed: number | null;
  /** MinIO 预签名地址 */
  url: string | null;
  createdAt: string | null;
  /** 文件字节大小，历史行首次加载后由后端懒回填 */
  fileSize: number | null;
}
