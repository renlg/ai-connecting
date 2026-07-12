import React, { useEffect, useMemo, useState } from 'react'
import { Card, Row, Col, Statistic, Spin, message, Modal, Table, Tag, Segmented, Empty } from 'antd'
import { KeyOutlined, SendOutlined, NumberOutlined, DollarOutlined } from '@ant-design/icons'
import { ApiOutlined, CloudServerOutlined, UserOutlined, WalletOutlined, StopOutlined } from '@ant-design/icons'
import { BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, Legend, ResponsiveContainer } from 'recharts'
import { getDashboard, getBlockedChannels, getDailyStats } from '../api'

// 缓存未命中 / 命中固定用两种颜色区分状态，模型身份由图例文案与横轴分组承载
const MISS_COLOR = '#1677ff'
const HIT_COLOR = '#722ed1'

function buildTokenChartData(data) {
  if (!data || !data.dailyTokensByModel || data.dailyTokensByModel.length === 0) {
    return { chartData: [], models: [] }
  }
  const models = [...new Set(data.dailyTokensByModel.map(d => d.model))]
  const grouped = {}
  data.dailyTokensByModel.forEach(item => {
    if (!grouped[item.date]) grouped[item.date] = { date: item.date }
    grouped[item.date][item.model + '_miss'] = item.cacheMissTokens
    grouped[item.date][item.model + '_hit'] = item.cachedTokens
  })
  return {
    chartData: Object.values(grouped).sort((a, b) => a.date.localeCompare(b.date)),
    models,
  }
}

