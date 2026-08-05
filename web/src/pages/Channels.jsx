import React, { useEffect, useRef, useState } from 'react'
import { Table, Button, Modal, Form, Input, InputNumber, Select, Space, Tag, message, Popconfirm, Switch, Tooltip, Checkbox, Image, Spin } from 'antd'
import { PlusOutlined, DeleteOutlined, EditOutlined, ApiOutlined, SendOutlined, ExperimentOutlined, UnlockOutlined, CopyOutlined, SearchOutlined } from '@ant-design/icons'
import { getChannels, createChannel, updateChannel, deleteChannel, updateChannelStatus, getEnabledModels, fetchChannelModels, testChannelChatStream, testChannelMedia, pollChannelTestVideo, downloadChannelTestVideo, getChannelHealth, unblockChannel, getChannelApiKey } from '../api'

const CB_STATE_COLOR = { CLOSED: 'green', HALF_OPEN: 'gold', OPEN: 'red' }

const HEALTH_REFRESH_MS = 30000
const VIDEO_POLL_INTERVAL_MS = 5000
const VIDEO_POLL_LIMIT = 150

const firstString = (...values) => values.find(value => typeof value === 'string' && value.trim())

const extractImageSource = (data) => {
  const item = Array.isArray(data?.data) ? data.data[0] : data?.data || data
  const url = firstString(item?.url, item?.image_url, data?.url, typeof item === 'string' ? item : null)
  if (url) return url
  const base64 = firstString(item?.b64_json, item?.base64, item?.image_base64, data?.b64_json)
  return base64 ? (base64.startsWith('data:') ? base64 : `data:image/png;base64,${base64}`) : null
}

const extractVideoSource = (data) => firstString(
  typeof data === 'string' ? data : null,
  data?.url,
  data?.data?.url,
  data?.metadata?.url,
  data?.data?.metadata?.url,
  data?.video?.metadata?.url,
  data?.remixed_from_video_id,
  data?.data?.remixed_from_video_id,
  data?.video?.remixed_from_video_id,
  data?.video_url,
  data?.output_url,
  data?.video?.url,
  data?.video?.video_url,
  data?.output?.url,
  data?.output?.video_url,
  data?.result?.url,
  data?.result?.video_url,
  data?.outputs?.[0]?.url,
  data?.data?.video?.url,
  data?.data?.output?.url,
  data?.data?.result?.url,
  Array.isArray(data?.data) ? firstString(data.data[0]?.url, data.data[0]?.video_url) : firstString(data?.data?.url, data?.data?.video_url, data?.data?.output_url)
)

