import React, { useState, useEffect, useRef } from 'react'
import { Table, Button, Modal, Form, InputNumber, Select, Space, Switch, Popconfirm, message, Radio, Typography, Grid } from 'antd'
import { PlusOutlined, DeleteOutlined, EditOutlined } from '@ant-design/icons'
import {
  getFailureStrategies, createFailureStrategy, updateFailureStrategy, deleteFailureStrategy, updateFailureStrategyStatus,
  getChannels, getEnabledModels
} from '../api'

const SCOPE_OPTIONS = [
  { value: 'GLOBAL', label: '全局' },
  { value: 'CHANNEL', label: '渠道级' },
]

const WINDOW_TYPE_OPTIONS = [
  { value: 'SLIDING', label: '滑动窗口' },
  { value: 'FIXED', label: '固定窗口' },
]

const WINDOW_DIMENSION_OPTIONS = [
  { value: 'MINUTE', label: '每分钟' },
  { value: 'HOUR', label: '每小时' },
  { value: 'DAY', label: '每天' },
]

const SCOPE_LABEL = { GLOBAL: '全局', CHANNEL: '渠道级' }
const WINDOW_TYPE_LABEL = { SLIDING: '滑动窗口', FIXED: '固定窗口' }
const WINDOW_DIM_LABEL = { MINUTE: '每分钟', HOUR: '每小时', DAY: '每天' }

