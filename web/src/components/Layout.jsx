import React, { useState, useEffect } from 'react'
import { Outlet, useNavigate, useLocation } from 'react-router-dom'
import { Layout, Menu, Avatar, Dropdown, Space, theme, Modal, Input, message, Alert, Typography } from 'antd'
import {
  DashboardOutlined, ApiOutlined, KeyOutlined,
  UserOutlined, TeamOutlined, LogoutOutlined, MenuFoldOutlined, MenuUnfoldOutlined,
  RobotOutlined, GiftOutlined, CopyOutlined
} from '@ant-design/icons'
import { redeemCoupon, getLatestAnnouncements, getInviteCode } from '../api'

const { Text } = Typography

const { Header, Sider, Content } = Layout

export default function AppLayout() {
  const [collapsed, setCollapsed] = useState(false)
  const [redeemOpen, setRedeemOpen] = useState(false)
  const [redeemCode, setRedeemCode] = useState('')
  const [redeemLoading, setRedeemLoading] = useState(false)
  const [announcements, setAnnouncements] = useState([])
  const [announcementModalOpen, setAnnouncementModalOpen] = useState(false)
  const [selectedAnnouncement, setSelectedAnnouncement] = useState(null)
  const [inviteCodeModalOpen, setInviteCodeModalOpen] = useState(false)
  const [inviteCode, setInviteCode] = useState('')
  const [inviteCodeLoading, setInviteCodeLoading] = useState(false)
  const navigate = useNavigate()
  const location = useLocation()
  const { token: themeToken } = theme.useToken()

  const user = JSON.parse(localStorage.getItem('user') || '{}')
  const isAdmin = user.role === 'admin'

  // 加载最新公告（每 30 秒刷新一次）
  useEffect(() => {
    loadAnnouncements()
    const interval = setInterval(loadAnnouncements, 30000)
    return () => clearInterval(interval)
  }, [])

  const loadAnnouncements = async () => {
    try {
      const res = await getLatestAnnouncements(5)
      if (res.code === 200) {
        setAnnouncements(res.data || [])
        console.log('公告已刷新:', new Date().toLocaleTimeString())
      }
    } catch (err) {
      console.error('加载公告失败:', err)
    }
  }

  const handleAnnouncementClick = (announcement) => {
    setSelectedAnnouncement(announcement)
    setAnnouncementModalOpen(true)
  }

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
      { key: '/announcements', icon: <DashboardOutlined />, label: '公告管理' },
    ] : []),
    { key: '/profile', icon: <UserOutlined />, label: '个人中心' },
  ]

  const userMenu = {
    items: [
      { key: 'invite', icon: <KeyOutlined />, label: '我的邀请码', onClick: () => showInviteCode() },
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

  const showInviteCode = async () => {
    setInviteCodeModalOpen(true)
    setInviteCodeLoading(true)
    try {
      const res = await getInviteCode()
      if (res.code === 200) {
        setInviteCode(res.data.inviteCode)
      }
    } catch (err) {
      message.error(err?.message || '获取邀请码失败')
    } finally {
      setInviteCodeLoading(false)
    }
  }

  const copyInviteCode = () => {
    if (inviteCode) {
      navigator.clipboard.writeText(inviteCode)
      message.success('邀请码已复制到剪贴板')
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
        {/* 公告滚动栏 */}
        {announcements.length > 0 && (
          <div style={{
            background: '#fff7e6',
            borderBottom: '1px solid #ffd591',
            padding: '8px 24px',
            overflow: 'hidden',
            position: 'relative',
          }}>
            <div style={{
              display: 'flex',
              animation: 'scroll-announcements 40s linear infinite',
              whiteSpace: 'nowrap',
              width: 'fit-content',
            }}>
              {[...announcements, ...announcements].map((a, i) => (
                <span key={`${a.id}-${i}`} style={{
                  marginRight: '60px',
                  color: '#d46b08',
                  fontSize: '14px',
                  cursor: 'pointer',
                }} onClick={() => handleAnnouncementClick(a)}>
                  <strong>【公告】</strong>{a.title}: {a.content}
                </span>
              ))}
            </div>
            <style>{`
              @keyframes scroll-announcements {
                0% { transform: translateX(0); }
                100% { transform: translateX(-50%); }
              }
            `}</style>
          </div>
        )}
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
      <Modal
        title={selectedAnnouncement?.title || '公告详情'}
        open={announcementModalOpen}
        onCancel={() => { setAnnouncementModalOpen(false); setSelectedAnnouncement(null) }}
        footer={null}
        width={600}
      >
        {selectedAnnouncement && (
          <div>
            <div style={{ marginBottom: 16, color: '#999', fontSize: '14px' }}>
              发布时间：{new Date(selectedAnnouncement.createdAt).toLocaleString('zh-CN', { hour12: false })}
            </div>
            <div style={{ whiteSpace: 'pre-wrap', lineHeight: 1.8, fontSize: '15px' }}>
              {selectedAnnouncement.content}
            </div>
          </div>
        )}
      </Modal>
      <Modal
        title="我的邀请码"
        open={inviteCodeModalOpen}
        onCancel={() => setInviteCodeModalOpen(false)}
        footer={null}
        width={400}
      >
        <div style={{ textAlign: 'center', padding: '20px 0' }}>
          <p style={{ marginBottom: 16, color: '#666' }}>分享你的邀请码，邀请好友注册使用</p>
          {inviteCodeLoading ? (
            <p>加载中...</p>
          ) : (
            <div style={{
              background: '#f5f5f5',
              padding: '16px 24px',
              borderRadius: 8,
              display: 'inline-flex',
              alignItems: 'center',
              gap: 12,
            }}>
              <Text strong style={{ fontSize: 24, letterSpacing: 2 }}>{inviteCode}</Text>
              <CopyOutlined
                style={{ fontSize: 20, cursor: 'pointer', color: '#1890ff' }}
                onClick={copyInviteCode}
              />
            </div>
          )}
        </div>
      </Modal>
    </Layout>
  )
}
