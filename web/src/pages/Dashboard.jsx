import React, { useEffect, useState } from 'react'
import { Card, Row, Col, Statistic, Spin, message } from 'antd'
import { KeyOutlined, SendOutlined, NumberOutlined, DollarOutlined } from '@ant-design/icons'
import { ApiOutlined, CloudServerOutlined, UserOutlined, WalletOutlined, StopOutlined } from '@ant-design/icons'
import { getDashboard } from '../api'

export default function Dashboard() {
  const [stats, setStats] = useState(null)
  const [loading, setLoading] = useState(true)
  const user = JSON.parse(localStorage.getItem('user') || '{}')
  const isAdmin = user.role === 'admin'

  useEffect(() => {
    getDashboard().then(res => {
      if (res.code === 200) setStats(res.data)
    }).catch(() => message.error('加载仪表盘数据失败')).finally(() => setLoading(false))
  }, [])

  if (loading) return <Spin size="large" style={{ display: 'block', margin: '100px auto' }} />

  // ── Top: Today's data (prominent) ──
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
  ]

  // ── Middle: Cumulative data ──
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
      { title: '封禁渠道', value: stats?.blockedChannels || 0, icon: <StopOutlined />, color: '#ff4d4f' },
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
            <Card hoverable style={{ borderRadius: 8 }}>
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
    </div>
  )
}
