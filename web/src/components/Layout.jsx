import React, { useState } from 'react'
import { Outlet, useNavigate, useLocation } from 'react-router-dom'
import { Layout, Menu, Avatar, Dropdown, Space, theme, Modal, Input, message } from 'antd'
import {
  DashboardOutlined, ApiOutlined, KeyOutlined,
  UserOutlined, TeamOutlined, LogoutOutlined, MenuFoldOutlined, MenuUnfoldOutlined,
  RobotOutlined, GiftOutlined
} from '@ant-design/icons'
import { redeemCoupon } from '../api'

const { Header, Sider, Content } = Layout

export default function AppLayout() {
  const [collapsed, setCollapsed] = useState(false)
  const [redeemOpen, setRedeemOpen] = useState(false)
  const [redeemCode, setRedeemCode] = useState('')
  const [redeemLoading, setRedeemLoading] = useState(false)
  const navigate = useNavigate()
  const location = useLocation()
  const { token: themeToken } = theme.useToken()

  const user = JSON.parse(localStorage.getItem('user') || '{}')
  const isAdmin = user.role === 'admin'

  const menuItems = [
    { key: '/', icon: <DashboardOutlined />, label: '仪表盘' },
    ...(isAdmin ? [
      { key: '/channels', icon: <ApiOutlined />, label: '渠道管理' },
      { key: '/models', icon: <RobotOutlined />, label: '模型管理' },
    ] : []),
    { key: '/tokens', icon: <KeyOutlined />, label: 'Token 管理' },
    ...(isAdmin ? [
      { key: '/users', icon: <TeamOutlined />, label: '用户管理' },
      { key: '/coupons', icon: <GiftOutlined />, label: '积分券管理' },
    ] : []),
    { key: '/profile', icon: <UserOutlined />, label: '个人中心' },
  ]

  const userMenu = {
    items: [
      { key: 'redeem', icon: <GiftOutlined />, label: '积分兑换', onClick: () => setRedeemOpen(true) },
      { key: 'profile', icon: <UserOutlined />, label: '个人中心', onClick: () => navigate('/profile') },
      { type: 'divider' },
      { key: 'logout', icon: <LogoutOutlined />, label: '退出登录', onClick: () => {
        localStorage.removeItem('token')
        localStorage.removeItem('user')
        navigate('/login')
      }},
    ]
  }

  const handleRedeem = async () => {
    if (!redeemCode.trim()) {
      message.warning('请输入兑换码')
      return
    }
    setRedeemLoading(true)
    try {
      await redeemCoupon(redeemCode.trim())
      message.success('兑换成功')
      setRedeemOpen(false)
      setRedeemCode('')
    } catch (err) {
      message.error(err?.message || '兑换失败')
    } finally {
      setRedeemLoading(false)
    }
  }

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider trigger={null} collapsible collapsed={collapsed} theme="dark">
        <div className="logo">{collapsed ? 'AI' : 'AI Connecting'}</div>
        <Menu
          theme="dark"
          mode="inline"
          selectedKeys={[location.pathname]}
          items={menuItems}
          onClick={({ key }) => navigate(key)}
        />
      </Sider>
      <Layout>
        <Header style={{ padding: '0 24px', background: themeToken.colorBgContainer, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <span onClick={() => setCollapsed(!collapsed)} style={{ fontSize: 18, cursor: 'pointer' }}>
            {collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
          </span>
          <Dropdown menu={userMenu}>
            <Space style={{ cursor: 'pointer' }}>
              <Avatar icon={<UserOutlined />} />
              <span>{user.nickname || user.username || '用户'}</span>
            </Space>
          </Dropdown>
        </Header>
        <Content style={{ margin: '24px 16px', padding: 24, background: themeToken.colorBgContainer, borderRadius: themeToken.borderRadiusLG, minHeight: 280 }}>
          <Outlet />
        </Content>
      </Layout>
      <Modal
        title="积分兑换"
        open={redeemOpen}
        onCancel={() => { setRedeemOpen(false); setRedeemCode('') }}
        okText="兑换"
        cancelText="取消"
        confirmLoading={redeemLoading}
        onOk={handleRedeem}
      >
        <Input
          placeholder="请输入兑换码"
          value={redeemCode}
          onChange={(e) => setRedeemCode(e.target.value)}
          onPressEnter={handleRedeem}
        />
      </Modal>
    </Layout>
  )
}
