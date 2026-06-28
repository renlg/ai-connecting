import React, { useEffect, useState } from 'react'
import { Table, Button, Modal, Form, Input, InputNumber, Space, Tag, message, Popconfirm, Switch, Typography } from 'antd'
import { PlusOutlined, DeleteOutlined, EditOutlined, CopyOutlined } from '@ant-design/icons'
import { getTokens, createToken, updateToken, deleteToken, updateTokenStatus } from '../api'
import dayjs from 'dayjs'

const { Text } = Typography

export default function Tokens() {
  const [tokens, setTokens] = useState([])
  const [loading, setLoading] = useState(false)
  const [modalOpen, setModalOpen] = useState(false)
  const [editing, setEditing] = useState(null)
  const [form] = Form.useForm()

  const load = () => {
    setLoading(true)
    getTokens().then(res => {
      if (res.code === 200) setTokens(res.data || [])
    }).finally(() => setLoading(false))
  }

  useEffect(() => { load() }, [])

  const handleSave = async () => {
    const values = await form.validateFields()
    if (editing) {
      await updateToken(editing.id, values)
      message.success('更新成功')
    } else {
      await createToken(values)
      message.success('创建成功')
    }
    setModalOpen(false)
    form.resetFields()
    setEditing(null)
    load()
  }

  const handleDelete = async (id) => {
    await deleteToken(id)
    message.success('删除成功')
    load()
  }

  const handleStatusChange = async (id, status) => {
    await updateTokenStatus(id, status ? 1 : 0)
    message.success('状态已更新')
    load()
  }

  const copyToken = (key) => {
    navigator.clipboard.writeText(key)
    message.success('已复制到剪贴板')
  }

  const columns = [
    { title: 'ID', dataIndex: 'id', width: 60 },
    { title: '名称', dataIndex: 'name', width: 120 },
    {
      title: 'Token Key', dataIndex: 'tokenKey', width: 280,
      render: v => (
        <Space>
          <Text code style={{ fontSize: 12 }}>{v?.substring(0, 20)}...</Text>
          <Button size="small" icon={<CopyOutlined />} onClick={() => copyToken(v)} />
        </Space>
      )
    },
    { title: '额度', dataIndex: 'quota', width: 100, render: v => v === -1 ? '无限' : v },
    { title: '已用', dataIndex: 'usedQuota', width: 80 },
    { title: '允许模型', dataIndex: 'allowedModels', ellipsis: true, render: v => v ? v.split(',').map(m => <Tag key={m}>{m.trim()}</Tag>) : '全部' },
    { title: '过期时间', dataIndex: 'expiredAt', width: 160, render: v => v ? dayjs(v).format('YYYY-MM-DD HH:mm') : '永不过期' },
    { title: '状态', dataIndex: 'status', width: 80, render: (v, r) => <Switch checked={v === 1} onChange={(c) => handleStatusChange(r.id, c)} /> },
    {
      title: '操作', width: 150, render: (_, record) => (
        <Space>
          <Button size="small" icon={<EditOutlined />} onClick={() => { setEditing(record); form.setFieldsValue(record); setModalOpen(true) }}>编辑</Button>
          <Popconfirm title="确定删除？" onConfirm={() => handleDelete(record.id)}>
            <Button size="small" danger icon={<DeleteOutlined />}>删除</Button>
          </Popconfirm>
        </Space>
      )
    },
  ]

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
        <h2>Token 管理</h2>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => { setEditing(null); form.resetFields(); setModalOpen(true) }}>新增 Token</Button>
      </div>
      <Table columns={columns} dataSource={tokens} rowKey="id" loading={loading} scroll={{ x: 1100 }} />
      <Modal title={editing ? '编辑 Token' : '新增 Token'} open={modalOpen} onOk={handleSave} onCancel={() => setModalOpen(false)} width={500}>
        <Form form={form} layout="vertical">
          <Form.Item name="name" label="名称" rules={[{ required: true }]}><Input placeholder="Token 名称" /></Form.Item>
          <Form.Item name="quota" label="额度" initialValue={-1}><InputNumber style={{ width: '100%' }} placeholder="-1 表示无限" /></Form.Item>
          <Form.Item name="allowedModels" label="允许模型"><Input placeholder="gpt-4,gpt-3.5-turbo (空=全部)" /></Form.Item>
        </Form>
      </Modal>
    </div>
  )
}
