import React, { useEffect, useState } from 'react'
import { Table, Button, Modal, Form, Input, InputNumber, Select, Space, Tag, message, Popconfirm, Switch, Typography } from 'antd'
import { PlusOutlined, DeleteOutlined, EditOutlined, CopyOutlined, SearchOutlined, BarChartOutlined, ExperimentOutlined, SendOutlined } from '@ant-design/icons'
import { getTokens, createToken, updateToken, deleteToken, updateTokenStatus, getTokenCreditHistory, testTokenChatStream, getTokenModels } from '../api'
import dayjs from 'dayjs'

const { Text } = Typography

export default function Tokens() {
  const [tokens, setTokens] = useState([])
  const [loading, setLoading] = useState(false)
  const [modalOpen, setModalOpen] = useState(false)
  const [editing, setEditing] = useState(null)
  const [form] = Form.useForm()
  const [searchText, setSearchText] = useState('')
  const [historyModalOpen, setHistoryModalOpen] = useState(false)
  const [historyToken, setHistoryToken] = useState(null)
  const [historyData, setHistoryData] = useState([])
  const [historyLoading, setHistoryLoading] = useState(false)
  const [testModalOpen, setTestModalOpen] = useState(false)
  const [testToken, setTestToken] = useState(null)
  const [testProtocol, setTestProtocol] = useState('openai')
  const [testModel, setTestModel] = useState('')
  const [testMessage, setTestMessage] = useState('')
  const [testResult, setTestResult] = useState(null)
  const [testLoading, setTestLoading] = useState(false)
  const [modelOptions, setModelOptions] = useState([])
  const [streamContent, setStreamContent] = useState('')
  const user = JSON.parse(localStorage.getItem('user') || '{}')
  const isAdmin = user.role === 'admin'

  const load = (search) => {
    setLoading(true)
    getTokens(search).then(res => {
      if (res.code === 200) setTokens(res.data || [])
    }).finally(() => setLoading(false))
  }

  useEffect(() => { load() }, [])

  const loadModels = () => {
    getTokenModels().then(res => {
      if (res.code === 200) {
        setModelOptions((res.data || []).map(m => ({
          value: m.displayName,
          label: m.displayName
        })))
      }
    })
  }

  useEffect(() => { loadModels() }, [])

  const handleSearch = (value) => {
    setSearchText(value)
    load(value || undefined)
  }

  const handleSave = async () => {
    const values = await form.validateFields()
    if (editing) {
      await updateToken(editing.id, values)
      message.success('更新成功')
    } else {
      await createToken(values)
      message.success('创建成功')
    }
    setModalOpen(false)
    form.resetFields()
    setEditing(null)
    load()
  }

  const handleDelete = async (id) => {
    await deleteToken(id)
    message.success('删除成功')
    load()
  }

  const handleStatusChange = async (id, status) => {
    await updateTokenStatus(id, status ? 1 : 0)
    message.success('状态已更新')
    load()
  }

  const openCreditHistory = (token) => {
    setHistoryToken(token)
    setHistoryModalOpen(true)
    setHistoryLoading(true)
    getTokenCreditHistory(token.id).then(res => {
      if (res.code === 200) setHistoryData(res.data || [])
    }).finally(() => setHistoryLoading(false))
  }

  const copyToken = (key) => {
    if (navigator.clipboard && window.isSecureContext) {
      navigator.clipboard.writeText(key).then(() => message.success('已复制到剪贴板'))
    } else {
      const textArea = document.createElement('textarea')
      textArea.value = key
      textArea.style.position = 'fixed'
      textArea.style.left = '-9999px'
      document.body.appendChild(textArea)
      textArea.select()
      try {
        document.execCommand('copy')
        message.success('已复制到剪贴板')
      } catch (err) {
        message.error('复制失败，请手动复制')
      }
      document.body.removeChild(textArea)
    }
  }

  const handleTestChat = async () => {
    if (!testModel) {
      message.warning('请选择模型')
      return
    }
    setTestLoading(true)
    setStreamContent('')
    setTestResult({ success: true, protocol: testProtocol })
    
    const startTime = Date.now()
    try {
      await testTokenChatStream(
        {
          tokenKey: testToken.tokenKey,
          protocol: testProtocol,
          model: testModel,
          message: testMessage || 'hi'
        },
        (chunk) => {
          // 从 SSE chunk 中提取内容
          let text = ''
          if (testProtocol === 'claude') {
            // Claude 流式: {"type":"content_block_delta","delta":{"type":"text_delta","text":"..."}}
            if (chunk.type === 'content_block_delta' && chunk.delta?.text) {
              text = chunk.delta.text
            }
          } else {
            // OpenAI 流式: {"choices":[{"delta":{"content":"..."}}]}
            text = chunk.choices?.[0]?.delta?.content || ''
          }
          if (text) {
            setStreamContent(prev => prev + text)
          }
          // 更新耗时
          setTestResult(prev => ({ ...prev, duration: Date.now() - startTime }))
        },
        () => {
          // 完成
          setTestLoading(false)
          message.success('测试完成')
        },
        (err) => {
          setTestLoading(false)
          setTestResult({ success: false, error: err.message || '请求失败' })
          message.error(err.message || '请求失败')
        }
      )
    } catch (err) {
      setTestLoading(false)
      setTestResult({ success: false, error: err.message || '请求失败' })
    }
  }

  const columns = [
    { title: 'ID', dataIndex: 'id', width: 60 },
    { title: '名称', dataIndex: 'name', width: 120 },
    ...(isAdmin ? [{ title: '所属账号', dataIndex: 'ownerName', width: 120 }] : []),
    {
      title: 'Token Key', dataIndex: 'tokenKey', width: 280,
      render: v => (
        <Space>
          <Text code style={{ fontSize: 12 }}>{v?.substring(0, 20)}...</Text>
          <Button size="small" icon={<CopyOutlined />} onClick={() => copyToken(v)} />
        </Space>
      )
    },
    { title: '积分', dataIndex: 'credits', width: 100, render: v => v === -1 ? '无限' : (v != null ? Math.round(Number(v)) + ' 积分' : '0 积分') },
    ...(isAdmin ? [{ title: '限流(次/分)', dataIndex: 'rateLimit', width: 100, render: v => v === 0 ? '不限' : v + ' 次/分' }] : []),
    { title: '状态', dataIndex: 'status', width: 80, render: (v, r) => <Switch checked={v === 1} onChange={(c) => handleStatusChange(r.id, c)} /> },
    {
      title: '操作', width: 280, fixed: 'right', render: (_, record) => (
        <Space size="small" wrap>
          <Button size="small" icon={<EditOutlined />} onClick={() => {
            setEditing(record)
            form.setFieldsValue(record)
            setModalOpen(true)
          }}>编辑</Button>
          <Button size="small" icon={<BarChartOutlined />} onClick={() => openCreditHistory(record)}>消耗记录</Button>
          <Button size="small" icon={<ExperimentOutlined />} onClick={() => {
            setTestToken(record)
            setTestProtocol('openai')
            setTestModel(modelOptions.length > 0 ? modelOptions[0].value : '')
            setTestMessage('')
            setTestResult(null)
            setTestModalOpen(true)
          }}>测试</Button>
          <Popconfirm title="确定删除？" onConfirm={() => handleDelete(record.id)}>
            <Button size="small" danger icon={<DeleteOutlined />}>删除</Button>
          </Popconfirm>
        </Space>
      )
    },
  ]

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
        <h2>Token 管理</h2>
        <Space>
          {isAdmin && (
            <Input.Search
              placeholder="搜索所属账号"
              allowClear
              onSearch={handleSearch}
              style={{ width: 250 }}
              prefix={<SearchOutlined />}
            />
          )}
          <Button type="primary" icon={<PlusOutlined />} onClick={() => { setEditing(null); form.resetFields(); setModalOpen(true) }}>新增 Token</Button>
        </Space>
      </div>
      {modelOptions.length > 0 && (
        <div style={{ marginBottom: 16, padding: '12px 16px', background: '#fafafa', borderRadius: 8, border: '1px solid #f0f0f0' }}>
          <div style={{ marginBottom: 8, fontSize: 13, color: '#888' }}>可用模型（{modelOptions.length}）</div>
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
            {modelOptions.map(m => (
              <Tag key={m.value} color="blue" style={{ margin: 0 }}>{m.label}</Tag>
            ))}
          </div>
        </div>
      )}
      <Table columns={columns} dataSource={tokens} rowKey="id" loading={loading} scroll={{ x: 1200 }} />
      <Modal title={editing ? '编辑 Token' : '新增 Token'} open={modalOpen} onOk={handleSave} onCancel={() => setModalOpen(false)} width={500}>
        <Form form={form} layout="vertical">
          <Form.Item name="name" label="名称" rules={[{ required: true }]}><Input placeholder="Token 名称" /></Form.Item>
          <Form.Item name="credits" label="积分" initialValue={-1}><InputNumber style={{ width: '100%' }} placeholder="-1 表示无限" /></Form.Item>
          {isAdmin && (
            <Form.Item name="rateLimit" label="限流(每分钟请求数)" initialValue={0} tooltip="0 表示不限流">
              <InputNumber style={{ width: '100%' }} min={0} step={10} placeholder="每分钟最大请求数，0 表示不限" />
            </Form.Item>
          )}
        </Form>
      </Modal>
      <Modal
        title={`Token消耗记录 - ${historyToken?.name || ''}`}
        open={historyModalOpen}
        onCancel={() => setHistoryModalOpen(false)}
        footer={null}
        width={600}
      >
        <Table
          columns={[
            { title: '日期', dataIndex: 'date', key: 'date' },
            { title: '消耗积分', dataIndex: 'credits', key: 'credits', render: v => Math.round(Number(v)) + ' 积分' },
          ]}
          dataSource={historyData}
          rowKey="date"
          loading={historyLoading}
          pagination={{ pageSize: 10 }}
          size="small"
          locale={{ emptyText: '暂无消耗记录' }}
        />
      </Modal>

      {/* Token 测试弹窗 */}
      <Modal
        title={`Token 测试 - ${testToken?.name || ''}`}
        open={testModalOpen}
        onCancel={() => setTestModalOpen(false)}
        footer={null}
        width={600}
      >
        <div style={{ marginBottom: 12 }}>
          <div style={{ marginBottom: 8 }}>协议：</div>
          <Select
            value={testProtocol}
            onChange={setTestProtocol}
            style={{ width: '100%' }}
            options={[
              { value: 'openai', label: 'OpenAI（/v1/chat/completions）' },
              { value: 'claude', label: 'Claude CC（/v1/messages）' }
            ]}
          />
        </div>
        <div style={{ marginBottom: 12 }}>
          <div style={{ marginBottom: 8 }}>模型：</div>
          <Select
            value={testModel}
            onChange={setTestModel}
            style={{ width: '100%' }}
            options={modelOptions}
            placeholder="选择模型"
            showSearch
            optionFilterProp="label"
          />
        </div>
        <div style={{ marginBottom: 12 }}>
          <div style={{ marginBottom: 8 }}>消息：</div>
          <Input.TextArea
            value={testMessage}
            onChange={e => setTestMessage(e.target.value)}
            placeholder="输入测试消息，不填则默认发送 hi"
            rows={2}
            onPressEnter={e => { if (e.ctrlKey || e.metaKey) handleTestChat() }}
          />
        </div>
        <Button
          type="primary"
          icon={<SendOutlined />}
          loading={testLoading}
          onClick={handleTestChat}
          block
        >
          发送测试 (Ctrl+Enter)
        </Button>

        {testResult && (
          <div style={{ marginTop: 16 }}>
            <div style={{ marginBottom: 8, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <strong>响应结果：</strong>
              <Space>
                {testResult.protocol && <Tag color="blue">{testResult.protocol.toUpperCase()}</Tag>}
                {testResult.success ? (
                  <Tag color="green">✓ 成功</Tag>
                ) : (
                  <Tag color="red">✗ 失败</Tag>
                )}
                {testResult.duration && <Tag>{testResult.duration}ms</Tag>}
              </Space>
            </div>
            {testResult.success ? (
              <div>
                <div style={{ background: '#f5f5f5', padding: 12, borderRadius: 6, whiteSpace: 'pre-wrap', wordBreak: 'break-word', fontSize: 14, minHeight: 80 }}>
                  {streamContent || (testLoading ? '正在接收...' : '(空响应)')}
                </div>
              </div>
            ) : (
              <div style={{ background: '#fff2f0', padding: 12, borderRadius: 6, color: '#ff4d4f', whiteSpace: 'pre-wrap', wordBreak: 'break-word', fontSize: 13 }}>
                {testResult.error || '未知错误'}
              </div>
            )}
          </div>
        )}
      </Modal>
    </div>
  )
}
