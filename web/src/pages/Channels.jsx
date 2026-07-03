import React, { useEffect, useState } from 'react'
import { Table, Button, Modal, Form, Input, InputNumber, Select, Space, Tag, message, Popconfirm, Switch } from 'antd'
import { PlusOutlined, DeleteOutlined, EditOutlined, ApiOutlined } from '@ant-design/icons'
import { getChannels, createChannel, updateChannel, deleteChannel, updateChannelStatus, getEnabledModels } from '../api'

export default function Channels() {
  const [channels, setChannels] = useState([])
  const [loading, setLoading] = useState(false)
  const [modalOpen, setModalOpen] = useState(false)
  const [editing, setEditing] = useState(null)
  const [form] = Form.useForm()
  const [modelOptions, setModelOptions] = useState([])


  const load = () => {
    setLoading(true)
    getChannels().then(res => {
      if (res.code === 200) setChannels(res.data || [])
    }).finally(() => setLoading(false))
  }

  const loadModels = () => {
    getEnabledModels().then(res => {
      if (res.code === 200) {
        setModelOptions((res.data || []).map(m => ({ value: m.name, label: m.name })))
      }
    })
  }

  useEffect(() => { load(); loadModels() }, [])

  const handleSave = async () => {
    const values = await form.validateFields()
    // 多选模型转逗号分隔字符串
    if (values.models && Array.isArray(values.models)) {
      values.models = values.models.join(',')
    }
    if (editing) {
      await updateChannel(editing.id, values)
      message.success('更新成功')
    } else {
      await createChannel(values)
      message.success('创建成功')
    }
    setModalOpen(false)
    form.resetFields()
    setEditing(null)
    load()
  }

  const handleDelete = async (id) => {
    await deleteChannel(id)
    message.success('删除成功')
    load()
  }

  const handleStatusChange = async (id, status) => {
    await updateChannelStatus(id, status ? 1 : 0)
    message.success('状态已更新')
    load()
  }

  const columns = [
    { title: 'ID', dataIndex: 'id', width: 60 },
    { title: '名称', dataIndex: 'name', width: 120 },
    { title: '类型', dataIndex: 'type', width: 100, render: v => <Tag color="blue">{v}</Tag> },
    { title: 'Base URL', dataIndex: 'baseUrl', ellipsis: true },
    { title: '模型', dataIndex: 'models', ellipsis: true, render: v => v ? v.split(',').map(m => <Tag key={m}>{m.trim()}</Tag>) : '-' },
    { title: '状态', dataIndex: 'status', width: 80, render: (v, r) => <Switch checked={v === 1} onChange={(c) => handleStatusChange(r.id, c)} /> },
    {
      title: '操作', width: 150, render: (_, record) => (
        <Space>
          <Button size="small" icon={<EditOutlined />} onClick={() => {
            setEditing(record)
            const formValues = { ...record }
            if (formValues.models && typeof formValues.models === 'string') {
              formValues.models = formValues.models.split(',').map(m => m.trim()).filter(m => m)
            }
            form.setFieldsValue(formValues)
            setModalOpen(true)
          }}>编辑</Button>
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
        <h2>渠道管理</h2>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => { setEditing(null); form.resetFields(); setModalOpen(true) }}>新增渠道</Button>
      </div>
      <Table columns={columns} dataSource={channels} rowKey="id" loading={loading} scroll={{ x: 1000 }} />
      <Modal title={editing ? '编辑渠道' : '新增渠道'} open={modalOpen} onOk={handleSave} onCancel={() => setModalOpen(false)} width={600}>
        <Form form={form} layout="vertical">
          <Form.Item name="name" label="名称" rules={[{ required: true }]}><Input placeholder="渠道名称" /></Form.Item>
          <Form.Item name="type" label="类型" rules={[{ required: true }]}>
            <Select placeholder="选择类型" options={[{ value: 'openai', label: 'OpenAI' }, { value: 'azure', label: 'Azure' }, { value: 'claude', label: 'Claude' }, { value: 'custom', label: '自定义' }]} />
          </Form.Item>
          <Form.Item name="baseUrl" label="Base URL" rules={[{ required: true }]}><Input placeholder="https://api.openai.com" /></Form.Item>
          <Form.Item name="apiKey" label="API Key" rules={[{ required: true }]}><Input.Password placeholder="sk-xxx" /></Form.Item>
          <Form.Item name="models" label="支持模型">
            <Select
              mode="tags"
              placeholder="选择或输入模型名称"
              options={modelOptions}
              tokenSeparators={[',']}
              style={{ width: '100%' }}
            />
          </Form.Item>
          <Space>
            <Form.Item name="priority" label="优先级" initialValue={0}><InputNumber /></Form.Item>
            <Form.Item name="rateLimit" label="速率限制" initialValue={0}><InputNumber min={0} placeholder="0=不限" /></Form.Item>
          </Space>
        </Form>
      </Modal>
    </div>
  )
}