const extractVideoTaskId = (data) => firstString(
  data?.video_id,
  data?.data?.video_id,
  data?.id,
  data?.task_id,
  data?.taskId,
  data?.data?.id,
  data?.data?.task_id,
  data?.data?.taskId,
  data?.video?.id
)
const videoStatus = (data) => String(data?.status || data?.state || data?.data?.status || data?.data?.state || data?.video?.status || '').toLowerCase()
const isVideoFailed = (status) => ['failed', 'cancelled', 'canceled', 'error'].includes(status)

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
  const testRunRef = useRef(0)
  const videoObjectUrlRef = useRef(null)
  const [healthMap, setHealthMap] = useState({})
  const [showBlockedOnly, setShowBlockedOnly] = useState(false)
  const [revealedKeys, setRevealedKeys] = useState({})
  const [copyingId, setCopyingId] = useState(null)
  const [searchName, setSearchName] = useState('')
  const [searchModelIds, setSearchModelIds] = useState([])


  const load = (filters) => {
    setLoading(true)
    const params = {}
    const name = filters?.name
    const modelIds = filters?.modelIds
    if (name) params.name = name
    if (modelIds && modelIds.length > 0) params.modelIds = modelIds.join(',')
    getChannels(params).then(res => {
      if (res.code === 200) setChannels(res.data || [])
    }).finally(() => setLoading(false))
  }

  const loadHealth = () => {
    getChannelHealth().then(res => {
      if (res.code === 200) {
        const map = {}
        ;(res.data || []).forEach(h => { map[h.channelId] = h })
        setHealthMap(map)
      }
    })
  }

  const handleUnblock = async (id) => {
    await unblockChannel(id)
    message.success('已解除封禁')
    loadHealth()
  }

  const revealApiKey = async (id) => {
    if (revealedKeys[id]) return revealedKeys[id]
    const res = await getChannelApiKey(id)
    if (res.code === 200) {
      const key = res.data?.apiKey
      setRevealedKeys(prev => ({ ...prev, [id]: key }))
      return key
    }
    return null
  }

  // 复制完整 Key 到剪贴板，列表中不显示明文
  const handleCopyApiKey = async (id) => {
    setCopyingId(id)
    try {
      const key = await revealApiKey(id)
      if (key) {
        await navigator.clipboard.writeText(key)
        message.success('已复制')
      } else {
        message.error('获取 API Key 失败')
      }
    } catch {
      message.error('复制失败')
    } finally {
      setCopyingId(null)
    }
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

  useEffect(() => { load(); loadModels(); loadHealth() }, [])

  useEffect(() => () => {
    if (videoObjectUrlRef.current) URL.revokeObjectURL(videoObjectUrlRef.current)
  }, [])

  useEffect(() => {
    const timer = setInterval(loadHealth, HEALTH_REFRESH_MS)
    return () => clearInterval(timer)
  }, [])

  const handleFetchModels = async () => {
    try {
      const values = form.getFieldsValue(['baseUrl', 'apiKey', 'type'])
      if (!values.baseUrl) {
        message.warning('请先填写 Base URL 和 API Key')
        return
      }
      let apiKey = values.apiKey
      if (!apiKey && editing?.id) {
        // 编辑已有渠道时，若未重新输入 Key，则使用数据库中已保存的真实 Key 获取模型
        apiKey = await revealApiKey(editing.id)
      }
      if (!apiKey) {
        message.warning('请先填写 Base URL 和 API Key')
        return
      }
      setFetchingModels(true)
      const res = await fetchChannelModels({ ...values, apiKey })
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
    if (!values.baseUrl) {
      message.warning('请先填写 Base URL')
      return
    }
    let apiKey = values.apiKey
    if (!apiKey && editing?.id) {
      // 编辑已有渠道时，若未重新输入 Key，则使用数据库中已保存的真实 Key 进行测试
      apiKey = await revealApiKey(editing.id)
    }
    if (!apiKey) {
      message.warning('请先填写 Base URL 和 API Key')
      return
    }
    const selectedModel = testModelOptions.find(option => option.value === testModel)
    const modelType = String(selectedModel?.type || 'text').toLowerCase()
    const runId = ++testRunRef.current
    if (videoObjectUrlRef.current) {
      URL.revokeObjectURL(videoObjectUrlRef.current)
      videoObjectUrlRef.current = null
    }
    setTestLoading(true)
    setStreamContent('')
    setTestResult({ success: true, statusCode: 200, duration: 0, modelType })

    const startTime = Date.now()
    try {
      if (modelType !== 'text') {
        const request = {
          ...values,
          apiKey,
          model: testModel,
          modelType,
          message: testMessage || 'hi'
        }
        const response = await testChannelMedia(request)
        const result = response?.data
        if (!result?.success) {
          throw new Error(result?.error || `HTTP ${result?.statusCode || 'error'}`)
        }

        if (modelType === 'image') {
          const mediaUrl = extractImageSource(result.data)
          if (!mediaUrl) throw new Error('上游响应中未找到图片 URL 或 base64 数据')
          setTestResult({ ...result, success: true, modelType, mediaUrl })
        } else if (modelType === 'audio') {
          if (!result.dataUrl) throw new Error('上游响应中未返回音频数据')
          setTestResult({ ...result, success: true, modelType, mediaUrl: result.dataUrl })
        } else {
          const taskId = extractVideoTaskId(result.data)
          let currentData = result.data
          let mediaUrl = extractVideoSource(currentData)
          const initialStatus = videoStatus(currentData)
          if (isVideoFailed(initialStatus)) {
            throw new Error(firstString(currentData?.error?.message, currentData?.data?.error?.message, currentData?.error, currentData?.message) || `视频生成失败 (${initialStatus})`)
          }
          if (!taskId && !mediaUrl) throw new Error('上游响应中未找到视频任务 id 或视频 URL')
          setTestResult({ ...result, success: true, modelType, polling: !mediaUrl, videoStatus: initialStatus || 'queued' })

          for (let attempt = 0; !mediaUrl && attempt < VIDEO_POLL_LIMIT; attempt += 1) {
            await new Promise(resolve => setTimeout(resolve, VIDEO_POLL_INTERVAL_MS))
            if (testRunRef.current !== runId) return
            const pollResponse = await pollChannelTestVideo({ ...values, apiKey, videoId: taskId })
            const pollResult = pollResponse?.data
            if (!pollResult?.success) throw new Error(pollResult?.error || `视频状态查询失败 (HTTP ${pollResult?.statusCode || 'error'})`)
            currentData = pollResult.data
            const status = videoStatus(currentData)
            if (isVideoFailed(status)) {
              throw new Error(firstString(currentData?.error?.message, currentData?.data?.error?.message, currentData?.error, currentData?.message) || `视频生成失败 (${status})`)
            }
            mediaUrl = extractVideoSource(currentData)
            console.debug(`[video test] poll attempt=${attempt + 1} taskId=${taskId} status=${status} urlFound=${!!mediaUrl}`)
            // 上游 completed 后仍可能需要一段时间才能返回最终 URL，
            // 因此即使状态已 completed 但尚无 URL 也继续轮询，而不是立即报错或调用不存在的 /content 接口。
            setTestResult(prev => ({ ...prev, duration: Date.now() - startTime, videoStatus: status || 'processing', polling: !mediaUrl, mediaUrl }))
          }

          if (!mediaUrl && testRunRef.current === runId) {
            // 兜底：对不返回 URL 的其它渠道，仍尝试旧的 /content 下载方式
            try {
              const videoBlob = await downloadChannelTestVideo({ ...values, apiKey, videoId: taskId })
              if (testRunRef.current !== runId) return
              mediaUrl = URL.createObjectURL(videoBlob)
              videoObjectUrlRef.current = mediaUrl
              setTestResult(prev => ({ ...prev, duration: Date.now() - startTime, videoStatus: 'completed', polling: false, mediaUrl }))
            } catch (fallbackErr) {
              console.debug('[video test] fallback content download failed:', fallbackErr?.message || String(fallbackErr))
            }
          }
          if (!mediaUrl) throw new Error('上游视频文件尚未就绪，请稍后重试')
        }
        if (testRunRef.current === runId) {
          setTestLoading(false)
          message.success('测试完成')
        }
        return
      }

      await testChannelChatStream(
        {
          ...values,
          apiKey,
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
          if (testRunRef.current !== runId) return
          setTestLoading(false)
          message.success('测试完成')
        },
        (err) => {
          if (testRunRef.current !== runId) return
          setTestLoading(false)
          setTestResult({ success: false, error: err.message || '请求失败' })
          message.error(err.message || '请求失败')
        }
      )
    } catch (err) {
      if (testRunRef.current !== runId) return
      setTestLoading(false)
      setTestResult({ success: false, error: err?.message || '请求失败', modelType })
      message.error(err?.message || '请求失败')
    }
  }

  const handleSave = async () => {
    const values = await form.validateFields()
    // 多选模型ID转逗号分隔字符串
    if (values.modelIds && Array.isArray(values.modelIds)) {
      values.modelIds = values.modelIds.join(',')
    }
    // 多选用户等级转逗号分隔字符串
    if (values.supportedLevels && Array.isArray(values.supportedLevels)) {
      values.supportedLevels = values.supportedLevels.join(',')
    }
    if (editing) {
      if (!values.apiKey || !values.apiKey.trim()) {
        delete values.apiKey
      }
      await updateChannel(editing.id, values)
      message.success('更新成功')
    } else {
      await createChannel(values)
      message.success('创建成功')
    }
    setModalOpen(false)
    form.resetFields()
    setEditing(null)
    load({ name: searchName || undefined, modelIds: searchModelIds })
  }

  const handleSearchName = (value) => {
    setSearchName(value)
    load({ name: value || undefined, modelIds: searchModelIds })
  }

  const handleSearchModelIds = (values) => {
    setSearchModelIds(values)
    load({ name: searchName || undefined, modelIds: values })
  }

  const handleDelete = async (id) => {
    await deleteChannel(id)
    message.success('删除成功')
    load({ name: searchName || undefined, modelIds: searchModelIds })
  }

  const handleStatusChange = async (id, status) => {
    await updateChannelStatus(id, status ? 1 : 0)
    message.success('状态已更新')
    load({ name: searchName || undefined, modelIds: searchModelIds })
  }

  const columns = [
    { title: 'ID', dataIndex: 'id', width: 60 },
    { title: '名称', dataIndex: 'name', width: 120 },
    { title: '类型', dataIndex: 'type', width: 100, render: v => <Tag color="blue">{v}</Tag> },
    { title: 'Base URL', dataIndex: 'baseUrl', ellipsis: true },
    {
      title: 'API Key', dataIndex: 'apiKey', width: 200, render: (v, record) => (
        <Space size="small">
          <span style={{ fontFamily: 'monospace' }}>{v}</span>
          <Tooltip title="复制">
            <Button
              size="small"
              type="text"
              icon={<CopyOutlined />}
              loading={copyingId === record.id}
              onClick={() => handleCopyApiKey(record.id)}
            />
          </Tooltip>
        </Space>
      )
    },
    { title: '模型', dataIndex: 'modelIds', width: 280, render: v => {
      if (!v) return '-'
      // 将ID转换为显示名称
      const modelNames = v.split(',').map(id => {
        const model = allLocalModels.find(m => String(m.id) === id.trim())
        return model ? (model.displayName ? `${model.name}（${model.displayName}）` : model.name) : id
      })
      return (
        <Space size={[0, 4]} wrap>
          {modelNames.map(m => <Tag key={m} style={{ whiteSpace: 'normal', height: 'auto' }}>{m}</Tag>)}
        </Space>
      )
    } },
    { title: '支持等级', dataIndex: 'supportedLevels', width: 150, render: v => {
      if (!v) return '-'
      return v.split(',').map(l => l.trim()).filter(l => l).map(l => <Tag key={l} color="purple">Lv{l}</Tag>)
    } },
    { title: '状态', dataIndex: 'status', width: 80, render: (v, r) => <Switch checked={v === 1} onChange={(c) => handleStatusChange(r.id, c)} /> },
    {
      title: '健康状态', width: 220, render: (_, record) => {
        const h = healthMap[record.id]
        if (!h) return <Tag>加载中</Tag>
        const state = h.circuitBreakerState
        const errorRatePct = ((h.errorRate || 0) * 100).toFixed(1)
        const tooltip = (
          <div>
            <div>currentWeight: {h.currentWeight}</div>
            <div>effectiveWeight: {h.effectiveWeight}</div>
            <div>最近1分钟请求数: {h.totalRequests1m}</div>
            <div>探测失败次数: {h.probeFailures}</div>
            <div>最后成功: {h.lastSuccessAt || '-'}</div>
            <div>最后失败: {h.lastFailureAt || '-'}</div>
            <div>最后失败原因: {h.lastFailureReason || '-'}</div>
          </div>
        )
        return (
          <Tooltip title={tooltip}>
            <Space size="small" wrap>
              <Tag color={CB_STATE_COLOR[state] || 'default'}>{state}</Tag>
              <Tag>{errorRatePct}%</Tag>
              {state === 'OPEN' && h.blockedUntil && <Tag color="red">至 {h.blockedUntil}</Tag>}
            </Space>
          </Tooltip>
        )
      }
    },
    {
      title: '操作', width: 220, fixed: 'right', render: (_, record) => (
        <Space size="small" wrap>
          {healthMap[record.id]?.circuitBreakerState === 'OPEN' && (
            <Popconfirm title="确定解除封禁？" onConfirm={() => handleUnblock(record.id)}>
              <Button size="small" icon={<UnlockOutlined />}>解封</Button>
            </Popconfirm>
          )}
          <Button size="small" icon={<EditOutlined />} onClick={() => {
            setEditing(record)
            const formValues = { ...record, apiKey: undefined }
            if (formValues.modelIds && typeof formValues.modelIds === 'string') {
              // 将逗号分隔的模型ID转换为数组
              formValues.modelIds = formValues.modelIds.split(',').map(id => id.trim()).filter(id => id)
            }
            if (formValues.supportedLevels && typeof formValues.supportedLevels === 'string') {
              // 将逗号分隔的等级转换为数组
              formValues.supportedLevels = formValues.supportedLevels.split(',').map(l => l.trim()).filter(l => l)
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
      <Space style={{ marginBottom: 16 }} wrap>
        <Input.Search
          placeholder="按名称模糊搜索"
          allowClear
          onSearch={handleSearchName}
          style={{ width: 240 }}
          prefix={<SearchOutlined />}
        />
        <Select
          mode="multiple"
          placeholder="按模型搜索（可多选）"
          allowClear
          value={searchModelIds}
          onChange={handleSearchModelIds}
          options={allLocalModels.map(m => ({
            value: String(m.id),
            label: m.displayName ? `${m.name}（${m.displayName}）` : m.name
          }))}
          style={{ minWidth: 300 }}
          filterOption={(input, option) => (option?.label ?? '').toLowerCase().includes(input.toLowerCase())}
        />
      </Space>
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
                  opts.push({ value: sendValue, label: matched.displayName ? `${matched.name}（${matched.displayName}）` : matched.name, type: matched.type || 'text' })
                }
              })
            }
            // 兜底：如果没有匹配到，使用所有modelOptions
            if (opts.length === 0 && modelOptions.length > 0) {
              modelOptions.forEach(opt => {
                const matched = allLocalModels.find(m => String(m.id) === opt.value)
                if (matched) {
                  const sendValue = matched.displayName || matched.name
                  opts.push({ value: sendValue, label: matched.displayName ? `${matched.name}（${matched.displayName}）` : matched.name, type: matched.type || 'text' })
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
            <Select placeholder="选择类型" options={[{ value: 'openai', label: 'OpenAI' }, { value: 'azure', label: 'Azure' }, { value: 'claude', label: 'Claude' }, { value: 'gemini', label: 'Gemini' }, { value: 'custom', label: '自定义' }]} />
          </Form.Item>
          <Form.Item name="baseUrl" label="Base URL" rules={[{ required: true }]}><Input placeholder="https://api.openai.com" /></Form.Item>
          <Form.Item name="apiKey" label="API Key" rules={editing ? [] : [{ required: true }]}>
            <Input.Password placeholder={editing ? '留空则不修改' : 'sk-xxx'} />
          </Form.Item>
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
          <Form.Item name="supportedLevels" label="支持等级" initialValue={['1', '2', '3', '4', '5']}>
            <Select
              mode="multiple"
              placeholder="选择该渠道支持的用户等级"
              options={[1, 2, 3, 4, 5].map(l => ({ value: String(l), label: `Lv${l}` }))}
              style={{ width: '100%' }}
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
        onCancel={() => {
          testRunRef.current += 1
          setTestLoading(false)
          if (videoObjectUrlRef.current) {
            URL.revokeObjectURL(videoObjectUrlRef.current)
            videoObjectUrlRef.current = null
          }
          setTestModalOpen(false)
        }}
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
                <div style={{ background: '#f5f5f5', padding: 12, borderRadius: 6, whiteSpace: 'pre-wrap', wordBreak: 'break-word', fontSize: 14, minHeight: 80, textAlign: testResult.modelType === 'text' ? 'left' : 'center' }}>
                  {testResult.modelType === 'image' && testResult.mediaUrl && (
                    <Image src={testResult.mediaUrl} alt="渠道测试生成图片" style={{ maxHeight: 420, objectFit: 'contain' }} />
                  )}
                  {testResult.modelType === 'video' && testResult.mediaUrl && (
                    <video src={testResult.mediaUrl} controls style={{ width: '100%', maxHeight: 420 }} />
                  )}
                  {testResult.modelType === 'audio' && testResult.mediaUrl && (
                    <audio src={testResult.mediaUrl} controls style={{ width: '100%' }} />
                  )}
                  {testResult.modelType === 'video' && testResult.polling && (
                    <Space direction="vertical"><Spin /><span>视频生成中（{testResult.videoStatus || 'processing'}）...</span></Space>
                  )}
                  {testResult.modelType === 'text' && (streamContent || (testLoading ? '正在接收...' : '(空响应)'))}
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
