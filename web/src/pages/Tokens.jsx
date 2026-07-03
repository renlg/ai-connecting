import React, { useEffect, useState } from 'react'
import { Table, Button, Modal, Form, Input, InputNumber, Select, Space, Tag, message, Popconfirm, Switch, Typography } from 'antd'
import { PlusOutlined, DeleteOutlined, EditOutlined, CopyOutlined, SearchOutlined, BarChartOutlined } from '@ant-design/icons'
import { getTokens, createToken, updateToken, deleteToken, updateTokenStatus, getEnabledModels, getTokenCreditHistory } from '../api'
import dayjs from 'dayjs'

const { Text } = Typography

export default function Tokens() {
  const [tokens, setTokens] = useState([])
  const [loading, setLoading] = useState(false)
  const [modalOpen, setModalOpen] = useState(false)
  const [editing, setEditing] = useState(null)
  const [form] = Form.useForm()
  const [modelOptions, setModelOptions] = useState([])
  const [searchText, setSearchText] = useState('')
  const [historyModalOpen, setHistoryModalOpen] = useState(false)
  const [historyToken, setHistoryToken] = useState(null)
  const [historyData, setHistoryData] = useState([])
  const [historyLoading, setHistoryLoading] = useState(false)
  const user = JSON.parse(localStorage.getItem('user') || '{}')
  const isAdmin = user.role === 'admin'

  const load = (search) => {
    setLoading(true)
    getTokens(search).then(res => {
      if (res.code === 200) setTokens(res.data || [])
    }).finally(() => setLoading(false))
  }

  const loadModels = () => {
    getEnabledModels().then(res => {
      if (res.code === 200) {
        setModelOptions((res.data || []).map(m => ({
          value: m.name,
          label: m.displayName || m.name
        })))
      }
    })
  }

  useEffect(() => { load(); loadModels() }, [])

  const handleSearch = (value) => {
    setSearchText(value)
    load(value || undefined)
  }

  const handleSave = async () => {
    const values = await form.validateFields()
    // 多选模型转逗号分隔字符串
    if (values.allowedModels && Array.isArray(values.allowedModels)) {
      values.allowedModels = values.allowedModels.join(',')
    }
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

  const openCreditHistory = (token) => {
    setHistoryToken(token)
    setHistoryModalOpen(true)
    setHistoryLoading(true)
    getTokenCreditHistory(token.id).then(res => {
      if (res.code === 200) setHistoryData(res.data || [])
    }).finally(() => setHistoryLoading(false))
  }

  const copyToken = (key) => {
    if (navigator.clipboard && window.isSecureContext) {
      navigator.clipboard.writeText(key).then(() => message.success('已复制到剪贴板'))
    } else {
      const textArea = document.createElement('textarea')
      textArea.value = key
      textArea.style.position = 'fixed'
      textArea.style.left = '-9999px'
      document.body.appendChild(textArea)
      textArea.select()
      try {
        document.execCommand('copy')
        message.success('已复制到剪贴板')
      } catch (err) {
        message.error('复制失败，请手动复制')
      }
      document.body.removeChild(textArea)
    }
  }

  const columns = [
    { title: 'ID', dataIndex: 'id', width: 60 },
    { title: '名称', dataIndex: 'name', width: 120 },
    ...(isAdmin ? [{ title: '所属账号', dataIndex: 'ownerName', width: 120 }] : []),
    {
      title: 'Token Key', dataIndex: 'tokenKey', width: 280,
      render: v => (
        <Space>
          <Text code style={{ fontSize: 12 }}>{v?.substring(0, 20)}...</Text>
          <Button size="small" icon={<CopyOutlined />} onClick={() => copyToken(v)} />
        </Space>
      )
    },
    { title: '积分', dataIndex: 'credits', width: 100, render: v => v === -1 ? '无限' : (v != null ? Math.round(Number(v)) + ' 积分' : '0 积分') },
    ...(isAdmin ? [{ title: '限流(次/分)', dataIndex: 'rateLimit', width: 100, render: v => v === 0 ? '不限' : v + ' 次/分' }] : []),
    { title: '允许模型', dataIndex: 'allowedModels', ellipsis: true, render: v => v ? v.split(',').map(m => <Tag key={m}>{m.trim()}</Tag>) : '全部' },
    { title: '状态', dataIndex: 'status', width: 80, render: (v, r) => <Switch checked={v === 1} onChange={(c) => handleStatusChange(r.id, c)} /> },
    {
      title: '操作', width: 180, render: (_, record) => (
        <Space>
          <Button size="small" icon={<EditOutlined />} onClick={() => {
            setEditing(record)
            const formValues = { ...record }
            if (formValues.allowedModels && typeof formValues.allowedModels === 'string') {
              formValues.allowedModels = formValues.allowedModels.split(',').map(m => m.trim()).filter(m => m)
            }
            form.setFieldsValue(formValues)
            setModalOpen(true)
          }}>编辑</Button>
          <Button size="small" icon={<BarChartOutlined />} onClick={() => openCreditHistory(record)}>消耗记录</Button>
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
        <Space>
          {isAdmin && (
            <Input.Search
              placeholder="搜索所属账号"
              allowClear
              onSearch={handleSearch}
              style={{ width: 250 }}
              prefix={<SearchOutlined />}
            />
          )}
          <Button type="primary" icon={<PlusOutlined />} onClick={() => { setEditing(null); form.resetFields(); setModalOpen(true) }}>新增 Token</Button>
        </Space>
      </div>
      <Table columns={columns} dataSource={tokens} rowKey="id" loading={loading} scroll={{ x: 1100 }} />
      <Modal title={editing ? '编辑 Token' : '新增 Token'} open={modalOpen} onOk={handleSave} onCancel={() => setModalOpen(false)} width={500}>
        <Form form={form} layout="vertical">
          <Form.Item name="name" label="名称" rules={[{ required: true }]}><Input placeholder="Token 名称" /></Form.Item>
          <Form.Item name="credits" label="积分" initialValue={-1}><InputNumber style={{ width: '100%' }} placeholder="-1 表示无限" /></Form.Item>
          <Form.Item name="allowedModels" label="允许模型">
            <Select
              mode="multiple"
              placeholder="选择允许的模型（空=全部）"
              options={modelOptions}
              allowClear
              style={{ width: '100%' }}
            />
          </Form.Item>
          {isAdmin && (
            <Form.Item name="rateLimit" label="限流(每分钟请求数)" initialValue={0} tooltip="0 表示不限流">
              <InputNumber style={{ width: '100%' }} min={0} step={10} placeholder="每分钟最大请求数，0 表示不限" />
            </Form.Item>
          )}
        </Form>
      </Modal>
      <Modal
        title={`Token消耗记录 - ${historyToken?.name || ''}`}
        open={historyModalOpen}
        onCancel={() => setHistoryModalOpen(false)}
        footer={null}
        width={600}
      >
        <Table
          columns={[
            { title: '日期', dataIndex: 'date', key: 'date' },
            { title: '消耗积分', dataIndex: 'credits', key: 'credits', render: v => Number(v).toFixed(2) },
          ]}
          dataSource={historyData}
          rowKey="date"
          loading={historyLoading}
          pagination={{ pageSize: 10 }}
          size="small"
          locale={{ emptyText: '暂无消耗记录' }}
        />
      </Modal>
    </div>
  )
}
