import React, { useEffect, useMemo, useState } from 'react'
import { Button, Card, Col, DatePicker, Form, Row, Select, Space, Statistic, Table, Typography, message } from 'antd'
import { DownloadOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons'
import dayjs from 'dayjs'
import utc from 'dayjs/plugin/utc'
import timezone from 'dayjs/plugin/timezone'
import { exportCostCsv, getChannels, getCostAggregate, getCostModels, getModelGroups, getModels } from '../api'

dayjs.extend(utc)
dayjs.extend(timezone)

const { RangePicker } = DatePicker
const { Text } = Typography
const beijingToday = dayjs().tz('Asia/Shanghai')
const DEFAULT_RANGE = [beijingToday.subtract(6, 'day'), beijingToday]

const numberFormat = value => Number(value || 0).toLocaleString('zh-CN')
const costFormat = value => Number(value || 0).toFixed(0)

export default function Cost() {
  const [form] = Form.useForm()
  const [loading, setLoading] = useState(false)
  const [exporting, setExporting] = useState(false)
  const [data, setData] = useState([])
  const [channels, setChannels] = useState([])
  const [configuredModels, setConfiguredModels] = useState([])
  const [usageModels, setUsageModels] = useState([])
  const [summary, setSummary] = useState({})
  const [pagination, setPagination] = useState({ current: 1, pageSize: 20, total: 0 })
  const models = useMemo(
    () => [...new Set([...configuredModels, ...usageModels])].sort(),
    [configuredModels, usageModels],
  )

  const currentParams = (page = pagination.current, pageSize = pagination.pageSize) => {
    const values = form.getFieldsValue()
    const range = values.dateRange?.length === 2 ? values.dateRange : DEFAULT_RANGE
    return {
      startDate: range[0].format('YYYY-MM-DD'),
      endDate: range[1].format('YYYY-MM-DD'),
      ...(values.channelId ? { channelId: values.channelId } : {}),
      ...(values.modelName ? { modelName: values.modelName } : {}),
      page: page - 1,
      size: pageSize,
    }
  }

  const load = async (page = pagination.current, pageSize = pagination.pageSize) => {
    setLoading(true)
    try {
      const res = await getCostAggregate(currentParams(page, pageSize))
      if (res.code === 200) {
        const result = res.data || {}
        setData(result.content || [])
        setSummary(result.summary || {})
        setPagination({
          current: (result.page ?? page - 1) + 1,
          pageSize: result.size || pageSize,
          total: result.totalElements || 0,
        })
      }
    } catch (error) {
      message.error(error?.message || '加载成本数据失败')
    } finally {
      setLoading(false)
    }
  }

  const loadModelOptions = async () => {
    const params = currentParams()
    delete params.modelName
    delete params.page
    delete params.size
    try {
      const res = await getCostModels(params)
      if (res.code === 200) setUsageModels(res.data || [])
    } catch {
      message.warning('实际模型筛选项加载失败，仍可查询成本数据')
    }
  }

  useEffect(() => {
    Promise.all([getChannels(), getModels(), getModelGroups()]).then(([channelRes, modelRes, groupRes]) => {
      if (channelRes.code === 200) setChannels(channelRes.data || [])
      const modelNames = (modelRes.data || []).map(item => item.name)
      const groupNames = (groupRes.data || []).map(item => item.group?.name).filter(Boolean)
      if (modelRes.code === 200 || groupRes.code === 200) {
        setConfiguredModels([...new Set([...modelNames, ...groupNames])].sort())
      }
    }).catch(() => message.warning('筛选项加载失败，仍可查询全部数据'))
    load(1, 20)
    loadModelOptions()
  }, [])

  const handleSearch = () => {
    load(1, pagination.pageSize)
    loadModelOptions()
  }

  const handleReset = () => {
    form.setFieldsValue({ dateRange: DEFAULT_RANGE, channelId: undefined, modelName: undefined })
    load(1, pagination.pageSize)
    loadModelOptions()
  }

  const handleExport = async () => {
    setExporting(true)
    try {
      const params = currentParams()
      delete params.page
      delete params.size
      const blob = await exportCostCsv(params)
      const url = URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = url
      link.download = `cost-${params.startDate}-to-${params.endDate}.csv`
      document.body.appendChild(link)
      link.click()
      link.remove()
      URL.revokeObjectURL(url)
    } catch (error) {
      message.error(error?.message || '导出 CSV 失败')
    } finally {
      setExporting(false)
    }
  }

  const columns = useMemo(() => [
    { title: '渠道', dataIndex: 'channelName', width: 150, fixed: 'left' },
    { title: '模型', dataIndex: 'model', width: 180, fixed: 'left', render: value => value || '—' },
    { title: '输入 token', dataIndex: 'totalPromptTokens', width: 130, align: 'right', render: numberFormat },
    { title: '输出 token', dataIndex: 'totalCompletionTokens', width: 130, align: 'right', render: numberFormat },
    { title: '缓存创建', dataIndex: 'totalCacheCreation', width: 130, align: 'right', render: numberFormat },
    { title: '缓存读取', dataIndex: 'totalCacheRead', width: 130, align: 'right', render: numberFormat },
    {
      title: '张数', dataIndex: 'imageCount', width: 90, align: 'right',
      render: (value, row) => row.modelType === 'image' ? numberFormat(value) : <Text type="secondary">—</Text>,
    },
    {
      title: '秒数', dataIndex: 'videoSeconds', width: 90, align: 'right',
      render: (value, row) => row.modelType === 'video' ? numberFormat(value) : <Text type="secondary">—</Text>,
    },
    { title: '请求数', dataIndex: 'requestCount', width: 100, align: 'right', render: numberFormat },
    {
      title: '成本', dataIndex: 'totalCreditCost', width: 190, align: 'right',
      render: value => <span>{costFormat(value)} 积分</span>,
    },
  ], [])

  return (
    <div>
      <Form
        className="mobile-filter-form"
        form={form}
        layout="inline"
        initialValues={{ dateRange: DEFAULT_RANGE }}
        onFinish={handleSearch}
        style={{ marginBottom: 20, rowGap: 12 }}
      >
        <Form.Item name="dateRange" label="日期范围" rules={[{ required: true, message: '请选择日期范围' }]}>
          <RangePicker allowClear={false} format="YYYY-MM-DD" />
        </Form.Item>
        <Form.Item name="channelId" label="渠道">
          <Select
            allowClear showSearch optionFilterProp="label" placeholder="全部渠道" style={{ width: 180 }}
            options={channels.map(item => ({ value: item.id, label: item.name }))}
          />
        </Form.Item>
        <Form.Item name="modelName" label="模型">
          <Select
            allowClear showSearch optionFilterProp="label" placeholder="全部模型" style={{ width: 220 }}
            options={models.map(name => ({ value: name, label: name }))}
          />
        </Form.Item>
        <Form.Item>
          <Space wrap>
            <Button type="primary" htmlType="submit" icon={<SearchOutlined />}>查询</Button>
            <Button icon={<ReloadOutlined />} onClick={handleReset}>重置</Button>
            <Button icon={<DownloadOutlined />} loading={exporting} onClick={handleExport}>导出 CSV</Button>
          </Space>
        </Form.Item>
      </Form>

      <Row gutter={[12, 12]} style={{ marginBottom: 16 }}>
        <Col xs={12} sm={8} lg={4}><Card size="small"><Statistic title="输入 token" value={summary.totalPromptTokens || 0} formatter={numberFormat} /></Card></Col>
        <Col xs={12} sm={8} lg={4}><Card size="small"><Statistic title="输出 token" value={summary.totalCompletionTokens || 0} formatter={numberFormat} /></Card></Col>
        <Col xs={12} sm={8} lg={4}><Card size="small"><Statistic title="缓存创建" value={summary.totalCacheCreation || 0} formatter={numberFormat} /></Card></Col>
        <Col xs={12} sm={8} lg={4}><Card size="small"><Statistic title="缓存读取" value={summary.totalCacheRead || 0} formatter={numberFormat} /></Card></Col>
        <Col xs={12} sm={8} lg={4}><Card size="small"><Statistic title="请求数" value={summary.requestCount || 0} formatter={numberFormat} /></Card></Col>
        <Col xs={12} sm={8} lg={4}><Card size="small"><Statistic title="总成本" value={summary.totalCreditCost || 0} formatter={value => `${costFormat(value)} 积分`} /></Card></Col>
      </Row>

      <Table
        rowKey={row => `${row.channelId ?? 'unknown'}-${row.model}`}
        loading={loading}
        columns={columns}
        dataSource={data}
        scroll={{ x: 1420 }}
        pagination={{ ...pagination, showSizeChanger: true, showTotal: total => `共 ${total} 条` }}
        onChange={next => load(next.current, next.pageSize)}
      />
    </div>
  )
}
