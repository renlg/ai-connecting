import React, { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Form, Input, Button, Card, message, Tabs, Typography } from 'antd'
import { UserOutlined, LockOutlined, MailOutlined, KeyOutlined } from '@ant-design/icons'
import { login, register } from '../api'

const { Title } = Typography

export default function Login() {
  const [loading, setLoading] = useState(false)
  const navigate = useNavigate()

  const oauthNext = () => {
    const next = new URLSearchParams(window.location.search).get('next')
    // Only honor the relay's own OAuth continuation path; avoid turning login into an open redirect.
    return next?.startsWith('/api/oauth/authorize?') ? next : null
  }

  const handleLogin = async (values) => {
    setLoading(true)
    try {
      const res = await login(values)
      if (res.code === 200) {
        localStorage.setItem('token', res.data.token)
        localStorage.setItem('user', JSON.stringify(res.data))
        message.success('登录成功')
        const next = oauthNext()
        if (next) {
          window.location.href = next
        } else {
          navigate('/')
        }
      } else {
        message.error(res.message || '登录失败')
      }
    } catch (err) {
      message.error(err.message || '登录失败')
    } finally {
      setLoading(false)
    }
  }

  const handleRegister = async (values) => {
    setLoading(true)
    try {
      const res = await register(values)
      if (res.code === 200) {
        message.success('注册成功，请登录')
      } else {
        message.error(res.message || '注册失败')
      }
    } catch (err) {
      message.error(err.message || '注册失败')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', justifyContent: 'center', alignItems: 'center', minHeight: '100vh', background: '#f0f2f5' }}>
      <Card style={{ width: 420, boxShadow: '0 2px 8px rgba(0,0,0,0.1)' }}>
        <div style={{ textAlign: 'center', marginBottom: 24 }}>
          <Title level={3} style={{ marginBottom: 4 }}>AI Connecting</Title>
          <p style={{ color: '#999' }}>AI Token 聚合管理面板</p>
        </div>
        <Tabs
          centered
          items={[
            {
              key: 'login',
              label: '登录',
              children: (
                <Form onFinish={handleLogin} size="large">
                  <Form.Item name="username" rules={[{ required: true, message: '请输入用户名' }]}>
                    <Input prefix={<UserOutlined />} placeholder="用户名" />
                  </Form.Item>
                  <Form.Item name="password" rules={[{ required: true, message: '请输入密码' }]}>
                    <Input.Password prefix={<LockOutlined />} placeholder="密码" />
                  </Form.Item>
                  <Form.Item>
                    <Button type="primary" htmlType="submit" loading={loading} block>登录</Button>
                  </Form.Item>
                </Form>
              )
            },
            {
              key: 'register',
              label: '注册',
              children: (
                <Form onFinish={handleRegister} size="large">
                  <Form.Item name="username" rules={[{ required: true, message: '请输入用户名' }]}>
                    <Input prefix={<UserOutlined />} placeholder="用户名" />
                  </Form.Item>
                  <Form.Item name="password" rules={[{ required: true, message: '请输入密码' }]}>
                    <Input.Password prefix={<LockOutlined />} placeholder="密码" />
                  </Form.Item>
                  <Form.Item name="email" rules={[{ required: true, message: '请输入邮箱' }, { type: 'email', message: '邮箱格式不正确' }]}>
                    <Input prefix={<MailOutlined />} placeholder="邮箱" />
                  </Form.Item>
                  <Form.Item name="nickname">
                    <Input prefix={<UserOutlined />} placeholder="昵称 (可选)" />
                  </Form.Item>
                  <Form.Item name="inviteCode" rules={[{ required: true, message: '请输入邀请码' }]}>
                    <Input prefix={<KeyOutlined />} placeholder="邀请码" />
                  </Form.Item>
                  <Form.Item>
                    <Button type="primary" htmlType="submit" loading={loading} block>注册</Button>
                  </Form.Item>
                </Form>
              )
            }
          ]}
        />
      </Card>
      <div style={{
        textAlign: 'center',
        padding: '12px 0',
        fontSize: 13,
        color: '#999',
        marginTop: 8,
      }}>
        <a href="https://beian.miit.gov.cn/" target="_blank" rel="noopener noreferrer" style={{ color: '#999', textDecoration: 'none' }}>
          浙ICP备2024089954号
        </a>
        {' '}&nbsp;{' '}
        <a href="http://www.beian.gov.cn/portal/registerSystemInfo?recordcode=33011002020003" target="_blank" rel="noopener noreferrer" style={{ color: '#999', textDecoration: 'none', display: 'inline-flex', alignItems: 'center', gap: 4 }}>
          <img src="/beian.png" style={{ width: 16, height: 16 }} alt="" />
          浙公网安备33011002020003号
        </a>
      </div>
    </div>
  )
}
