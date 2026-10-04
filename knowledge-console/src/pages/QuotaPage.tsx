import { useCallback, useEffect, useState } from 'react';
import { Button, Card, Col, Progress, Row, Spin, Typography } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { quotaApi } from '../api/quotas';
import type { QuotaUsage } from '../api/types';

function percent(used: number, max: number | null): number {
  if (max == null || max <= 0) return 0;
  return Math.min(100, (used / max) * 100);
}

const formatNumber = (n: number | null | undefined) =>
  n == null ? '未限制' : n.toLocaleString('zh-CN');

/** 配额用量（spec 3.1） */
export default function QuotaPage() {
  const [usage, setUsage] = useState<QuotaUsage | null>(null);
  const [loading, setLoading] = useState(true);

  const load = useCallback(() => {
    setLoading(true);
    quotaApi
      .usage()
      .then(setUsage)
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  if (loading && !usage) {
    return <Spin style={{ width: '100%', marginTop: 120 }} size="large" />;
  }

  if (!usage) {
    return null;
  }

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <Typography.Title level={4}>配额用量</Typography.Title>
        <Button icon={<ReloadOutlined />} loading={loading} onClick={load}>
          刷新
        </Button>
      </div>
      <Row gutter={[16, 16]}>
        <Col xs={24} sm={12}>
          <Card title="对象存储" size="small">
            <Progress
              type="dashboard"
              percent={percent(usage.storageUsedMb, usage.storageMaxMb)}
              format={() => `${percent(usage.storageUsedMb, usage.storageMaxMb).toFixed(4)}%`}
            />
            <Typography.Paragraph type="secondary" style={{ marginTop: 12, marginBottom: 0 }}>
              {usage.storageUsedMb} MB / {formatNumber(usage.storageMaxMb)} MB
            </Typography.Paragraph>
          </Card>
        </Col>
        <Col xs={24} sm={12}>
          <Card title="LLM Token（本月）" size="small">
            <Progress
              type="dashboard"
              percent={percent(usage.tokensUsedThisMonth, usage.tokensMaxThisMonth)}
              strokeColor="#52c41a"
              format={() => `${percent(usage.tokensUsedThisMonth, usage.tokensMaxThisMonth).toFixed(4)}%`}
            />
            <Typography.Paragraph type="secondary" style={{ marginTop: 12, marginBottom: 0 }}>
              {formatNumber(usage.tokensUsedThisMonth)} / {formatNumber(usage.tokensMaxThisMonth)}
            </Typography.Paragraph>
          </Card>
        </Col>
      </Row>
    </div>
  );
}
