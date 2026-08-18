import React, { useEffect, useState } from 'react'
import { Button, Form, Input, InputNumber, Select, Space, Table, Tag, Tooltip, message } from 'antd'
import { SearchOutlined, ReloadOutlined } from '@ant-design/icons'
import dayjs from 'dayjs'
import { getChannelFailureLogs } from '../api'

function ErrorCell({ value }) {
  if (!value) return <span style={{ color: '#999' }}>—</span>
  return (
    <Tooltip title={value} placement="topLeft" overlayStyle={{ maxWidth: 720 }}>
      <span style={{ display: 'block', maxWidth: 320, overflow: 'hidden', whiteSpace: 'nowrap', textOverflow: 'ellipsis' }}>
        {value}
      </span>
    </Tooltip>
  )
}

export default function ChannelFailureLogs() {
  const [form] = Form.useForm()
  const [loading, setLoading] = useState(false)
  const [data, setData] = useState([])
  const [pagination, setPagination] = useState({ current: 1, pageSize: 20, total: 0 })

  const load = async (page = pagination.current, pageSize = pagination.pageSize) => {
    const values = form.getFieldsValue()
    const params = { page: page - 1, size: pageSize }
    if (values.channelId) params.channelId = values.channelId
    if (values.modelName?.trim()) params.modelName = values.modelName.trim()
    if (values.analyzed !== undefined && values.analyzed !== null) params.analyzed = values.analyzed
    setLoading(true)
    try {
      const res = await getChannelFailureLogs(params)
      if (res.code === 200) {
        const pageData = res.data || {}
        setData(pageData.content || [])
        setPagination({
          current: (pageData.number ?? page - 1) + 1,
          pageSize: pageData.size || pageSize,
          total: pageData.totalElements || 0,
        })
      }
    } catch (error) {
      message.error(error?.message || '加载渠道失败日志失败')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => { load(1, 20) }, [])

  const columns = [
    { title: '渠道ID', dataIndex: 'channelId', width: 90 },
    { title: '模型名', dataIndex: 'modelName', width: 180, render: value => value || '—' },
    { title: '错误码', dataIndex: 'errorCode', width: 100, render: value => value || '—' },
    { title: '错误信息', dataIndex: 'errorMessage', width: 360, render: value => <ErrorCell value={value} /> },
    {
      title: 'AI分析', dataIndex: 'analyzed', width: 100,
      render: value => value ? <Tag color="green">是</Tag> : <Tag color="default">否</Tag>,
    },
    {
      title: '时间', dataIndex: 'createdAt', width: 180,
      render: value => value ? dayjs(value).format('YYYY-MM-DD HH:mm:ss') : '—',
    },
  ]

  return (
    <div>
      <Form className="mobile-filter-form" form={form} layout="inline" onFinish={() => load(1, pagination.pageSize)} style={{ marginBottom: 20, rowGap: 12 }}>
        <Form.Item name="channelId" label="渠道ID">
          <InputNumber placeholder="渠道ID" style={{ width: 120 }} />
        </Form.Item>
        <Form.Item name="modelName" label="模型名">
          <Input allowClear placeholder="模型名称" style={{ width: 180 }} />
        </Form.Item>
        <Form.Item name="analyzed" label="AI分析">
          <Select allowClear placeholder="全部" style={{ width: 100 }} options={[
            { value: true, label: '已分析' },
            { value: false, label: '未分析' },
          ]} />
        </Form.Item>
        <Form.Item>
          <Space>
            <Button type="primary" htmlType="submit" icon={<SearchOutlined />}>查询</Button>
            <Button icon={<ReloadOutlined />} onClick={() => { form.resetFields(); load(1, pagination.pageSize) }}>重置</Button>
          </Space>
        </Form.Item>
      </Form>

      <Table
        rowKey="id"
        loading={loading}
        columns={columns}
        dataSource={data}
        scroll={{ x: 1010 }}
        pagination={{ ...pagination, showSizeChanger: true, showTotal: total => `共 ${total} 条` }}
        onChange={next => load(next.current, next.pageSize)}
      />
    </div>
  )
}
