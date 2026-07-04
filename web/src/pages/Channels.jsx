import React, { useEffect, useState } from 'react'
import { Table, Button, Modal, Form, Input, InputNumber, Select, Space, Tag, message, Popconfirm, Switch } from 'antd'
import { PlusOutlined, DeleteOutlined, EditOutlined, ApiOutlined, SendOutlined, ExperimentOutlined } from '@ant-design/icons'
import { getChannels, createChannel, updateChannel, deleteChannel, updateChannelStatus, getEnabledModels, fetchChannelModels, testChannelChatStream } from '../api'

export default function Channels() {
  const [channels, setChannels] = useState([])
  const [loading, setLoading] = useState(false)
  const [modalOpen, setModalOpen] = useState(false)
  const [editing, setEditing] = useState(null)
  const [form] = Form.useForm()
  const [modelOptions, setModelOptions] = useState([])
  const [allLocalModels, setAllLocalModels] = useState([])
  const [fetchingModels, setFetchingModels] = useState(false)
  const [testModalOpen, setTestModalOpen] = useState(false)
  const [testMessage, setTestMessage] = useState('')
  const [testModel, setTestModel] = useState('')
  const [testResult, setTestResult] = useState(null)
  const [testLoading, setTestLoading] = useState(false)
  const [streamContent, setStreamContent] = useState('')
  const [testModelOptions, setTestModelOptions] = useState([])


  const load = () => {
    setLoading(true)
    getChannels().then(res => {
      if (res.code === 200) setChannels(res.data || [])
    }).finally(() => setLoading(false))
  }

  const loadModels = () => {
    getEnabledModels().then(res => {
      if (res.code === 200) {
        const models = res.data || []
        setAllLocalModels(models)
        // 使用模型ID作为value，显示名称和displayName
        setModelOptions(models.map(m => ({ 
          value: String(m.id), 
          label: m.displayName ? `${m.name}（${m.displayName}）` : m.name 
        })))
      }
    })
  }

  useEffect(() => { load(); loadModels() }, [])

  const handleFetchModels = async () => {
    try {
      const values = form.getFieldsValue(['baseUrl', 'apiKey', 'type'])
      if (!values.baseUrl || !values.apiKey) {
        message.warning('请先填写 Base URL 和 API Key')
        return
      }
      setFetchingModels(true)
      const res = await fetchChannelModels(values)
      if (res.code === 200) {
        const upstreamModels = res.data || []
        if (upstreamModels.length === 0) {
          message.info('上游渠道没有返回任何模型')
          return
        }
        // 用上游模型名和本地模型列表按 name 匹配
        const matched = allLocalModels.filter(m => upstreamModels.includes(m.name))
        if (matched.length > 0) {
          // 使用模型ID作为value
          setModelOptions(matched.map(m => ({
            value: String(m.id),
            label: m.displayName ? `${m.name}（${m.displayName}）` : m.name
          })))
          
          // 同步更新 form 中的 modelIds 字段，确保只保留匹配的模型（使用ID）
          const currentModelIds = form.getFieldValue('modelIds') || []
          const normalizedModelIds = Array.isArray(currentModelIds) ? currentModelIds : [currentModelIds]
          // 将当前选中的模型名转换为ID
          const validModelIds = normalizedModelIds.map(idOrName => {
            // 如果已经是数字ID，直接返回
            if (/^\d+$/.test(idOrName)) return idOrName
            // 否则尝试查找对应的ID
            const matched = allLocalModels.find(m => m.name === idOrName || m.displayName === idOrName)
            return matched ? String(matched.id) : null
          }).filter(id => id !== null)
          
          if (validModelIds.length > 0) {
            form.setFieldValue('modelIds', validModelIds)
          }
          
          message.success(`匹配到 ${matched.length} 个模型`)
        } else {
          // 没有匹配到，显示上游模型名供手动输入
          setModelOptions(upstreamModels.map(name => ({ value: name, label: name })))
          message.info(`未匹配到本地模型，已加载 ${upstreamModels.length} 个上游模型供选择`)
        }
      }
    } catch (err) {
      message.error(err?.message || '获取上游模型失败')
    } finally {
      setFetchingModels(false)
    }
  }

  const handleTestChat = async () => {
    if (!testModel) {
      message.warning('请选择模型')
      return
    }
    const values = form.getFieldsValue(['baseUrl', 'apiKey', 'type'])
    if (!values.baseUrl || !values.apiKey) {
      message.warning('请先填写 Base URL 和 API Key')
      return
    }
    setTestLoading(true)
    setStreamContent('')
    setTestResult({ success: true, statusCode: 200, duration: 0 })
    
    const startTime = Date.now()
    try {
      await testChannelChatStream(
        {
          ...values,
          model: testModel,
          message: testMessage || 'hi'
        },
        (chunk) => {
          // 累积流式内容
          if (chunk.content) {
            setStreamContent(prev => prev + chunk.content)
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

  const handleSave = async () => {
    const values = await form.validateFields()
    // 多选模型ID转逗号分隔字符串
    if (values.modelIds && Array.isArray(values.modelIds)) {
      values.modelIds = values.modelIds.join(',')
    }
    if (editing) {
      await updateChannel(editing.id, values)
      message.success('更新成功')
    } else {
      await createChannel(values)
      message.success('创建成功')
    }
    setModalOpen(false)
    form.resetFields()
    setEditing(null)
    load()
  }

  const handleDelete = async (id) => {
    await deleteChannel(id)
    message.success('删除成功')
    load()
  }

  const handleStatusChange = async (id, status) => {
    await updateChannelStatus(id, status ? 1 : 0)
    message.success('状态已更新')
    load()
  }

  const columns = [
    { title: 'ID', dataIndex: 'id', width: 60 },
    { title: '名称', dataIndex: 'name', width: 120 },
    { title: '类型', dataIndex: 'type', width: 100, render: v => <Tag color="blue">{v}</Tag> },
    { title: 'Base URL', dataIndex: 'baseUrl', ellipsis: true },
    { title: '模型', dataIndex: 'modelIds', ellipsis: true, render: v => {
      if (!v) return '-'
      // 将ID转换为显示名称
      const modelNames = v.split(',').map(id => {
        const model = allLocalModels.find(m => String(m.id) === id.trim())
        return model ? (model.displayName ? `${model.name}（${model.displayName}）` : model.name) : id
      })
      return modelNames.map(m => <Tag key={m}>{m}</Tag>)
    } },
    { title: '状态', dataIndex: 'status', width: 80, render: (v, r) => <Switch checked={v === 1} onChange={(c) => handleStatusChange(r.id, c)} /> },
    {
      title: '操作', width: 180, fixed: 'right', render: (_, record) => (
        <Space size="small" wrap>
          <Button size="small" icon={<EditOutlined />} onClick={() => {
            setEditing(record)
            const formValues = { ...record }
            if (formValues.modelIds && typeof formValues.modelIds === 'string') {
              // 将逗号分隔的模型ID转换为数组
              formValues.modelIds = formValues.modelIds.split(',').map(id => id.trim()).filter(id => id)
            }
            form.setFieldsValue(formValues)
            setModalOpen(true)
          }}>编辑</Button>
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
        <h2>渠道管理</h2>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => { setEditing(null); form.resetFields(); setModalOpen(true) }}>新增渠道</Button>
      </div>
      <Table columns={columns} dataSource={channels} rowKey="id" loading={loading} scroll={{ x: 1100 }} />
      <Modal
        title={editing ? '编辑渠道' : '新增渠道'}
        open={modalOpen}
        onCancel={() => setModalOpen(false)}
        width={600}
        footer={[
          <Button key="test" icon={<ExperimentOutlined />} onClick={() => {
            const modelIds = form.getFieldValue('modelIds')
            // 构建测试弹窗的模型选项：只显示该渠道配置的模型，value用displayName（上游API使用显示名称）
            const opts = []
            if (modelIds && Array.isArray(modelIds) && modelIds.length > 0) {
              modelIds.forEach(id => {
                const matched = allLocalModels.find(m => String(m.id) === String(id))
                if (matched) {
                  const sendValue = matched.displayName || matched.name
                  opts.push({ value: sendValue, label: matched.displayName ? `${matched.name}（${matched.displayName}）` : matched.name })
                }
              })
            }
            // 兜底：如果没有匹配到，使用所有modelOptions
            if (opts.length === 0 && modelOptions.length > 0) {
              modelOptions.forEach(opt => {
                const matched = allLocalModels.find(m => String(m.id) === opt.value)
                if (matched) {
                  const sendValue = matched.displayName || matched.name
                  opts.push({ value: sendValue, label: matched.displayName ? `${matched.name}（${matched.displayName}）` : matched.name })
                }
              })
            }
            setTestModelOptions(opts)
            // 默认选中第一个
            if (opts.length > 0) {
              setTestModel(opts[0].value)
            } else {
              setTestModel('')
            }
            setTestMessage('')
            setTestResult(null)
            setTestModalOpen(true)
          }}>测试</Button>,
          <Button key="cancel" onClick={() => setModalOpen(false)}>取消</Button>,
          <Button key="ok" type="primary" onClick={handleSave}>确定</Button>,
        ]}
      >
        <Form form={form} layout="vertical">
          <Form.Item name="name" label="名称" rules={[{ required: true }]}><Input placeholder="渠道名称" /></Form.Item>
          <Form.Item name="type" label="类型" rules={[{ required: true }]}>
            <Select placeholder="选择类型" options={[{ value: 'openai', label: 'OpenAI' }, { value: 'azure', label: 'Azure' }, { value: 'claude', label: 'Claude' }, { value: 'custom', label: '自定义' }]} />
          </Form.Item>
          <Form.Item name="baseUrl" label="Base URL" rules={[{ required: true }]}><Input placeholder="https://api.openai.com" /></Form.Item>
          <Form.Item name="apiKey" label="API Key" rules={[{ required: true }]}><Input.Password placeholder="sk-xxx" /></Form.Item>
          <Form.Item name="modelIds" label={
            <span>支持模型 <Button size="small" type="dashed" icon={<ApiOutlined />} loading={fetchingModels} onClick={handleFetchModels}>获取模型</Button></span>
          }>
            <Select
              mode="tags"
              placeholder="选择或输入模型名称"
              options={modelOptions}
              tokenSeparators={[',']}
              style={{ width: '100%' }}
              // 允许用户输入不在 options 中的值（用于手动输入上游模型名）
              filterOption={(input, option) =>
                (option?.label ?? '').toLowerCase().includes(input.toLowerCase()) ||
                (option?.value ?? '').toLowerCase().includes(input.toLowerCase())
              }
            />
          </Form.Item>
          <Space>
            <Form.Item name="priority" label="优先级" initialValue={0}><InputNumber /></Form.Item>
            <Form.Item name="rateLimit" label="速率限制" initialValue={0}><InputNumber min={0} placeholder="0=不限" /></Form.Item>
          </Space>
        </Form>
      </Modal>

      {/* 测试弹窗 */}
      <Modal
        title="渠道测试"
        open={testModalOpen}
        onCancel={() => setTestModalOpen(false)}
        footer={null}
        width={600}
      >
        <div style={{ marginBottom: 12 }}>
          <div style={{ marginBottom: 8 }}>模型：</div>
          <Select
            value={testModel}
            onChange={setTestModel}
            style={{ width: '100%' }}
            options={testModelOptions}
            placeholder="选择模型"
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
                <Tag color={testResult.success ? 'green' : 'red'}>
                  {testResult.success ? `✓ ${testResult.statusCode}` : `✗ ${testResult.statusCode}`}
                </Tag>
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
