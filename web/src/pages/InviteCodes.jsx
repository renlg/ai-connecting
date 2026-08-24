import React, { useEffect, useRef, useState } from 'react'
import { Button, DatePicker, Form, InputNumber, message, Modal, Popconfirm, Space, Switch, Table, Tag, Typography } from 'antd'
import { CopyOutlined, DeleteOutlined, PlusOutlined } from '@ant-design/icons'
import dayjs from 'dayjs'
import { deleteInviteCode, generateInviteCodes, getInviteCodes, updateInviteCodeStatus } from '../api'

const { Text } = Typography

export default function InviteCodes() {
  const [codes, setCodes] = useState([])
  const [loading, setLoading] = useState(false)
  const [modalOpen, setModalOpen] = useState(false)
  const [generateLoading, setGenerateLoading] = useState(false)
  const [generatedCodes, setGeneratedCodes] = useState([])
  const [form] = Form.useForm()
  const generateLockRef = useRef(false)

  const load = async () => {
    setLoading(true)
    try {
      const res = await getInviteCodes()
      if (res.code === 200) setCodes(res.data || [])
    } catch (err) {
      message.error(err?.message || '加载邀请码失败')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => { load() }, [])

  const closeModal = () => {
    setModalOpen(false)
    setGeneratedCodes([])
    form.resetFields()
  }

  const handleGenerate = async () => {
    if (generateLockRef.current) return
    generateLockRef.current = true
    setGenerateLoading(true)
    try {
      const values = await form.validateFields()
      const res = await generateInviteCodes({
        count: values.count,
        maxUses: values.maxUses,
        expiryDate: values.expiryDate ? values.expiryDate.format('YYYY-MM-DDTHH:mm:ss') : null,
      })
      if (res.code === 200) {
        setGeneratedCodes(res.data || [])
        message.success(`成功生成 ${res.data?.length || 0} 个邀请码`)
        load()
      } else {
        message.error(res.message || '生成失败')
      }
    } catch (err) {
      if (!err?.errorFields) message.error(err?.message || '生成失败')
    } finally {
      generateLockRef.current = false
      setGenerateLoading(false)
    }
  }

  const handleStatusChange = async (record, checked) => {
    try {
      await updateInviteCodeStatus(record.id, checked ? 1 : 0)
      message.success(checked ? '已启用' : '已禁用')
      load()
    } catch (err) {
      message.error(err?.message || '状态更新失败')
    }
  }

  const handleDelete = async (id) => {
    try {
      await deleteInviteCode(id)
      message.success('邀请码已删除')
      load()
    } catch (err) {
      message.error(err?.message || '删除失败')
    }
  }

  const copyCode = async (code) => {
    try {
      await navigator.clipboard.writeText(code)
      message.success('邀请码已复制')
    } catch {
      message.error('复制失败，请手动复制')
    }
  }

  const statusTag = (record) => {
    if (record.status !== 1) return <Tag>已禁用</Tag>
    if (record.expiryDate && !dayjs(record.expiryDate).isAfter(dayjs())) return <Tag color="orange">已过期</Tag>
    if (record.usedCount >= record.maxUses) return <Tag color="red">已用尽</Tag>
    return <Tag color="green">已启用</Tag>
  }

  const columns = [
    {
      title: '邀请码', dataIndex: 'code', width: 190,
      render: value => <Space><Text code copyable={false}>{value}</Text><Button type="text" size="small" icon={<CopyOutlined />} onClick={() => copyCode(value)} /></Space>,
    },
    { title: '创建时间', dataIndex: 'createdAt', width: 170, render: value => value ? dayjs(value).format('YYYY-MM-DD HH:mm:ss') : '-' },
    { title: '过期时间', dataIndex: 'expiryDate', width: 170, render: value => value ? dayjs(value).format('YYYY-MM-DD HH:mm:ss') : <Tag color="green">永不过期</Tag> },
    { title: '最大次数', dataIndex: 'maxUses', width: 100 },
    { title: '已用次数', dataIndex: 'usedCount', width: 100 },
    { title: '状态', width: 90, render: (_, record) => statusTag(record) },
    {
      title: '启用', width: 90,
      render: (_, record) => <Switch checked={record.status === 1} onChange={checked => handleStatusChange(record, checked)} />,
    },
    {
      title: '操作', width: 90, fixed: 'right',
      render: (_, record) => (
        <Popconfirm title="确认删除该邀请码？" description="删除后无法恢复，尚未使用的次数也会失效。" onConfirm={() => handleDelete(record.id)} okText="删除" cancelText="取消">
          <Button type="link" danger icon={<DeleteOutlined />}>删除</Button>
        </Popconfirm>
      ),
    },
  ]

  return (
    <div>
      <div className="mobile-wrap-toolbar" style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
        <h2>邀请码管理</h2>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setModalOpen(true)}>生成邀请码</Button>
      </div>
      <Table columns={columns} dataSource={codes} rowKey="id" loading={loading} scroll={{ x: 1100 }} />

      <Modal
        title="生成邀请码"
        open={modalOpen}
        onOk={generatedCodes.length ? closeModal : handleGenerate}
        onCancel={closeModal}
        confirmLoading={!generatedCodes.length && generateLoading}
        okButtonProps={{ disabled: !generatedCodes.length && generateLoading }}
        okText={generatedCodes.length ? '关闭' : '生成'}
        cancelText="取消"
        width={520}
      >
        {generatedCodes.length ? (
          <div>
            <p style={{ marginBottom: 12 }}>已生成以下邀请码：</p>
            <Space direction="vertical" style={{ width: '100%', maxHeight: 320, overflowY: 'auto' }}>
              {generatedCodes.map(item => (
                <div key={item.id} style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', background: '#f5f5f5', borderRadius: 6, padding: '10px 14px' }}>
                  <Text strong style={{ fontFamily: 'monospace', fontSize: 18, letterSpacing: 2 }}>{item.code}</Text>
                  <Button type="text" icon={<CopyOutlined />} onClick={() => copyCode(item.code)}>复制</Button>
                </div>
              ))}
            </Space>
          </div>
        ) : (
          <Form form={form} layout="vertical" initialValues={{ count: 1, maxUses: 1 }}>
            <Form.Item name="count" label="生成数量" rules={[{ required: true, message: '请输入生成数量' }]}>
              <InputNumber style={{ width: '100%' }} min={1} max={100} precision={0} />
            </Form.Item>
            <Form.Item name="maxUses" label="每个邀请码可使用次数" rules={[{ required: true, message: '请输入使用次数' }]}>
              <InputNumber style={{ width: '100%' }} min={1} max={1000000} precision={0} />
            </Form.Item>
            <Form.Item name="expiryDate" label="过期时间">
              <DatePicker showTime style={{ width: '100%' }} placeholder="留空表示永不过期" disabledDate={current => current && current < dayjs().startOf('day')} />
            </Form.Item>
          </Form>
        )}
      </Modal>
    </div>
  )
}
