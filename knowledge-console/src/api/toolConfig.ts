import { http, unwrap } from './client';
import type { ToolCatalogItem, ToolConfigMasked, ToolConfigReq } from './types';

export const toolConfigApi = {
  get: () => unwrap<ToolConfigMasked>(http.get('/tool-config')),
  update: (req: ToolConfigReq) =>
    unwrap<ToolConfigMasked>(http.put('/tool-config', req)),
};

/** 工具目录（后端 sys_tool + 租户开关） */
export const toolCatalogApi = {
  list: () => unwrap<ToolCatalogItem[]>(http.get('/tools')),
};
