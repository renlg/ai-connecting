import React, { useEffect, useState } from 'react'
import { Table, Button, Modal, Form, Input, InputNumber, Space, Tag, message, Popconfirm, Switch, Tooltip } from 'antd'
import { PlusOutlined, DeleteOutlined, EditOutlined, ThunderboltOutlined, WalletOutlined } from '@ant-design/icons'
import { getModels, createModel, updateModel, deleteModel, updateModelStatus, batchCreateModels } from '../api'

const { TextArea } = Input

export default function Models() {
  const [models, setModels] = useState([])
  const [loading, setLoading] = useState(false)
  const [modalOpen, setModalOpen] = useState(false)
  const [batchModalOpen, setBatchModalOpen] = useState(false)
  const [editing, setEditing] = useState(null)
  const [form] = Form.useForm()
  const [batchForm] = Form.useForm()
  const [rateModalOpen, setRateModalOpen] = useState(false)
  const [rateModel, setRateModel] = useState(null)
  const [rateForm] = Form.useForm()

  const load = () => {
    setLoading(true)
    getModels().then(res => {
      if (res.code === 200) setModels(res.data || [])
    }).finally(() => setLoading(false))
  }

  useEffect(() => { load() }, [])

  const handleSave = async () => {
    const values = await form.validateFields()
    if (editing) {
      await updateModel(editing.id, values)
      message.success('更新成功')
    } else {
      await createModel(values)
      message.success('创建成功')
    }
    setModalOpen(false)
    form.resetFields()
    setEditing(null)
    load()
  }

  const handleBatchCreate = async () => {
    const values = await batchForm.validateFields()
    const names = values.names
      .split(/[\n,，]/)
      .map(n => n.trim())
      .filter(n => n)
    if (names.length === 0) {
      message.warning('请输入至少一个模型名称')
      return
    }
    await batchCreateModels(names)
    message.success(`成功添加 ${names.length} 个模型`)
    setBatchModalOpen(false)
    batchForm.resetFields()
    load()
  }

  const handleDelete = async (id) => {
    await deleteModel(id)
    message.success('删除成功')
    load()
  }

  const handleStatusChange = async (id, status) => {
    await updateModelStatus(id, status ? 1 : 0)
    message.success('状态已更新')
    load()
  }

  const handleUpdateRate = async () => {
    const values = await rateForm.validateFields()
    await updateModel(rateModel.id, { ...rateModel, ...values })
    message.success('积分比例已更新')
    setRateModalOpen(false)
    load()
  }

  const columns = [
    { title: 'ID', dataIndex: 'id', width: 60 },
    { title: '模型名称', dataIndex: 'name', width: 180, render: v => <Tag color="blue">{v}</Tag> },
    { title: '显示名称', dataIndex: 'displayName', width: 150, render: v => v || '-' },
    { title: '输入比例（积分/千token）', dataIndex: 'inputCreditRate', width: 130, render: v => v || 0 },
    { title: '输出比例（积分/千token）', dataIndex: 'outputCreditRate', width: 130, render: v => v || 0 },
    { title: '描述', dataIndex: 'description', ellipsis: true, render: v => v || '-' },
    {
      title: '仅管理员', dataIndex: 'adminOnly', width: 100,
      render: (v, r) => <Switch checked={!!v} onChange={(c) => {
        updateModel(r.id, { adminOnly: c }).then(() => {
          message.success('已更新')
          load()
        })
      }} />
    },
    {
      title: '状态', dataIndex: 'status', width: 80,
      render: (v, r) => <Switch checked={v === 1} onChange={(c) => handleStatusChange(r.id, c)} />
    },
    {
      title: '创建时间', dataIndex: 'createdAt', width: 180,
      render: v => v ? new Date(v).toLocaleString('zh-CN') : '-'
    },
    {
      title: '操作', width: 150, render: (_, record) => (
        <Space>
          <Button size="small" icon={<EditOutlined />} onClick={() => {
            setEditing(record)
            form.setFieldsValue(record)
            setModalOpen(true)
          }}>编辑</Button>
          <Button size="small" icon={<WalletOutlined />} onClick={() => {
            setRateModel(record)
            rateForm.setFieldsValue({ inputCreditRate: record.inputCreditRate ?? 0, outputCreditRate: record.outputCreditRate ?? 0 })
            setRateModalOpen(true)
          }}>修改比例</Button>
          <Popconfirm title="确定删除该模型？" onConfirm={() => handleDelete(record.id)}>
            <Button size="small" danger icon={<DeleteOutlined />}>删除</Button>
          </Popconfirm>
        </Space>
      )
    },
  ]

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
        <h2>模型管理</h2>
        <Space>
          <Button icon={<ThunderboltOutlined />} onClick={() => { batchForm.resetFields(); setBatchModalOpen(true) }}>批量添加</Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => { setEditing(null); form.resetFields(); setModalOpen(true) }}>新增模型</Button>
        </Space>
      </div>

      <Table columns={columns} dataSource={models} rowKey="id" loading={loading} scroll={{ x: 1200 }} />

      {/* 单个新增/编辑 */}
      <Modal title={editing ? '编辑模型' : '新增模型'} open={modalOpen} onOk={handleSave} onCancel={() => setModalOpen(false)} width={500}>
        <Form form={form} layout="vertical">
          <Form.Item name="name" label="模型名称" rules={[{ required: true, message: '请输入模型名称' }]}>
            <Input placeholder="例如: gpt-4o" disabled={!!editing} />
          </Form.Item>
          <Form.Item name="displayName" label="显示名称">
            <Input placeholder="例如: GPT-4o（用于 Token 管理展示）" />
          </Form.Item>
          <Form.Item name="inputCreditRate" label="输入积分比例（每1000 token）" initialValue={0}>
            <InputNumber min={0} style={{ width: '100%' }} placeholder="每 1000 输入 token 消耗的积分数" />
          </Form.Item>
          <Form.Item name="outputCreditRate" label="输出积分比例（每1000 token）" initialValue={0}>
            <InputNumber min={0} style={{ width: '100%' }} placeholder="每 1000 输出 token 消耗的积分数" />
          </Form.Item>
          <Form.Item name="description" label="描述">
            <Input placeholder="模型描述（可选）" />
          </Form.Item>
          <Form.Item name="adminOnly" label="仅管理员可选" valuePropName="checked" initialValue={false}>
            <Switch />
          </Form.Item>
        </Form>
      </Modal>

      {/* 批量添加 */}
      <Modal title="批量添加模型" open={batchModalOpen} onOk={handleBatchCreate} onCancel={() => setBatchModalOpen(false)} width={500}>
        <Form form={batchForm} layout="vertical">
          <Form.Item name="names" label="模型名称列表" rules={[{ required: true, message: '请输入模型名称' }]}>
            <TextArea rows={8} placeholder={'每行一个模型名称，或用逗号分隔\n例如:\ngpt-4o\ngpt-4o-mini\nclaude-3-5-sonnet-20241022'} />
          </Form.Item>
        </Form>
        <div style={{ color: '#999', fontSize: 12 }}>提示：已存在的模型名称会自动跳过</div>
      </Modal>

      {/* 修改积分比例 */}
      <Modal title={`修改积分比例 - ${rateModel?.name || ''}`} open={rateModalOpen} onOk={handleUpdateRate} onCancel={() => setRateModalOpen(false)} width={400}>
        <Form form={rateForm} layout="vertical">
          <Form.Item name="inputCreditRate" label="输入积分比例（每1000 token）">
            <InputNumber min={0} style={{ width: '100%' }} placeholder="每 1000 输入 token 消耗的积分数" />
          </Form.Item>
          <Form.Item name="outputCreditRate" label="输出积分比例（每1000 token）">
            <InputNumber min={0} style={{ width: '100%' }} placeholder="每 1000 输出 token 消耗的积分数" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  )
}
