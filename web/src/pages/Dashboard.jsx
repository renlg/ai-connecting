import React, { useEffect, useState } from 'react'
import { Card, Row, Col, Statistic, Spin, message } from 'antd'
import { ApiOutlined, KeyOutlined, UserOutlined, CloudServerOutlined, SendOutlined, NumberOutlined } from '@ant-design/icons'
import { getDashboard } from '../api'

export default function Dashboard() {
  const [stats, setStats] = useState(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    getDashboard().then(res => {
      if (res.code === 200) setStats(res.data)
    }).catch(() => message.error('加载仪表盘数据失败')).finally(() => setLoading(false))
  }, [])

  if (loading) return <Spin size="large" style={{ display: 'block', margin: '100px auto' }} />

  const cards = [
    { title: '总渠道数', value: stats?.totalChannels || 0, icon: <ApiOutlined />, color: '#1677ff' },
    { title: '活跃渠道', value: stats?.activeChannels || 0, icon: <CloudServerOutlined />, color: '#52c41a' },
    { title: 'Token 数量', value: stats?.totalTokens || 0, icon: <KeyOutlined />, color: '#722ed1' },
    { title: '用户数量', value: stats?.totalUsers || 0, icon: <UserOutlined />, color: '#fa8c16' },
    { title: '总请求数', value: stats?.totalRequests || 0, icon: <SendOutlined />, color: '#13c2c2' },
    { title: '今日请求', value: stats?.requestsToday || 0, icon: <NumberOutlined />, color: '#eb2f96' },
  ]

  return (
    <div>
      <h2 style={{ marginBottom: 24 }}>仪表盘</h2>
      <Row gutter={[16, 16]}>
        {cards.map((item, i) => (
          <Col xs={24} sm={12} md={8} key={i}>
            <Card hoverable>
              <Statistic
                title={item.title}
                value={item.value}
                prefix={<span style={{ color: item.color }}>{item.icon}</span>}
              />
            </Card>
          </Col>
        ))}
      </Row>
      <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
        <Col xs={24} sm={12}>
          <Card>
            <Statistic title="总消耗 Token" value={stats?.totalTokensUsed || 0} suffix="tokens" />
          </Card>
        </Col>
        <Col xs={24} sm={12}>
          <Card>
            <Statistic title="今日消耗 Token" value={stats?.tokensUsedToday || 0} suffix="tokens" />
          </Card>
        </Col>
      </Row>
    </div>
  )
}
