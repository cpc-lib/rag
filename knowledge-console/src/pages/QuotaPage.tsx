import { useEffect, useState } from 'react';
import { Card, Col, Progress, Row, Spin, Typography } from 'antd';
import { quotaApi } from '../api/quotas';
import type { QuotaUsage } from '../api/types';

function percent(used: number, max: number | null): number {
  if (max == null || max <= 0) return 0;
  return Math.min(100, Math.round((used / max) * 100));
}

const formatNumber = (n: number | null | undefined) =>
  n == null ? '未限制' : n.toLocaleString('zh-CN');

/** 配额用量（spec 3.1） */
export default function QuotaPage() {
  const [usage, setUsage] = useState<QuotaUsage | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    quotaApi
      .usage()
      .then(setUsage)
      .finally(() => setLoading(false));
  }, []);

  if (loading || !usage) {
    return <Spin style={{ width: '100%', marginTop: 120 }} size="large" />;
  }

  return (
    <div>
      <Typography.Title level={4}>配额用量</Typography.Title>
      <Row gutter={[16, 16]}>
        <Col xs={24} sm={12} xl={6}>
          <Card title="对象存储" size="small">
            <Progress
              type="dashboard"
              percent={percent(usage.storageUsedMb, usage.storageMaxMb)}
              format={() => `${percent(usage.storageUsedMb, usage.storageMaxMb)}%`}
            />
            <Typography.Paragraph type="secondary" style={{ marginTop: 12, marginBottom: 0 }}>
              {usage.storageUsedMb} MB / {formatNumber(usage.storageMaxMb)} MB
            </Typography.Paragraph>
          </Card>
        </Col>
        <Col xs={24} sm={12} xl={6}>
          <Card title="LLM Token（本月）" size="small">
            <Progress
              type="dashboard"
              percent={percent(usage.tokensUsedThisMonth, usage.tokensMaxThisMonth)}
              strokeColor="#52c41a"
              format={() => `${percent(usage.tokensUsedThisMonth, usage.tokensMaxThisMonth)}%`}
            />
            <Typography.Paragraph type="secondary" style={{ marginTop: 12, marginBottom: 0 }}>
              {formatNumber(usage.tokensUsedThisMonth)} / {formatNumber(usage.tokensMaxThisMonth)}
            </Typography.Paragraph>
          </Card>
        </Col>
        <Col xs={24} sm={12} xl={6}>
          <Card title="SSE 并发连接" size="small">
            <Progress
              type="dashboard"
              percent={percent(usage.sseCurrentConnections, usage.sseMaxConnections)}
              strokeColor="#faad14"
              format={() => `${usage.sseCurrentConnections}`}
            />
            <Typography.Paragraph type="secondary" style={{ marginTop: 12, marginBottom: 0 }}>
              当前 {usage.sseCurrentConnections} / 上限 {formatNumber(usage.sseMaxConnections)}
            </Typography.Paragraph>
          </Card>
        </Col>
        <Col xs={24} sm={12} xl={6}>
          <Card title="MQ 任务并发额度" size="small">
            <Progress
              type="dashboard"
              percent={100}
              strokeColor="#722ed1"
              showInfo={false}
              format={() => ''}
            />
            <Typography.Paragraph type="secondary" style={{ marginTop: 12, marginBottom: 0 }}>
              最大并发：{formatNumber(usage.mqConcurrencyMax)}
            </Typography.Paragraph>
          </Card>
        </Col>
      </Row>
    </div>
  );
}
