import { useRef, useState } from 'react';
import { App } from 'antd';
import { createSHA256 } from 'hash-wasm';
import { uploadApi } from '../api/uploads';

export interface UploadTask {
  key: string;
  name: string;
  percent: number;
  tip?: string;
  speed?: string;
}

/** 上传速度格式化 */
function formatSpeed(bps: number): string {
  if (bps >= 1024 * 1024 * 1024) return `${(bps / 1024 / 1024 / 1024).toFixed(1)} GB/s`;
  if (bps >= 1024 * 1024) return `${(bps / 1024 / 1024).toFixed(1)} MB/s`;
  if (bps >= 1024) return `${(bps / 1024).toFixed(1)} KB/s`;
  return `${bps.toFixed(0)} B/s`;
}

/**
 * 分片上传：流式 SHA-256 → 秒传 → 断点续传 → 分片上传 → 合并。
 * 每个文件独立任务与进度，互不阻塞；批内相同 SHA-256 只保留第一个任务。
 */
export function useMultipartUpload({ onCompleted }: { onCompleted: () => void | Promise<void> }) {
  const { message } = App.useApp();
  const [uploads, setUploads] = useState<UploadTask[]>([]);
  /** 本次上传会话内已处理的 SHA-256：相同内容文件只保留一个任务 */
  const seenSha256Ref = useRef<Set<string>>(new Set());

  const beforeUpload = async (file: File): Promise<boolean> => {
    const key = `upload-session:${file.name}:${file.size}`;
    const taskKey = `${file.name}:${file.size}:${Date.now()}`;
    setUploads((prev) => [...prev, { key: taskKey, name: file.name, percent: 0, tip: 'loading' }]);
    const setPct = (p: number) =>
      setUploads((prev) => prev.map((u) => (u.key === taskKey ? { ...u, percent: p, tip: undefined } : u)));
    try {
      // 秒传：流式分块计算 SHA-256（hash-wasm 增量模式，内存占用极低，大小无上限）
      let sha256Hex: string;
      {
        const hasher = await createSHA256();
        const CHUNK = 8 * 1024 * 1024;
        for (let off = 0; off < file.size; off += CHUNK) {
          const buf = await file.slice(off, Math.min(off + CHUNK, file.size)).arrayBuffer();
          hasher.update(new Uint8Array(buf));
          // 校验进度映射到 0-50%，避免长时间 0% 卡死感
          setUploads((prev) => prev.map((u) => (u.key === taskKey
            ? { ...u, percent: Math.round(((off + CHUNK) / file.size) * 50), tip: 'loading' }
            : u)));
        }
        sha256Hex = hasher.digest('hex');
        setUploads((prev) => prev.map((u) => (u.key === taskKey ? { ...u, percent: 50 } : u)));
      }
      // 批内去重：同一 sha256 只保留第一个任务，其余直接跳过（仅进行中去重，完成后由后端秒传兜底）
      if (seenSha256Ref.current.has(sha256Hex)) {
        setUploads((prev) => prev.map((u) => (u.key === taskKey ? { ...u, percent: 100, tip: '重复' } : u)));
        message.info(`${file.name} 与本次上传中的另一文件内容相同，已跳过`);
        return false;
      }
      seenSha256Ref.current.add(sha256Hex);
      try {
        let sessionId = Number(localStorage.getItem(key)) || 0;
        let chunkSize = 0;
        const done = new Set<number>();
        if (sessionId) {
          try {
            const s = await uploadApi.session(sessionId);
            if (s.status === 'UPLOADING') {
              chunkSize = s.chunkSize;
              s.uploadedParts.forEach((p) => done.add(p));
            } else {
              sessionId = 0;
            }
          } catch {
            sessionId = 0;
          }
        }
        if (!sessionId) {
          const r = await uploadApi.init(file.name, file.size, file.type || 'application/octet-stream', sha256Hex);
          if (r.instant) {
            // 秒传命中：文件库已有同内容条目，直接完成
            localStorage.removeItem(key);
            setUploads((prev) => prev.map((u) => (u.key === taskKey ? { ...u, percent: 100, tip: undefined } : u)));
            message.success(`${file.name} 秒传成功（文件已存在）`);
            await onCompleted();
            return false;
          }
          sessionId = r.sessionId;
          chunkSize = r.chunkSize;
          localStorage.setItem(key, String(sessionId));
        }
        const total = Math.ceil(file.size / chunkSize);
        if (done.size > 0) setPct(Math.round((done.size / total) * 100));
        let lastT = performance.now();
        for (let i = 0; i < total; i++) {
          const part = i + 1;
          if (done.has(part)) continue;
          const slice = file.slice(i * chunkSize, (i + 1) * chunkSize);
          await uploadApi.uploadPart(sessionId, part, slice);
          const now = performance.now();
          const sec = (now - lastT) / 1000;
          lastT = now;
          done.add(part);
          const bps = slice.size / sec;
          setUploads((prev) => prev.map((u) => (u.key === taskKey ? { ...u, percent: Math.round((done.size / total) * 100), tip: undefined, speed: formatSpeed(bps) } : u)));
        }
        await uploadApi.complete(sessionId);
        localStorage.removeItem(key);
        message.success(`${file.name} 上传成功`);
        await onCompleted();
      } finally {
        // 任务结束后从批内去重集合移除，允许用户删除后再次上传同一文件
        seenSha256Ref.current.delete(sha256Hex);
      }
    } catch {
      message.error(`${file.name} 上传失败，重新上传将自动从断点续传`);
    } finally {
      setTimeout(() => setUploads((prev) => prev.filter((u) => u.key !== taskKey)), 800);
    }
    return false;
  };

  return { uploads, beforeUpload };
}
