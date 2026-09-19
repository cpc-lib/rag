import { http, unwrap } from './client';
import type { Chunk, PageResult } from './types';

export interface ChunkCreateReq {
  documentId: number;
  content: string;
  page?: number;
  sectionTitle?: string;
}

export interface ChunkUpdateReq {
  content?: string;
  page?: number;
  sectionTitle?: string;
}

export const chunkApi = {
  get: (id: number) => unwrap<Chunk>(http.get(`/chunks/${id}`)),
  page: (documentId: number, page = 1, size = 50) =>
    unwrap<PageResult<Chunk>>(
      http.get(`/documents/${documentId}/chunks`, { params: { page, size } }),
    ),
  create: (req: ChunkCreateReq) => unwrap<Chunk>(http.post('/chunks', req)),
  update: (id: number, req: ChunkUpdateReq) =>
    unwrap<Chunk>(http.put(`/chunks/${id}`, req)),
  remove: (id: number) => unwrap<void>(http.delete(`/chunks/${id}`)),
};
