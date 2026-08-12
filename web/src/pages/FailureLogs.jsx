import React, { useEffect, useState } from 'react'
import { Button, Checkbox, DatePicker, Form, Input, InputNumber, Space, Table, Tag, Tooltip, Typography, message } from 'antd'
import { SearchOutlined, ReloadOutlined } from '@ant-design/icons'
import dayjs from 'dayjs'
import { getFailureLogs } from '../api'

const { RangePicker } = DatePicker
const { Paragraph, Text } = Typography

function ErrorCell({ value }) {
  if (!value) return <Text type="secondary">—</Text>
  return (
    <Tooltip title={value} placement="topLeft" overlayStyle={{ maxWidth: 720 }}>
      <span style={{ display: 'block', maxWidth: 320, overflow: 'hidden', whiteSpace: 'nowrap', textOverflow: 'ellipsis' }}>
        {value}
      </span>
    </Tooltip>
  )
}

export default function FailureLogs() {
  const [form] = Form.useForm()
  const [loading, setLoading] = useState(false)
  const [data, setData] = useState([])
  const [pagination, setPagination] = useState({ current: 1, pageSize: 20, total: 0 })

  const load = async (page = pagination.current, pageSize = pagination.pageSize) => {
    const values = form.getFieldsValue()
    const params = { page: page - 1, size: pageSize }
    if (values.traceId?.trim()) params.traceId = values.traceId.trim()
    if (values.exactTraceId) params.exactTraceId = true
    if (values.httpStatus) params.httpStatus = values.httpStatus
    if (values.timeRange?.length === 2) {
      params.startTime = values.timeRange[0].valueOf()
      params.endTime = values.timeRange[1].valueOf()
    }
    setLoading(true)
    try {
      const res = await getFailureLogs(params)
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
      message.error(error?.message || '加载失败日志失败')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => { load(1, 20) }, [])

  const columns = [
    {
      title: '时间', dataIndex: 'createdAt', width: 180,
      render: value => value ? dayjs(value).format('YYYY-MM-DD HH:mm:ss') : '—',
    },
    {
      title: 'traceId', dataIndex: 'traceId', width: 230,
      render: value => <Text copyable={{ text: value }} style={{ fontFamily: 'monospace' }}>{value}</Text>,
    },
    { title: '用户请求模型', dataIndex: 'modelName', width: 160, render: value => value || '—' },
    { title: '渠道模型', dataIndex: 'channelModelName', width: 160, render: value => value || '—' },
    {
      title: '状态码', dataIndex: 'httpStatus', width: 90,
      render: value => <Tag color={value >= 500 ? 'red' : value === 429 ? 'orange' : 'gold'}>{value}</Tag>,
    },
    { title: '用户报错', dataIndex: 'userError', width: 340, render: value => <ErrorCell value={value} /> },
    { title: '渠道报错', dataIndex: 'channelError', width: 340, render: value => <ErrorCell value={value} /> },
  ]

  return (
    <div>
      <Form form={form} layout="inline" onFinish={() => load(1, pagination.pageSize)} style={{ marginBottom: 20, rowGap: 12 }}>
        <Form.Item name="traceId" label="traceId">
          <Input allowClear placeholder="输入完整或部分 traceId" style={{ width: 260 }} />
        </Form.Item>
        <Form.Item name="exactTraceId" valuePropName="checked">
          <Checkbox>精确匹配</Checkbox>
        </Form.Item>
        <Form.Item name="timeRange" label="时间范围">
          <RangePicker showTime format="YYYY-MM-DD HH:mm:ss" />
        </Form.Item>
        <Form.Item name="httpStatus" label="状态码">
          <InputNumber min={400} max={599} placeholder="如 502" style={{ width: 110 }} />
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
        scroll={{ x: 1500 }}
        pagination={{ ...pagination, showSizeChanger: true, showTotal: total => `共 ${total} 条` }}
        onChange={next => load(next.current, next.pageSize)}
        expandable={{
          expandedRowRender: record => (
            <div style={{ padding: '4px 16px' }}>
              <Paragraph><Text strong>用户报错：</Text><span style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>{record.userError || '—'}</span></Paragraph>
              <Paragraph style={{ marginBottom: 0 }}><Text strong>渠道报错：</Text><span style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>{record.channelError || '—'}</span></Paragraph>
            </div>
          ),
          rowExpandable: record => Boolean(record.userError || record.channelError),
        }}
      />
    </div>
  )
}
