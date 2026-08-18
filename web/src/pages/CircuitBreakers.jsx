import React, { useState, useEffect, useRef } from 'react'
import { Table, Button, Modal, Form, InputNumber, Select, Space, Popconfirm, message, Tag, Input, Typography, Grid } from 'antd'
import { PlusOutlined, UnlockOutlined } from '@ant-design/icons'
import {
  getCircuitBreakerRecords, releaseCircuitBreaker, createManualCircuitBreaker,
  getChannels, getEnabledModels
} from '../api'
import dayjs from 'dayjs'

const RECORD_STATUS_LABEL = { ACTIVE: '熔断中', EXPIRED: '已过期', MANUAL_RELEASED: '手动解除' }
const RECORD_STATUS_COLOR = { ACTIVE: 'red', EXPIRED: 'default', MANUAL_RELEASED: 'green' }
const SOURCE_LABEL = { AUTO_RATE: '自动限速', AUTO_FAILURE: '自动失败', MANUAL: '手动' }
const SOURCE_COLOR = { AUTO_RATE: 'orange', AUTO_FAILURE: 'red', MANUAL: 'blue' }

export default function CircuitBreakers() {
  const [records, setRecords] = useState([])
  const [channels, setChannels] = useState([])
  const [models, setModels] = useState([])
  const [loading, setLoading] = useState(false)
  const [modalOpen, setModalOpen] = useState(false)
  const [saveLoading, setSaveLoading] = useState(false)
  const saveLockRef = useRef(false)
  const [form] = Form.useForm()
  const screens = Grid.useBreakpoint()
  const isMobile = !screens.md

  useEffect(() => {
    loadRecords()
    loadChannels()
    loadModels()
    const interval = setInterval(loadRecords, 15000)
    return () => clearInterval(interval)
  }, [])

  const loadRecords = () => {
    setLoading(true)
    getCircuitBreakerRecords().then(res => {
      if (res.code === 200) setRecords(res.data || [])
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

  const handleRelease = async (id) => {
    try {
      await releaseCircuitBreaker(id)
      message.success('已解除熔断')
      loadRecords()
    } catch (err) {
      message.error(err?.message || '操作失败')
    }
  }

  const handleManualAdd = async () => {
    if (saveLockRef.current) return
    saveLockRef.current = true
    setSaveLoading(true)
    try {
      const values = await form.validateFields()
      await createManualCircuitBreaker(values)
      message.success('手动熔断已添加')
      setModalOpen(false)
      form.resetFields()
      loadRecords()
    } catch (err) {
      if (!err?.errorFields) message.error(err?.message || '添加失败')
    } finally {
      saveLockRef.current = false
      setSaveLoading(false)
    }
  }

  const getChannelName = (channelId) => {
    const ch = channels.find(c => c.id === channelId)
    return ch ? ch.name : `#${channelId}`
  }

  const columns = [
    { title: '来源', dataIndex: 'source', width: 100,
      render: (v) => <Tag color={SOURCE_COLOR[v] || 'default'}>{SOURCE_LABEL[v] || v}</Tag> },
    { title: '渠道', dataIndex: 'channelId', width: 150,
      render: (v) => getChannelName(v) },
    { title: '模型', dataIndex: 'modelConfigName', width: 150,
      render: (v) => v || '渠道级' },
    { title: '触发时间', dataIndex: 'triggeredAt', width: 180,
      render: (v) => v ? dayjs(v).format('YYYY-MM-DD HH:mm:ss') : '-' },
    { title: '到期时间', dataIndex: 'expiresAt', width: 180,
      render: (v) => v ? dayjs(v).format('YYYY-MM-DD HH:mm:ss') : '-' },
    { title: '状态', dataIndex: 'status', width: 100,
      render: (v) => <Tag color={RECORD_STATUS_COLOR[v] || 'default'}>{RECORD_STATUS_LABEL[v] || v}</Tag> },
    { title: '备注', dataIndex: 'reason', width: 150, ellipsis: true,
      render: (v) => v || '-' },
    { title: '操作', width: 100, fixed: 'right',
      render: (_, r) => r.status === 'ACTIVE' ? (
        <Popconfirm title="确定解除熔断？" onConfirm={() => handleRelease(r.id)}>
          <Button size="small" icon={<UnlockOutlined />}>解除</Button>
        </Popconfirm>
      ) : null },
  ]

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }} className="mobile-wrap-toolbar">
        <Typography.Title level={4} style={{ margin: 0 }}>熔断记录</Typography.Title>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => {
          form.resetFields()
          form.setFieldsValue({ durationSeconds: 300 })
          setModalOpen(true)
        }}>手动添加</Button>
      </div>

      <Table
        dataSource={records}
        columns={columns}
        rowKey="id"
        loading={loading}
        pagination={{ pageSize: 10, showSizeChanger: true, showTotal: (t) => `共 ${t} 条` }}
        size="small"
        scroll={{ x: 1100 }}
      />

      <Modal
        title="手动添加熔断"
        open={modalOpen}
        onCancel={() => setModalOpen(false)}
        onOk={handleManualAdd}
        confirmLoading={saveLoading}
        okButtonProps={{ disabled: saveLoading }}
        width={480}
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
          <Form.Item name="modelConfigName" label="模型（可选，不选则熔断整个渠道）">
            <Select
              placeholder="不选 = 渠道级熔断"
              allowClear
              showSearch
              optionFilterProp="label"
              options={models.map(m => ({ value: m.name, label: m.displayName || m.name }))}
            />
          </Form.Item>
          <Form.Item name="durationSeconds" label="熔断时长（秒）" initialValue={300} rules={[{ required: true, message: '请输入熔断时长' }]}>
            <InputNumber min={10} placeholder="熔断持续多少秒" style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="reason" label="备注">
            <Input.TextArea rows={2} placeholder="手动熔断原因（可选）" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  )
}
