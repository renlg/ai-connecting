import React, { useEffect, useState } from 'react'
import { Table, Button, Modal, Form, Input, InputNumber, Select, Space, Tag, Tooltip, message, Popconfirm, Switch } from 'antd'
import { PlusOutlined, DeleteOutlined, EditOutlined, SearchOutlined } from '@ant-design/icons'
import {
  getModels, createModel, updateModel, deleteModel, updateModelStatus,
  getModelGroups,
} from '../api'

const TYPE_META = {
  text: { color: 'default', label: '文本' },
  image: { color: 'purple', label: '图片' },
  video: { color: 'orange', label: '视频' },
  audio: { color: 'cyan', label: '音频' },
}

export default function Models() {
  const [models, setModels] = useState([])
  const [allModels, setAllModels] = useState([])
  const [fallbackGroups, setFallbackGroups] = useState([])
  const [loading, setLoading] = useState(false)
  const [modalOpen, setModalOpen] = useState(false)
  const [editing, setEditing] = useState(null)
  const [searchName, setSearchName] = useState('')
  const [searchType, setSearchType] = useState(undefined)
  const [searchStatus, setSearchStatus] = useState('all')
  const [form] = Form.useForm()
  const modelType = Form.useWatch('type', form) || 'text'

  const load = (filters) => {
    setLoading(true)
    const params = {}
    if (filters?.name) params.name = filters.name
    if (filters?.type) params.type = filters.type
    getModels(params).then(res => {
      if (res.code === 200) setModels(res.data || [])
    }).finally(() => setLoading(false))
  }

  const loadAllModels = () => {
    getModels().then(res => {
      if (res.code === 200) setAllModels(res.data || [])
    })
  }

  const loadFallbackGroups = () => {
    getModelGroups().then(res => {
      if (res.code === 200) {
        setFallbackGroups((res.data || []).map(item => item.group))
      }
    }).catch(err => message.error(err?.message || '故障转移组加载失败'))
  }

  useEffect(() => {
    load()
    loadAllModels()
    loadFallbackGroups()
  }, [])

  const refreshModels = () => {
    load({ name: searchName || undefined, type: searchType })
    loadAllModels()
  }

  const handleSave = async () => {
    const values = await form.validateFields()
    const payload = {
      ...values,
      fallbackGroupId: values.fallbackGroupId || (editing ? 0 : null),
    }
    try {
      if (editing) {
        await updateModel(editing.id, payload)
        message.success('更新成功')
      } else {
        await createModel(payload)
        message.success('创建成功')
      }
    } catch (err) {
      message.error(err?.message || '保存失败')
      return
    }
    setModalOpen(false)
    form.resetFields()
    setEditing(null)
    refreshModels()
  }

  const handleDelete = async (id) => {
    try {
      await deleteModel(id)
      message.success('删除成功')
      refreshModels()
    } catch (err) {
      message.error(err?.message || '删除失败')
    }
  }

  const handleStatusChange = async (id, status) => {
    try {
      await updateModelStatus(id, status ? 1 : 0)
      message.success('状态已更新')
      refreshModels()
    } catch (err) {
      message.error(err?.message || '状态更新失败')
    }
  }

  const columns = [
    { title: 'ID', dataIndex: 'id', width: 60 },
    {
      title: '模型名称',
      dataIndex: 'name',
      width: 180,
      render: v => (
        <Tooltip title={v}>
          <Tag color="blue" style={{ maxWidth: '100%', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', verticalAlign: 'bottom' }}>
            {v}
          </Tag>
        </Tooltip>
      ),
    },
    { title: '显示名称', dataIndex: 'displayName', width: 150, render: v => v || '-' },
    {
      title: '类型', dataIndex: 'type', width: 80,
      render: v => {
        const meta = TYPE_META[v] || TYPE_META.text
        return <Tag color={meta.color}>{meta.label}</Tag>
      }
    },
    { title: '输入比例（积分/百万token）', dataIndex: 'inputCreditRate', width: 130, render: (v, r) => ['image', 'video', 'audio'].includes(r.type) ? '-' : (v || 0) },
    { title: '输出比例（积分/百万token）', dataIndex: 'outputCreditRate', width: 130, render: (v, r) => ['image', 'video', 'audio'].includes(r.type) ? '-' : (v || 0) },
    { title: '缓存比例（积分/百万token）', dataIndex: 'cacheCreditRate', width: 130, render: (v, r) => ['image', 'video', 'audio'].includes(r.type) ? '-' : (v ?? 0) },
    {
      title: '档位价格（积分）', width: 220,
      render: (_, r) => {
        if (r.type === 'image') return `1K: ${r.imagePrice1k ?? 0} / 2K: ${r.imagePrice2k ?? 0} / 4K: ${r.imagePrice4k ?? 0}`
        if (r.type === 'video') return `480P: ${r.videoPrice480p ?? 0} / 720P: ${r.videoPrice720p ?? 0} / 1080P: ${r.videoPrice1080p ?? 0} / 4K: ${r.videoPrice4k ?? 0}`
        if (r.type === 'audio') return `标准: ${r.audioPriceStandard ?? 0} / 高清: ${r.audioPriceHd ?? 0}`
        return '-'
      }
    },
    { title: '描述', dataIndex: 'description', ellipsis: true, render: v => v || '-' },
    {
      title: '仅管理员', dataIndex: 'adminOnly', width: 100,
      render: (v, r) => <Switch checked={!!v} onChange={(checked) => {
        updateModel(r.id, { adminOnly: checked }).then(() => {
          message.success('已更新')
          refreshModels()
        }).catch(err => message.error(err?.message || '更新失败'))
      }} />
    },
    { title: '状态', dataIndex: 'status', width: 80, render: (v, r) => <Switch checked={v === 1} onChange={checked => handleStatusChange(r.id, checked)} /> },
    { title: '创建时间', dataIndex: 'createdAt', width: 180, render: v => v ? new Date(v).toLocaleString('zh-CN') : '-' },
    {
      title: '操作', width: 160, fixed: 'right', render: (_, record) => (
        <Space size="small" wrap>
          <Button size="small" icon={<EditOutlined />} onClick={() => {
            setEditing(record)
            form.resetFields()
            const fallbackAvailable = fallbackGroups.some(group => group.id === record.fallbackGroupId && group.enabled && group.type === (record.type || 'text'))
            form.setFieldsValue({ ...record, fallbackGroupId: fallbackAvailable ? record.fallbackGroupId : 0 })
            setModalOpen(true)
          }}>编辑</Button>
          <Popconfirm title="确定删除该模型？" onConfirm={() => handleDelete(record.id)}>
            <Button size="small" danger icon={<DeleteOutlined />}>删除</Button>
          </Popconfirm>
        </Space>
      )
    },
  ]

  const fallbackGroupOptions = fallbackGroups
    .filter(group => group.enabled && group.type === modelType)
    .map(group => ({ value: group.id, label: group.name }))

  const filteredModels = searchStatus === 'all'
    ? models
    : models.filter(model => model.status === searchStatus)

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
        <h2>模型管理</h2>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => {
          setEditing(null)
          form.resetFields()
          form.setFieldsValue({ type: 'text', adminOnly: false, fallbackGroupId: 0 })
          setModalOpen(true)
        }}>新增模型</Button>
      </div>

      <Space style={{ marginBottom: 16 }} wrap>
        <Input.Search placeholder="按模型名称模糊搜索" allowClear onSearch={(value) => {
          setSearchName(value)
          load({ name: value || undefined, type: searchType })
        }} style={{ width: 240 }} prefix={<SearchOutlined />} />
        <Select placeholder="按模型类型搜索" allowClear value={searchType} onChange={(value) => {
          setSearchType(value)
          load({ name: searchName || undefined, type: value })
        }} options={Object.entries(TYPE_META).map(([value, meta]) => ({ value, label: meta.label }))} style={{ width: 180 }} />
        <Select value={searchStatus} onChange={setSearchStatus} options={[
          { value: 'all', label: '全部状态' },
          { value: 1, label: '仅启用' },
          { value: 0, label: '仅禁用' },
        ]} style={{ width: 140 }} />
      </Space>

      <Table columns={columns} dataSource={filteredModels} rowKey="id" loading={loading} scroll={{ x: 1400 }} />

      <Modal title={editing ? '编辑模型' : '新增模型'} open={modalOpen} onOk={handleSave} onCancel={() => setModalOpen(false)} width={520}>
        <Form form={form} layout="vertical">
          <Form.Item name="name" label="模型名称（模型ID，发送给上游供应商，可重复）" rules={[{ required: true, message: '请输入模型名称' }]}>
            <Input placeholder="例如: gpt-4o" />
          </Form.Item>
          <Form.Item name="displayName" label="显示名称（平台唯一标识，用于 Token 管理展示）" rules={[
            { required: true, message: '请输入显示名称' },
            { validator: (_, value) => {
              if (!value) return Promise.resolve()
              const conflict = allModels.some(model => model.displayName === value && model.id !== editing?.id)
              return conflict ? Promise.reject(new Error('显示名称已存在，请使用唯一的显示名称')) : Promise.resolve()
            } }
          ]}>
            <Input placeholder="例如: GPT-4o" />
          </Form.Item>
          <Form.Item name="type" label="模型类型" initialValue="text">
            <Select onChange={() => form.setFieldValue('fallbackGroupId', 0)} options={[
              { value: 'text', label: '文本（按 token 计费）' },
              { value: 'image', label: '图片（按分辨率档位计费）' },
              { value: 'video', label: '视频（按分辨率档位计费）' },
              { value: 'audio', label: '音频（按音质档位计费）' },
            ]} />
          </Form.Item>
          <Form.Item name="fallbackGroupId" label="故障转移组" initialValue={0} tooltip="模型请求失败后，可转入同类型模型组继续尝试。">
            <Select options={[{ value: 0, label: '不启用' }, ...fallbackGroupOptions]} />
          </Form.Item>
          {modelType === 'text' && <>
            <Form.Item name="inputCreditRate" label="输入积分比例（每百万token）" initialValue={0}><InputNumber min={0} style={{ width: '100%' }} /></Form.Item>
            <Form.Item name="outputCreditRate" label="输出积分比例（每百万token）" initialValue={0}><InputNumber min={0} style={{ width: '100%' }} /></Form.Item>
            <Form.Item name="cacheCreditRate" label="缓存比例（积分/百万token）" initialValue={0}><InputNumber min={0} style={{ width: '100%' }} /></Form.Item>
          </>}
          {modelType === 'image' && <>
            <Form.Item name="imagePrice1k" label="1K 档价格（积分/张）" initialValue={0}><InputNumber min={0} style={{ width: '100%' }} /></Form.Item>
            <Form.Item name="imagePrice2k" label="2K 档价格（积分/张）" initialValue={0}><InputNumber min={0} style={{ width: '100%' }} /></Form.Item>
            <Form.Item name="imagePrice4k" label="4K 档价格（积分/张）" initialValue={0}><InputNumber min={0} style={{ width: '100%' }} /></Form.Item>
          </>}
          {modelType === 'video' && <>
            <Form.Item name="videoPrice480p" label="480P 档价格（积分/秒）" initialValue={0}><InputNumber min={0} style={{ width: '100%' }} /></Form.Item>
            <Form.Item name="videoPrice720p" label="720P 档价格（积分/秒）" initialValue={0}><InputNumber min={0} style={{ width: '100%' }} /></Form.Item>
            <Form.Item name="videoPrice1080p" label="1080P 档价格（积分/秒）" initialValue={0}><InputNumber min={0} style={{ width: '100%' }} /></Form.Item>
            <Form.Item name="videoPrice4k" label="4K 档价格（积分/秒）" initialValue={0}><InputNumber min={0} style={{ width: '100%' }} /></Form.Item>
          </>}
          {modelType === 'audio' && <>
            <Form.Item name="audioPriceStandard" label="标准档价格（积分/秒）" initialValue={0}><InputNumber min={0} style={{ width: '100%' }} /></Form.Item>
            <Form.Item name="audioPriceHd" label="高清档价格（积分/秒）" initialValue={0}><InputNumber min={0} style={{ width: '100%' }} /></Form.Item>
          </>}
          <Form.Item name="description" label="描述"><Input placeholder="模型描述（可选）" /></Form.Item>
          <Form.Item name="adminOnly" label="仅管理员可选" valuePropName="checked" initialValue={false}><Switch /></Form.Item>
        </Form>
      </Modal>

    </div>
  )
}