export default function FailureStrategies() {
  const [strategies, setStrategies] = useState([])
  const [channels, setChannels] = useState([])
  const [models, setModels] = useState([])
  const [loading, setLoading] = useState(false)
  const [modalOpen, setModalOpen] = useState(false)
  const [saveLoading, setSaveLoading] = useState(false)
  const saveLockRef = useRef(false)
  const [editing, setEditing] = useState(null)
  const [form] = Form.useForm()
  const screens = Grid.useBreakpoint()
  const isMobile = !screens.md

  const scopeValue = Form.useWatch('scope', form)

  useEffect(() => {
    loadStrategies()
    loadChannels()
    loadModels()
  }, [])

  const loadStrategies = () => {
    setLoading(true)
    getFailureStrategies().then(res => {
      if (res.code === 200) setStrategies(res.data || [])
    }).finally(() => setLoading(false))
  }

  const loadChannels = () => {
    getChannels().then(res => {
      if (res.code === 200) setChannels(res.data || [])
    })
  }

  const loadModels = () => {
    getEnabledModels().then(res => {
      if (res.code === 200) setModels(res.data || [])
    })
  }

  const handleSave = async () => {
    if (saveLockRef.current) return
    saveLockRef.current = true
    setSaveLoading(true)
    try {
      const values = await form.validateFields()
      if (values.scope === 'GLOBAL') {
        values.channelId = null
        values.modelConfigId = null
      }
      if (editing) {
        await updateFailureStrategy(editing.id, values)
        message.success('更新成功')
      } else {
        await createFailureStrategy(values)
        message.success('创建成功')
      }
      setModalOpen(false)
      form.resetFields()
      setEditing(null)
      loadStrategies()
    } catch (err) {
      if (!err?.errorFields) message.error(err?.message || '保存失败')
    } finally {
      saveLockRef.current = false
      setSaveLoading(false)
    }
  }

  const handleDelete = async (id) => {
    try {
      await deleteFailureStrategy(id)
      message.success('删除成功')
      loadStrategies()
    } catch (err) {
      message.error(err?.message || '删除失败')
    }
  }

  const handleStatusChange = async (id, checked) => {
    try {
      await updateFailureStrategyStatus(id, checked)
      message.success(checked ? '已启用' : '已禁用')
      loadStrategies()
    } catch (err) {
      message.error(err?.message || '操作失败')
    }
  }

  const getChannelName = (channelId) => {
    if (!channelId) return '-'
    const ch = channels.find(c => c.id === channelId)
    return ch ? ch.name : `#${channelId}`
  }

  const getModelName = (modelConfigId) => {
    if (!modelConfigId) return '全部模型'
    const m = models.find(m => m.id === modelConfigId)
    return m ? (m.displayName || m.name) : `#${modelConfigId}`
  }

  const columns = [
    { title: '优先级', dataIndex: 'priority', width: 70, sorter: (a, b) => a.priority - b.priority },
    { title: '范围', dataIndex: 'scope', width: 80,
      render: (v) => SCOPE_LABEL[v] || v },
    { title: '渠道', dataIndex: 'channelId', width: 120,
      render: (v) => getChannelName(v) },
    { title: '模型', dataIndex: 'modelConfigId', width: 120,
      render: (v) => getModelName(v) },
    { title: '失败状态码', dataIndex: 'httpCodes', width: 120 },
    { title: '窗口', width: 140,
      render: (_, r) => `${WINDOW_DIM_LABEL[r.windowDimension] || r.windowDimension} / ${WINDOW_TYPE_LABEL[r.windowType] || r.windowType}` },
    { title: '阈值', dataIndex: 'failureThreshold', width: 70 },
    { title: '熔断秒数', dataIndex: 'fuseDurationSeconds', width: 90 },
    { title: '状态', dataIndex: 'enabled', width: 80,
      render: (v, r) => <Switch checked={v} onChange={(c) => handleStatusChange(r.id, c)} /> },
    { title: '操作', width: 150,
      render: (_, r) => (
        <Space>
          <Button size="small" icon={<EditOutlined />} onClick={() => {
            setEditing(r)
            form.resetFields()
            form.setFieldsValue(r)
            setModalOpen(true)
          }}>编辑</Button>
          <Popconfirm title="确定删除？" onConfirm={() => handleDelete(r.id)}>
            <Button size="small" danger icon={<DeleteOutlined />}>删除</Button>
          </Popconfirm>
        </Space>
      ) },
  ]

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }} className="mobile-wrap-toolbar">
        <Typography.Title level={4} style={{ margin: 0 }}>失败策略</Typography.Title>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => {
          setEditing(null)
          form.resetFields()
          form.setFieldsValue({ scope: 'GLOBAL', windowType: 'SLIDING', windowDimension: 'MINUTE', priority: 0, enabled: true })
          setModalOpen(true)
        }}>新增策略</Button>
      </div>

      <Table
        dataSource={strategies}
        columns={columns}
        rowKey="id"
        loading={loading}
        pagination={false}
        size="small"
        scroll={{ x: 1100 }}
      />

      <Modal
        title={editing ? '编辑失败策略' : '新增失败策略'}
        open={modalOpen}
        onCancel={() => { setModalOpen(false); setEditing(null) }}
        onOk={handleSave}
        confirmLoading={saveLoading}
        okButtonProps={{ disabled: saveLoading }}
        width={560}
      >
        <Form form={form} layout="vertical" style={{ marginTop: 16 }}>
          <Form.Item name="scope" label="范围" rules={[{ required: true }]}>
            <Radio.Group options={SCOPE_OPTIONS} optionType="button" buttonStyle="solid" />
          </Form.Item>
          {scopeValue === 'CHANNEL' && (
            <Form.Item name="channelId" label="渠道" rules={[{ required: true, message: '请选择渠道' }]}>
              <Select
                placeholder="选择渠道"
                showSearch
                optionFilterProp="label"
                options={channels.map(c => ({ value: c.id, label: `${c.name} (${c.type})` }))}
              />
            </Form.Item>
          )}
          <Form.Item name="modelConfigId" label="模型（可选，不选则匹配该范围下所有模型）">
            <Select
              placeholder="不选 = 匹配所有模型"
              allowClear
              showSearch
              optionFilterProp="label"
              options={models.map(m => ({ value: m.id, label: m.displayName || m.name }))}
            />
          </Form.Item>
          <Form.Item name="httpCodes" label="失败状态码" rules={[{ required: true, message: '请输入失败状态码' }]}
            extra="支持末位 x 通配，如 5xx,429 表示 500-599 和 429">
            <Input placeholder="例如: 5xx,429" />
          </Form.Item>
          <Space style={{ width: '100%' }} direction="vertical">
            <Form.Item name="windowType" label="窗口类型" initialValue="SLIDING" rules={[{ required: true }]}>
              <Radio.Group options={WINDOW_TYPE_OPTIONS} />
            </Form.Item>
            <Form.Item name="windowDimension" label="窗口维度" rules={[{ required: true, message: '请选择窗口维度' }]}>
              <Select placeholder="选择窗口维度" options={WINDOW_DIMENSION_OPTIONS} />
            </Form.Item>
          </Space>
          <Space style={{ width: '100%' }} direction="vertical">
            <Form.Item name="failureThreshold" label="失败阈值（次数）" rules={[{ required: true, message: '请输入失败阈值' }]}>
              <InputNumber min={1} placeholder="窗口内失败次数达到此值触发熔断" style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item name="fuseDurationSeconds" label="熔断时长（秒）" rules={[{ required: true, message: '请输入熔断时长' }]}>
              <InputNumber min={10} placeholder="触发熔断后持续多少秒" style={{ width: '100%' }} />
            </Form.Item>
          </Space>
          <Space style={{ width: '100%' }} direction="vertical">
            <Form.Item name="priority" label="优先级（数字越小越优先）" initialValue={0} rules={[{ required: true }]}>
              <InputNumber min={0} style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item name="enabled" label="启用" valuePropName="checked" initialValue={true}>
              <Switch />
            </Form.Item>
          </Space>
        </Form>
      </Modal>
    </div>
  )
}
