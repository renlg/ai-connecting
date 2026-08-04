import React, { useEffect, useState } from 'react'
import { Table, Button, Modal, Form, Input, InputNumber, Select, Space, Tag, message, Popconfirm, Switch, Tooltip } from 'antd'
import { PlusOutlined, DeleteOutlined, EditOutlined, ThunderboltOutlined } from '@ant-design/icons'
import { getModels, createModel, updateModel, deleteModel, updateModelStatus, batchCreateModels } from '../api'

const { TextArea } = Input

const TYPE_META = {
  text: { color: 'default', label: '文本' },
  image: { color: 'purple', label: '图片' },
  video: { color: 'orange', label: '视频' },
  audio: { color: 'cyan', label: '音频' },
}

export default function Models() {
  const [models, setModels] = useState([])
  const [loading, setLoading] = useState(false)
  const [modalOpen, setModalOpen] = useState(false)
  const [batchModalOpen, setBatchModalOpen] = useState(false)
  const [editing, setEditing] = useState(null)
  const [form] = Form.useForm()
  const [batchForm] = Form.useForm()
  const modelType = Form.useWatch('type', form) || 'text'

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

  const columns = [
    { title: 'ID', dataIndex: 'id', width: 60 },
    { title: '模型名称', dataIndex: 'name', width: 180, render: v => <Tag color="blue">{v}</Tag> },
    { title: '显示名称', dataIndex: 'displayName', width: 150, render: v => v || '-' },
    {
      title: '类型', dataIndex: 'type', width: 80,
      render: v => {
        const meta = TYPE_META[v] || TYPE_META.text
        return <Tag color={meta.color}>{meta.label}</Tag>
      }
    },
    { title: '输入比例（积分/百万token）', dataIndex: 'inputCreditRate', width: 130, render: (v, r) => (r.type === 'image' || r.type === 'video' || r.type === 'audio') ? '-' : (v || 0) },
    { title: '输出比例（积分/百万token）', dataIndex: 'outputCreditRate', width: 130, render: (v, r) => (r.type === 'image' || r.type === 'video' || r.type === 'audio') ? '-' : (v || 0) },
    { title: '缓存比例（积分/百万token）', dataIndex: 'cacheCreditRate', width: 130, render: (v, r) => (r.type === 'image' || r.type === 'video' || r.type === 'audio') ? '-' : (v != null ? v : '0') },
    {
      title: '档位价格（积分）', width: 220,
      render: (_, r) => {
        if (r.type === 'image') {
          return `1K: ${r.imagePrice1k ?? 0} / 2K: ${r.imagePrice2k ?? 0} / 4K: ${r.imagePrice4k ?? 0}`
        }
        if (r.type === 'video') {
          return `480P: ${r.videoPrice480p ?? 0} / 720P: ${r.videoPrice720p ?? 0} / 1080P: ${r.videoPrice1080p ?? 0} / 4K: ${r.videoPrice4k ?? 0}`
        }
        if (r.type === 'audio') {
          return `标准: ${r.audioPriceStandard ?? 0} / 高清: ${r.audioPriceHd ?? 0}`
        }
        return '-'
      }
    },
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
      title: '操作', width: 160, fixed: 'right', render: (_, record) => (
        <Space size="small" wrap>
          <Button size="small" icon={<EditOutlined />} onClick={() => {
            setEditing(record)
            form.setFieldsValue(record)
            setModalOpen(true)
          }}>编辑</Button>
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

      <Table columns={columns} dataSource={models} rowKey="id" loading={loading} scroll={{ x: 1400 }} />

      {/* 单个新增/编辑 */}
      <Modal title={editing ? '编辑模型' : '新增模型'} open={modalOpen} onOk={handleSave} onCancel={() => setModalOpen(false)} width={500}>
        <Form form={form} layout="vertical">
          <Form.Item name="name" label="模型名称" rules={[{ required: true, message: '请输入模型名称' }]}>
            <Input placeholder="例如: gpt-4o" disabled={!!editing} />
          </Form.Item>
          <Form.Item name="displayName" label="显示名称">
            <Input placeholder="例如: GPT-4o（用于 Token 管理展示）" />
          </Form.Item>
          <Form.Item name="type" label="模型类型" initialValue="text">
            <Select options={[
              { value: 'text', label: '文本（按 token 计费）' },
              { value: 'image', label: '图片（按分辨率档位计费）' },
              { value: 'video', label: '视频（按分辨率档位计费）' },
              { value: 'audio', label: '音频（按音质档位计费）' },
            ]} />
          </Form.Item>
          {modelType === 'text' && <>
            <Form.Item name="inputCreditRate" label="输入积分比例（每百万token）" initialValue={0}>
              <InputNumber min={0} style={{ width: '100%' }} placeholder="每 百万 输入 token 消耗的积分数" />
            </Form.Item>
            <Form.Item name="outputCreditRate" label="输出积分比例（每百万token）" initialValue={0}>
              <InputNumber min={0} style={{ width: '100%' }} placeholder="每 百万 输出 token 消耗的积分数" />
            </Form.Item>
            <Form.Item name="cacheCreditRate" label="缓存比例（积分/百万token）" initialValue={0}>
              <InputNumber min={0} step={100} style={{ width: '100%' }} placeholder="每 百万 缓存 token 消耗的积分数" />
            </Form.Item>
          </>}
          {modelType === 'image' && <>
            <Form.Item name="imagePrice1k" label="1K 档价格（积分/张，最长边 < 2048）" initialValue={0}>
              <InputNumber min={0} style={{ width: '100%' }} placeholder="例如 1024x1024、1024x1536" />
            </Form.Item>
            <Form.Item name="imagePrice2k" label="2K 档价格（积分/张，最长边 < 4096）" initialValue={0}>
              <InputNumber min={0} style={{ width: '100%' }} placeholder="例如 2048x2048" />
            </Form.Item>
            <Form.Item name="imagePrice4k" label="4K 档价格（积分/张，最长边 ≥ 4096）" initialValue={0}>
              <InputNumber min={0} style={{ width: '100%' }} placeholder="例如 4096x4096" />
            </Form.Item>
          </>}
          {modelType === 'video' && <>
            <Form.Item name="videoPrice480p" label="480P 档价格（积分/秒）" initialValue={0}>
              <InputNumber min={0} style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item name="videoPrice720p" label="720P 档价格（积分/秒）" initialValue={0}>
              <InputNumber min={0} style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item name="videoPrice1080p" label="1080P 档价格（积分/秒）" initialValue={0}>
              <InputNumber min={0} style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item name="videoPrice4k" label="4K 档价格（积分/秒）" initialValue={0}>
              <InputNumber min={0} style={{ width: '100%' }} />
            </Form.Item>
          </>}
          {modelType === 'audio' && <>
            <Form.Item name="audioPriceStandard" label="标准档价格（积分/秒）" initialValue={0}>
              <InputNumber min={0} style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item name="audioPriceHd" label="高清档价格（积分/秒）" initialValue={0}>
              <InputNumber min={0} style={{ width: '100%' }} />
            </Form.Item>
          </>}
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

    </div>
  )
}
