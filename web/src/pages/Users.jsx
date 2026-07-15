import React, { useEffect, useState } from 'react'
import { Table, Tag, Switch, message, Button, Popconfirm, InputNumber, Modal, Form, Space, Input, Select } from 'antd'
import { SearchOutlined } from '@ant-design/icons'
import dayjs from 'dayjs'
import { getUsers, updateUserStatus, resetUserPassword, updateUserCredits, updateUserLevel } from '../api'

export default function Users() {
  const [users, setUsers] = useState([])
  const [loading, setLoading] = useState(false)
  const [creditsModalOpen, setCreditsModalOpen] = useState(false)
  const [levelModalOpen, setLevelModalOpen] = useState(false)
  const [editingUser, setEditingUser] = useState(null)
  const [creditsForm] = Form.useForm()
  const [levelForm] = Form.useForm()
  const [searchText, setSearchText] = useState('')

  const load = (search) => {
    setLoading(true)
    getUsers(search).then(res => {
      if (res.code === 200) setUsers(res.data || [])
    }).finally(() => setLoading(false))
  }

  useEffect(() => { load() }, [])

  const handleSearch = (value) => {
    setSearchText(value)
    load(value || undefined)
  }

  const handleStatusChange = async (id, status) => {
    await updateUserStatus(id, status ? 1 : 0)
    message.success('状态已更新')
    load()
  }

  const handleResetPassword = async (id) => {
    await resetUserPassword(id)
    message.success('密码已重置')
  }

  const handleUpdateCredits = async () => {
    const values = await creditsForm.validateFields()
    await updateUserCredits(editingUser.id, values.credits)
    message.success('积分已更新')
    setCreditsModalOpen(false)
    load()
  }

  const handleUpdateLevel = async () => {
    const values = await levelForm.validateFields()
    await updateUserLevel(editingUser.id, values.level)
    message.success('等级已更新')
    setLevelModalOpen(false)
    load()
  }

  const columns = [
    { title: 'ID', dataIndex: 'id', width: 60 },
    { title: '用户名', dataIndex: 'username', width: 120 },
    { title: '昵称', dataIndex: 'nickname', width: 120 },
    { title: '邮箱', dataIndex: 'email' },
    { title: '角色', dataIndex: 'role', width: 100, render: v => <Tag color={v === 'admin' ? 'red' : 'blue'}>{v === 'admin' ? '管理员' : '用户'}</Tag> },
    { title: '额度', dataIndex: 'quota', width: 100, render: v => v === -1 ? '无限' : v },
    { title: '已用额度', dataIndex: 'usedQuota', width: 100 },
    { title: '积分', dataIndex: 'credits', width: 100, render: v => v != null ? Math.round(Number(v)) + ' 积分' : '0 积分' },
    { title: '等级', dataIndex: 'level', width: 80, render: v => <Tag color="purple">Lv{v ?? 1}</Tag> },
    { title: '状态', dataIndex: 'status', width: 80, render: (v, r) => <Switch checked={v === 1} onChange={(c) => handleStatusChange(r.id, c)} /> },
    { title: '注册时间', dataIndex: 'createdAt', width: 170, render: v => v ? dayjs(v).format('YYYY-MM-DD HH:mm') : '-' },
    {
      title: '操作', width: 200, fixed: 'right', render: (_, r) => (
        <Space size="small" wrap>
          <Button type="link" size="small" onClick={() => {
            setEditingUser(r)
            creditsForm.setFieldsValue({ credits: r.credits ?? 0 })
            setCreditsModalOpen(true)
          }}>修改积分</Button>
          <Button type="link" size="small" onClick={() => {
            setEditingUser(r)
            levelForm.setFieldsValue({ level: r.level ?? 1 })
            setLevelModalOpen(true)
          }}>修改等级</Button>
          <Popconfirm title="确认重置密码？" description="密码将被重置为默认密码" onConfirm={() => handleResetPassword(r.id)} okText="确认" cancelText="取消">
            <Button type="link" danger size="small">重置密码</Button>
          </Popconfirm>
        </Space>
      )
    },
  ]

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
        <h2>用户管理</h2>
        <Input.Search
          placeholder="搜索用户名/昵称/邮箱"
          allowClear
          onSearch={handleSearch}
          style={{ width: 300 }}
          prefix={<SearchOutlined />}
        />
      </div>
      <Table columns={columns} dataSource={users} rowKey="id" loading={loading} scroll={{ x: 1100 }} />
      <Modal title={`修改积分 - ${editingUser?.username || ''}`} open={creditsModalOpen} onOk={handleUpdateCredits} onCancel={() => setCreditsModalOpen(false)} width={400}>
        <Form form={creditsForm} layout="vertical">
          <Form.Item name="credits" label="积分" rules={[{ required: true, message: '请输入积分数量' }]}>
            <InputNumber style={{ width: '100%' }} min={0} step={1} placeholder="输入积分数量" />
          </Form.Item>
        </Form>
      </Modal>
      <Modal title={`修改等级 - ${editingUser?.username || ''}`} open={levelModalOpen} onOk={handleUpdateLevel} onCancel={() => setLevelModalOpen(false)} width={400}>
        <Form form={levelForm} layout="vertical">
          <Form.Item name="level" label="用户等级" rules={[{ required: true, message: '请选择用户等级' }]}>
            <Select options={[1, 2, 3, 4, 5].map(l => ({ value: l, label: `Lv${l}` }))} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  )
}
