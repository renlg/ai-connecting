import React, { useEffect, useState } from 'react'
import { Table, Tag, Button, Modal, Form, InputNumber, DatePicker, message, Switch } from 'antd'
import { PlusOutlined, EyeOutlined } from '@ant-design/icons'
import { getCoupons, generateCoupon, updateCouponStatus, getCouponRedemptions } from '../api'
import dayjs from 'dayjs'

export default function Coupons() {
  const [coupons, setCoupons] = useState([])
  const [loading, setLoading] = useState(false)
  const [modalOpen, setModalOpen] = useState(false)
  const [form] = Form.useForm()
  const [generatedCode, setGeneratedCode] = useState(null)

  // 查看使用记录弹窗
  const [redemptionModalOpen, setRedemptionModalOpen] = useState(false)
  const [redemptionLoading, setRedemptionLoading] = useState(false)
  const [redemptionData, setRedemptionData] = useState([])
  const [currentCouponCode, setCurrentCouponCode] = useState('')

  const load = () => {
    setLoading(true)
    getCoupons().then(res => {
      if (res.code === 200) setCoupons(res.data || [])
    }).finally(() => setLoading(false))
  }

  useEffect(() => { load() }, [])

  const handleGenerate = async () => {
    const values = await form.validateFields()
    const data = {
      credits: values.credits,
      maxUses: values.maxUses || 1,
      expiryDate: values.expiryDate ? values.expiryDate.format('YYYY-MM-DDTHH:mm:ss') : null,
    }
    try {
      const res = await generateCoupon(data)
      if (res.code === 200) {
        setGeneratedCode(res.data.code)
        message.success('积分券生成成功')
        form.resetFields()
        load()
      } else {
        message.error(res.message || '生成失败')
      }
    } catch (err) {
      message.error(err?.message || '生成失败')
    }
  }

  const handleViewRedemptions = async (record) => {
    setCurrentCouponCode(record.code)
    setRedemptionModalOpen(true)
    setRedemptionLoading(true)
    setRedemptionData([])
    try {
      const res = await getCouponRedemptions(record.id)
      if (res.code === 200) {
        setRedemptionData(res.data || [])
      } else {
        message.error(res.message || '加载失败')
      }
    } catch (err) {
      message.error(err?.message || '加载失败')
    } finally {
      setRedemptionLoading(false)
    }
  }

  const handleToggleStatus = async (id, checked) => {
    const newStatus = checked ? 1 : 0
    try {
      const res = await updateCouponStatus(id, newStatus)
      if (res.code === 200) {
        message.success(checked ? '已启用' : '已禁用')
        load()
      } else {
        message.error(res.message || '操作失败')
      }
    } catch (err) {
      message.error(err?.message || '操作失败')
    }
  }

  const redemptionColumns = [
    { title: '用户名', dataIndex: 'username', key: 'username' },
    { title: '昵称', dataIndex: 'nickname', key: 'nickname', render: v => v || '-' },
    { title: '兑换时间', dataIndex: 'redeemedAt', key: 'redeemedAt', render: v => v ? dayjs(v).format('YYYY-MM-DD HH:mm:ss') : '-' },
    { title: '获得积分', dataIndex: 'credits', key: 'credits', render: v => v != null ? Math.round(Number(v)) + ' 积分' : '0 积分' },
  ]

  const columns = [
    { title: '兑换码', dataIndex: 'code', render: v => <span style={{ fontFamily: 'monospace', color: '#1890ff', fontSize: 18 }}>{v}</span> },
    { title: '积分额度', dataIndex: 'credits', render: v => v != null ? Math.round(Number(v)) + ' 积分' : '0 积分' },
    { title: '使用次数', render: (_, r) => `${r.usedCount || 0} / ${r.maxUses}` },
    { title: '过期时间', dataIndex: 'expiryDate', render: v => v ? dayjs(v).format('YYYY-MM-DD HH:mm') : <Tag color="green">永不过期</Tag> },
    {
      title: '状态',
      dataIndex: 'status',
      render: (v, r) => (
        <Switch
          checked={v === 1}
          checkedChildren="启用"
          unCheckedChildren="禁用"
          onChange={(checked) => handleToggleStatus(r.id, checked)}
        />
      ),
    },
    { title: '创建时间', dataIndex: 'createdAt', render: v => v ? dayjs(v).format('YYYY-MM-DD HH:mm') : '-' },
    {
      title: '操作',
      render: (_, r) => (
        <Button type="link" icon={<EyeOutlined />} onClick={() => handleViewRedemptions(r)}>
          查看
        </Button>
      ),
    },
  ]

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
        <h2>积分券管理</h2>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => { setGeneratedCode(null); setModalOpen(true) }}>
          生成积分券
        </Button>
      </div>
      <Table columns={columns} dataSource={coupons} rowKey="id" loading={loading} />

      {/* 生成积分券弹窗 */}
      <Modal
        title="生成积分券"
        open={modalOpen}
        onOk={generatedCode ? () => setModalOpen(false) : handleGenerate}
        onCancel={() => { setModalOpen(false); setGeneratedCode(null); form.resetFields() }}
        okText={generatedCode ? '关闭' : '生成'}
        cancelText="取消"
        width={400}
      >
        {generatedCode ? (
          <div style={{ textAlign: 'center', padding: '20px 0' }}>
            <p style={{ marginBottom: 8 }}>兑换码已生成：</p>
            <div style={{ fontSize: 28, fontWeight: 'bold', letterSpacing: 4, color: '#1890ff', fontFamily: 'monospace' }}>
              {generatedCode}
            </div>
            <p style={{ marginTop: 12, color: '#999' }}>请将此兑换码提供给用户</p>
          </div>
        ) : (
          <Form form={form} layout="vertical" initialValues={{ maxUses: 1 }}>
            <Form.Item name="credits" label="积分额度" rules={[{ required: true, message: '请输入积分额度' }]}>
              <InputNumber style={{ width: '100%' }} min={0.01} step={1} placeholder="输入积分额度" />
            </Form.Item>
            <Form.Item name="maxUses" label="使用次数" rules={[{ required: true, message: '请输入使用次数' }]}>
              <InputNumber style={{ width: '100%' }} min={1} step={1} placeholder="最大使用次数" />
            </Form.Item>
            <Form.Item name="expiryDate" label="过期时间">
              <DatePicker showTime style={{ width: '100%' }} placeholder="留空表示永不过期" />
            </Form.Item>
          </Form>
        )}
      </Modal>

      {/* 使用记录弹窗 */}
      <Modal
        title={`使用记录 - ${currentCouponCode}`}
        open={redemptionModalOpen}
        onCancel={() => setRedemptionModalOpen(false)}
        onOk={() => setRedemptionModalOpen(false)}
        okText="关闭"
        cancelText={null}
        width={800}
        footer={(_, { OkBtn }) => <OkBtn />}
      >
        <Table
          columns={redemptionColumns}
          dataSource={redemptionData}
          rowKey="userId"
          loading={redemptionLoading}
          pagination={{ pageSize: 10 }}
          locale={{ emptyText: '暂无使用记录' }}
        />
      </Modal>
    </div>
  )
}
