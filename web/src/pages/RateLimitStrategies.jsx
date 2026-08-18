import React, { useState, useEffect, useRef } from 'react'
import { Table, Button, Modal, Form, InputNumber, Select, Space, Switch, Popconfirm, message, Radio, Typography, Grid } from 'antd'
import { PlusOutlined, DeleteOutlined, EditOutlined } from '@ant-design/icons'
import {
  getRiskPolicies, createRiskPolicy, updateRiskPolicy, deleteRiskPolicy, updateRiskPolicyStatus,
  getChannels, getEnabledModels
} from '../api'

const TIME_WINDOW_OPTIONS = [
  { value: 'MINUTE', label: '每分钟' },
  { value: 'HOUR', label: '每小时' },
  { value: 'DAY', label: '每天' },
]

const TIME_WINDOW_LABEL = { MINUTE: '每分钟', HOUR: '每小时', DAY: '每天' }
const WINDOW_TYPE_LABEL = { SLIDING: '滑动窗口', FIXED: '固定窗口' }

export default function RateLimitStrategies() {
  const [policies, setPolicies] = useState([])
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

  useEffect(() => {
    loadPolicies()
    loadChannels()
    loadModels()
  }, [])

  const loadPolicies = () => {
    setLoading(true)
    getRiskPolicies().then(res => {
      if (res.code === 200) setPolicies(res.data || [])
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
      if (editing) {
        await updateRiskPolicy(editing.id, values)
        message.success('更新成功')
      } else {
        await createRiskPolicy(values)
        message.success('创建成功')
      }
      setModalOpen(false)
      form.resetFields()
      setEditing(null)
      loadPolicies()
    } catch (err) {
      if (!err?.errorFields) message.error(err?.message || '保存失败')
    } finally {
      saveLockRef.current = false
      setSaveLoading(false)
    }
  }

  const handleDelete = async (id) => {
    try {
      await deleteRiskPolicy(id)
      message.success('删除成功')
      loadPolicies()
    } catch (err) {
      message.error(err?.message || '删除失败')
    }
  }

  const handleStatusChange = async (id, checked) => {
    try {
      await updateRiskPolicyStatus(id, checked ? 1 : 0)
      message.success(checked ? '已启用' : '已禁用')
      loadPolicies()
    } catch (err) {
      message.error(err?.message || '操作失败')
    }
  }

  const getChannelName = (channelId) => {
    const ch = channels.find(c => c.id === channelId)
    return ch ? ch.name : `#${channelId}`
  }

  const columns = [
    { title: '渠道', dataIndex: 'channelId', width: 150,
      render: (v) => getChannelName(v) },
    { title: '模型', dataIndex: 'modelConfigName', width: 150,
      render: (v) => v || '全部模型' },
    { title: '速率限制', dataIndex: 'rateLimit', width: 120,
      render: (v, r) => `${v} 次/${TIME_WINDOW_LABEL[r.timeWindow] || r.timeWindow}` },
    { title: '窗口类型', dataIndex: 'windowType', width: 100,
      render: (v) => WINDOW_TYPE_LABEL[v] || WINDOW_TYPE_LABEL.SLIDING },
    { title: '熔断时长', dataIndex: 'circuitBreakerDuration', width: 100,
      render: (v) => `${v} 秒` },
    { title: '状态', dataIndex: 'status', width: 80,
      render: (v, r) => <Switch checked={v === 1} onChange={(c) => handleStatusChange(r.id, c)} /> },
    { title: '操作', width: 150,
      render: (_, r) => (
        <Space>
          <Button size="small" icon={<EditOutlined />} onClick={() => {
            setEditing(r)
            form.resetFields()
            form.setFieldsValue({ ...r, windowType: r.windowType || 'SLIDING' })
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
        <Typography.Title level={4} style={{ margin: 0 }}>限速策略</Typography.Title>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => {
          setEditing(null)
          form.resetFields()
          form.setFieldsValue({ windowType: 'SLIDING', circuitBreakerDuration: 300 })
          setModalOpen(true)
        }}>新增策略</Button>
      </div>

      <Table
        dataSource={policies}
        columns={columns}
        rowKey="id"
        loading={loading}
        pagination={false}
        size="small"
        scroll={{ x: 800 }}
      />

      <Modal
        title={editing ? '编辑策略' : '新增策略'}
        open={modalOpen}
        onCancel={() => { setModalOpen(false); setEditing(null) }}
        onOk={handleSave}
        confirmLoading={saveLoading}
        okButtonProps={{ disabled: saveLoading }}
        width={520}
      >
        <Form form={form} layout="vertical" style={{ marginTop: 16 }}>
          <Form.Item name="channelId" label="渠道" rules={[{ required: true, message: '请选择渠道' }]}>
            <Select
              placeholder="选择渠道"
              showSearch
              optionFilterProp="label"
              options={channels.map(c => ({ value: c.id, label: `${c.name} (${c.type})` }))}
            />
          </Form.Item>
          <Form.Item name="modelConfigName" label="模型（可选，不选则针对整个渠道）">
            <Select
              placeholder="不选 = 针对整个渠道所有模型"
              allowClear
              showSearch
              optionFilterProp="label"
              options={models.map(m => ({ value: m.name, label: m.displayName || m.name }))}
            />
          </Form.Item>
          <Form.Item name="rateLimit" label="速率限制值" rules={[{ required: true, message: '请输入速率限制值' }]}>
            <InputNumber min={1} placeholder="窗口内最大请求数" style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="timeWindow" label="时间维度" rules={[{ required: true, message: '请选择时间维度' }]}>
            <Select placeholder="选择时间维度" options={TIME_WINDOW_OPTIONS} />
          </Form.Item>
          <Form.Item name="windowType" label="窗口类型" initialValue="SLIDING" rules={[{ required: true }]}>
            <Radio.Group>
              <Radio value="SLIDING">滑动窗口</Radio>
              <Radio value="FIXED">固定窗口</Radio>
            </Radio.Group>
          </Form.Item>
          <Form.Item name="circuitBreakerDuration" label="熔断时长（秒）" initialValue={300} rules={[{ required: true, message: '请输入熔断时长' }]}>
            <InputNumber min={10} placeholder="触发熔断后持续多少秒" style={{ width: '100%' }} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  )
}