export default function Dashboard() {
  const [stats, setStats] = useState(null)
  const [loading, setLoading] = useState(true)
  const [blockedModalOpen, setBlockedModalOpen] = useState(false)
  const [blockedChannels, setBlockedChannels] = useState([])
  const [blockedLoading, setBlockedLoading] = useState(false)
  const [dailyStats, setDailyStats] = useState(null)
  const [dailyStatsLoading, setDailyStatsLoading] = useState(true)
  const [days, setDays] = useState(7)
  const user = JSON.parse(localStorage.getItem('user') || '{}')
  const isAdmin = user.role === 'admin'

  useEffect(() => {
    getDashboard().then(res => {
      if (res.code === 200) setStats(res.data)
    }).catch(() => message.error('加载仪表盘数据失败')).finally(() => setLoading(false))
  }, [])

  useEffect(() => {
    setDailyStatsLoading(true)
    getDailyStats(days).then(res => {
      if (res.code === 200) setDailyStats(res.data)
    }).catch(() => message.error('加载每日统计数据失败')).finally(() => setDailyStatsLoading(false))
  }, [days])

  const { chartData: tokenChartData, models } = useMemo(() => buildTokenChartData(dailyStats), [dailyStats])
  const creditChartData = dailyStats?.dailyCredits || []

  const handleBlockedClick = () => {
    setBlockedModalOpen(true)
    setBlockedLoading(true)
    getBlockedChannels().then(res => {
      if (res.code === 200) setBlockedChannels(res.data || [])
    }).catch(() => message.error('加载封禁渠道失败')).finally(() => setBlockedLoading(false))
  }

  const blockedColumns = [
    { title: 'ID', dataIndex: 'id', width: 60 },
    { title: '渠道名称', dataIndex: 'name' },
    { title: '类型', dataIndex: 'type', width: 100, render: v => <Tag>{v}</Tag> },
    {
      title: '封禁至', dataIndex: 'blockedUntil', width: 180,
      render: v => v ? new Date(v).toLocaleString('zh-CN', { hour12: false }) : '-'
    },
  ]

  if (loading) return <Spin size="large" style={{ display: 'block', margin: '100px auto' }} />

  // ── Top: Today's data (prominent) ──
  const cacheHitRateToday = stats?.inputTokensToday > 0
    ? ((stats?.cachedPromptTokensToday || 0) / stats.inputTokensToday * 100).toFixed(1)
    : null

  const todayCards = [
    {
      title: '今日消耗积分',
      value: stats?.creditsConsumedToday != null ? Math.round(Number(stats.creditsConsumedToday)) : 0,
      icon: <DollarOutlined />, color: '#f5222d', suffix: '积分', prominent: true,
    },
    {
      title: '今日输入 Token',
      value: stats?.inputTokensToday || 0,
      icon: <KeyOutlined />, color: '#1677ff', suffix: 'tokens', prominent: true,
    },
    {
      title: '今日输出 Token',
      value: stats?.outputTokensToday || 0,
      icon: <KeyOutlined />, color: '#52c41a', suffix: 'tokens', prominent: true,
    },
    {
      title: '今日缓存 Token',
      value: stats?.cachedPromptTokensToday || 0,
      icon: <KeyOutlined />, color: '#722ed1', suffix: cacheHitRateToday != null ? `tokens (${cacheHitRateToday}%)` : 'tokens', prominent: true,
    },
    {
      title: '今日缓存创建 Token',
      value: stats?.todayCacheCreationTokens || 0,
      icon: <KeyOutlined />, color: '#fa8c16', suffix: 'tokens', prominent: true,
    },
    {
      title: '今日缓存读取 Token',
      value: stats?.todayCacheReadTokens || 0,
      icon: <KeyOutlined />, color: '#13c2c2', suffix: 'tokens', prominent: true,
    },
  ]

  // ── Middle: Cumulative data ──
  const cacheHitRateTotal = stats?.totalInputTokens > 0
    ? ((stats?.totalCachedPromptTokens || 0) / stats.totalInputTokens * 100).toFixed(1)
    : null

  const cumulativeCards = [
    {
      title: '总消耗积分',
      value: stats?.totalCreditsConsumed != null ? Math.round(Number(stats.totalCreditsConsumed)) : 0,
      icon: <DollarOutlined />, color: '#f5222d', suffix: '积分',
    },
    {
      title: '总输入 Token',
      value: stats?.totalInputTokens || 0,
      icon: <KeyOutlined />, color: '#1677ff', suffix: 'tokens',
    },
    {
      title: '总输出 Token',
      value: stats?.totalOutputTokens || 0,
      icon: <KeyOutlined />, color: '#52c41a', suffix: 'tokens',
    },
    {
      title: '总缓存 Token',
      value: stats?.totalCachedPromptTokens || 0,
      icon: <KeyOutlined />, color: '#722ed1', suffix: cacheHitRateTotal != null ? `tokens (${cacheHitRateTotal}%)` : 'tokens',
    },
    {
      title: '总缓存创建 Token',
      value: stats?.totalCacheCreationTokens || 0,
      icon: <KeyOutlined />, color: '#fa8c16', suffix: 'tokens',
    },
    {
      title: '总缓存读取 Token',
      value: stats?.totalCacheReadTokens || 0,
      icon: <KeyOutlined />, color: '#13c2c2', suffix: 'tokens',
    },
  ]

  // ── Bottom: Other data ──
  let otherCards = []
  if (isAdmin) {
    otherCards = [
      { title: '总请求数', value: stats?.totalRequests || 0, icon: <SendOutlined />, color: '#13c2c2' },
      { title: '今日请求数', value: stats?.requestsToday || 0, icon: <NumberOutlined />, color: '#eb2f96' },
      { title: 'Token 数量', value: stats?.totalTokens || 0, icon: <KeyOutlined />, color: '#722ed1' },
      { title: '用户数量', value: stats?.totalUsers || 0, icon: <UserOutlined />, color: '#fa8c16' },
      { title: '总渠道数', value: stats?.totalChannels || 0, icon: <ApiOutlined />, color: '#1677ff' },
      { title: '活跃渠道', value: stats?.activeChannels || 0, icon: <CloudServerOutlined />, color: '#52c41a' },
      { title: '封禁渠道', value: stats?.blockedChannels || 0, icon: <StopOutlined />, color: '#ff4d4f', clickable: true },
    ]
  } else {
    otherCards = [
      { title: '我的积分', value: stats?.myCredits != null ? Math.round(Number(stats.myCredits)) : 0, icon: <WalletOutlined />, color: '#fa8c16', suffix: '积分' },
      { title: '总请求数', value: stats?.totalRequests || 0, icon: <SendOutlined />, color: '#13c2c2' },
      { title: '今日请求数', value: stats?.requestsToday || 0, icon: <NumberOutlined />, color: '#eb2f96' },
      { title: '我的 Token 数量', value: stats?.totalTokens || 0, icon: <KeyOutlined />, color: '#722ed1' },
    ]
  }

  return (
    <div>
      <h2 style={{ marginBottom: 24 }}>{isAdmin ? '仪表盘' : '我的概览'}</h2>

      {/* ── Section 1: 今日数据（最显眼，大卡片加粗） ── */}
      <h3 style={{ fontSize: 18, marginBottom: 12, color: '#f5222d', fontWeight: 600 }}>
        📊 今日数据
      </h3>
      <Row gutter={[16, 16]}>
        {todayCards.map((item, i) => (
          <Col xs={24} sm={12} md={8} key={i}>
            <Card
              hoverable
              style={{
                borderTop: `4px solid ${item.color}`,
                background: 'linear-gradient(135deg, #fff5f5 0%, #fff 100%)',
                borderRadius: 8,
              }}
              bodyStyle={{ padding: '20px 24px' }}
            >
              <Statistic
                title={<span style={{ fontSize: 15, fontWeight: 600 }}>{item.title}</span>}
                value={item.value}
                prefix={<span style={{ color: item.color, fontSize: 22 }}>{item.icon}</span>}
                suffix={item.suffix}
                valueStyle={{
                  color: item.color,
                  fontWeight: 700,
                  fontSize: 28,
                }}
              />
            </Card>
          </Col>
        ))}
      </Row>

      {/* ── Section 2: 累计数据 ── */}
      <h3 style={{ fontSize: 18, marginBottom: 12, marginTop: 32, color: '#1677ff', fontWeight: 600 }}>
        📈 累计数据
      </h3>
      <Row gutter={[16, 16]}>
        {cumulativeCards.map((item, i) => (
          <Col xs={24} sm={12} md={8} key={i}>
            <Card hoverable style={{ borderRadius: 8 }}>
              <Statistic
                title={item.title}
                value={item.value}
                prefix={<span style={{ color: item.color }}>{item.icon}</span>}
                suffix={item.suffix}
                valueStyle={{ color: item.color, fontWeight: 600 }}
              />
            </Card>
          </Col>
        ))}
      </Row>

      {/* ── Section 3: 其他数据 ── */}
      <h3 style={{ fontSize: 18, marginBottom: 12, marginTop: 32, color: '#595959', fontWeight: 600 }}>
        📋 其他数据
      </h3>
      <Row gutter={[16, 16]}>
        {otherCards.map((item, i) => (
          <Col xs={24} sm={12} md={8} key={i}>
            <Card
              hoverable
              style={{ borderRadius: 8, cursor: item.clickable ? 'pointer' : 'default' }}
              onClick={item.clickable ? handleBlockedClick : undefined}
            >
              <Statistic
                title={item.title}
                value={item.value}
                prefix={<span style={{ color: item.color }}>{item.icon}</span>}
                suffix={item.suffix}
              />
            </Card>
          </Col>
        ))}
      </Row>

      {/* ── Section 4: 每日消耗趋势 ── */}
      <Row align="middle" justify="space-between" style={{ marginTop: 32, marginBottom: 12 }}>
        <Col>
          <h3 style={{ fontSize: 18, color: '#595959', fontWeight: 600, margin: 0 }}>
            📅 每日消耗趋势
          </h3>
        </Col>
        <Col>
          <Segmented
            value={days}
            onChange={setDays}
            options={[
              { label: '7 天', value: 7 },
              { label: '14 天', value: 14 },
              { label: '30 天', value: 30 },
            ]}
          />
        </Col>
      </Row>

      <Card title="每日消耗积分" style={{ borderRadius: 8, marginBottom: 16 }} loading={dailyStatsLoading}>
        {creditChartData.length === 0 ? (
          <Empty description="暂无数据" />
        ) : (
          <ResponsiveContainer width="100%" height={400}>
            <BarChart data={creditChartData} margin={{ top: 8, right: 16, left: 0, bottom: 8 }}>
              <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#e1e0d9" />
              <XAxis dataKey="date" tick={{ fontSize: 12 }} />
              <YAxis unit="积分" tick={{ fontSize: 12 }} />
              <Tooltip />
              <Bar dataKey="credits" name="消耗积分" fill={MISS_COLOR} barSize={24} radius={[4, 4, 0, 0]} />
            </BarChart>
          </ResponsiveContainer>
        )}
      </Card>

      {/* ── Section 5: 每日 Token 消耗（每个模型单独一个柱状图） ── */}
      <h3 style={{ fontSize: 18, marginBottom: 12, marginTop: 32, color: '#595959', fontWeight: 600 }}>
        🔤 每日 Token 消耗（按模型）
      </h3>
      {dailyStatsLoading ? (
        <Spin style={{ display: 'block', margin: '40px auto' }} />
      ) : models.length === 0 ? (
        <Card style={{ borderRadius: 8 }}><Empty description="暂无 Token 数据" /></Card>
      ) : (
        <Row gutter={[16, 16]}>
          {models.map(model => {
            const modelData = (dailyStats?.dailyTokensByModel || [])
              .filter(d => d.model === model)
              .map(d => ({ date: d.date, cacheMiss: d.cacheMissTokens, cacheHit: d.cachedTokens }))
              .sort((a, b) => a.date.localeCompare(b.date))
            return (
              <Col xs={24} sm={24} md={12} key={model}>
                <Card
                  title={<span style={{ fontSize: 14 }}>{model}</span>}
                  size="small"
                  style={{ borderRadius: 8 }}
                >
                  <ResponsiveContainer width="100%" height={250}>
                    <BarChart data={modelData} margin={{ top: 4, right: 8, left: 0, bottom: 4 }}>
                      <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#e1e0d9" />
                      <XAxis dataKey="date" tick={{ fontSize: 11 }} />
                      <YAxis tick={{ fontSize: 11 }} />
                      <Tooltip />
                      <Legend wrapperStyle={{ fontSize: 12 }} />
                      <Bar dataKey="cacheMiss" name="缓存未命中" fill={MISS_COLOR} stackId="a" barSize={20} />
                      <Bar dataKey="cacheHit" name="缓存命中" fill={HIT_COLOR} stackId="a" barSize={20} radius={[4, 4, 0, 0]} />
                    </BarChart>
                  </ResponsiveContainer>
                </Card>
              </Col>
            )
          })}
        </Row>
      )}

      <Modal
        title="封禁渠道列表"
        open={blockedModalOpen}
        onCancel={() => setBlockedModalOpen(false)}
        footer={null}
        width={600}
      >
        <Table
          columns={blockedColumns}
          dataSource={blockedChannels}
          rowKey="id"
          loading={blockedLoading}
          pagination={false}
          size="small"
          locale={{ emptyText: '当前没有封禁的渠道' }}
        />
      </Modal>
    </div>
  )
}
