import React, { useEffect, useRef, useState } from 'react'
import { Table, Button, Modal, Form, Input, InputNumber, Select, Space, Tag, message, Popconfirm, Switch, Typography } from 'antd'
import { PlusOutlined, DeleteOutlined, EditOutlined, ArrowUpOutlined, ArrowDownOutlined } from '@ant-design/icons'
import {
  getModels, getModelGroups, createModelGroup, updateModelGroup, deleteModelGroup, updateModelGroupStatus,
} from '../api'

const { Text } = Typography

const TYPE_META = {
  text: { color: 'default', label: '文本' },
  image: { color: 'purple', label: '图片' },
  video: { color: 'orange', label: '视频' },
  audio: { color: 'cyan', label: '音频' },
}

const STRATEGY_META = {
  round_robin: '轮询',
  priority: '优先级',
  random: '随机',
}

const groupPriceSummary = (group) => {
  if (group.type === 'text') return `输入 ${group.inputPrice ?? 0} / 输出 ${group.outputPrice ?? 0} / 缓存 ${group.cachedPrice ?? 0}（积分/百万token）`
  if (group.type === 'image') return `1K ${group.price1k ?? 0} / 2K ${group.price2k ?? 0} / 4K ${group.price4k ?? 0}`
  if (group.type === 'video') return `480P ${group.price480p ?? 0} / 720P ${group.price720p ?? 0} / 1080P ${group.price1080p ?? 0} / 4K ${group.videoPrice4k ?? 0}`
  return `标准 ${group.priceStandard ?? 0} / 高清 ${group.priceHd ?? 0}`
}

export default function ModelGroups() {
  const [groups, setGroups] = useState([])
  const [allModels, setAllModels] = useState([])
  const [groupLoading, setGroupLoading] = useState(false)
  const [groupModalOpen, setGroupModalOpen] = useState(false)
  const [saveLoading, setSaveLoading] = useState(false)
  const saveLockRef = useRef(false)
  const [editingGroup, setEditingGroup] = useState(null)
  const [groupMembers, setGroupMembers] = useState([])
  const [groupForm] = Form.useForm()
  const groupType = Form.useWatch('type', groupForm) || 'text'
  const groupStrategy = Form.useWatch('strategy', groupForm)

  const loadGroups = () => {
    setGroupLoading(true)
    getModelGroups().then(res => {
      if (res.code === 200) {
        setGroups((res.data || []).map(item => ({ ...item.group, members: item.members || [] })))
      }
    }).catch(err => message.error(err?.message || '模型组加载失败'))
      .finally(() => setGroupLoading(false))
  }

  const loadModels = () => {
    getModels().then(res => {
      if (res.code === 200) setAllModels(res.data || [])
    }).catch(err => message.error(err?.message || '模型加载失败'))
  }

  useEffect(() => {
    loadGroups()
    loadModels()
  }, [])

  const openCreateGroup = () => {
    setEditingGroup(null)
    setGroupMembers([])
    groupForm.resetFields()
    groupForm.setFieldsValue({ type: 'text', strategy: 'round_robin', maxAttempts: 5, enabled: true, adminOnly: false, supportedLevels: ['1', '2', '3', '4', '5'] })
    setGroupModalOpen(true)
  }

  const openEditGroup = (group) => {
    setEditingGroup(group)
    setGroupMembers((group.members || []).map(member => ({ modelConfigId: member.modelConfigId, weight: member.weight || 1 })))
    groupForm.resetFields()
    const formValues = { ...group }
    if (formValues.supportedLevels && typeof formValues.supportedLevels === 'string') {
      formValues.supportedLevels = formValues.supportedLevels.split(',').map(l => l.trim()).filter(l => l)
    }
    if (!formValues.supportedLevels || formValues.supportedLevels.length === 0) {
      formValues.supportedLevels = ['1', '2', '3', '4', '5']
    }
    groupForm.setFieldsValue(formValues)
    setGroupModalOpen(true)
  }

  const handleGroupMemberSelection = (ids) => {
    setGroupMembers(current => ids.map(id => current.find(member => member.modelConfigId === id) || { modelConfigId: id, weight: 1 }))
  }

  const moveGroupMember = (index, offset) => {
    setGroupMembers(current => {
      const next = [...current]
      const target = index + offset
      if (target < 0 || target >= next.length) return current
      ;[next[index], next[target]] = [next[target], next[index]]
      return next
    })
  }

  const handleGroupSave = async () => {
    if (saveLockRef.current) return
    saveLockRef.current = true
    setSaveLoading(true)
    try {
      const values = await groupForm.validateFields()
      if (Array.isArray(values.supportedLevels)) {
        values.supportedLevels = values.supportedLevels.length === 5 ? '' : values.supportedLevels.join(',')
      }
      const payload = { ...values, members: groupMembers }
      if (editingGroup) {
        await updateModelGroup(editingGroup.id, payload)
        message.success('模型组更新成功')
      } else {
        await createModelGroup(payload)
        message.success('模型组创建成功')
      }
      setGroupModalOpen(false)
      setEditingGroup(null)
      setGroupMembers([])
      groupForm.resetFields()
      loadGroups()
    } catch (err) {
      if (!err?.errorFields) message.error(err?.message || '模型组保存失败')
    } finally {
      saveLockRef.current = false
      setSaveLoading(false)
    }
  }

  const handleGroupDelete = async (id) => {
    try {
      await deleteModelGroup(id)
      message.success('模型组删除成功')
      loadGroups()
    } catch (err) {
      Modal.error({ title: '无法删除模型组', content: err?.message || '删除失败' })
    }
  }

  const handleGroupStatusChange = async (id, enabled) => {
    try {
      await updateModelGroupStatus(id, enabled)
      message.success('模型组状态已更新')
      loadGroups()
    } catch (err) {
      message.error(err?.message || '模型组状态更新失败')
    }
  }

  const groupColumns = [
    { title: '名称', dataIndex: 'name', width: 160, render: value => <Tag color="geekblue">{value}</Tag> },
    { title: '类型', dataIndex: 'type', width: 80, render: value => <Tag color={TYPE_META[value]?.color}>{TYPE_META[value]?.label || value}</Tag> },
    { title: '策略', dataIndex: 'strategy', width: 100, render: value => STRATEGY_META[value] || value },
    { title: '最多尝试', dataIndex: 'maxAttempts', width: 90 },
    { title: '启用', dataIndex: 'enabled', width: 70, render: (value, record) => <Switch checked={!!value} onChange={checked => handleGroupStatusChange(record.id, checked)} /> },
    { title: '仅管理员', dataIndex: 'adminOnly', width: 90, render: value => value ? <Tag color="gold">是</Tag> : '否' },
    { title: '价格摘要', width: 300, render: (_, record) => groupPriceSummary(record) },
    { title: '支持等级', width: 150, render: (_, record) => {
      const v = record.supportedLevels
      if (!v) return '全部'
      return v.split(',').map(l => l.trim()).filter(l => l).map(l => <Tag key={l} color="purple">Lv{l}</Tag>)
    } },
    { title: '成员数', width: 80, render: (_, record) => record.members?.length || 0 },
    {
      title: '操作', width: 160, fixed: 'right', render: (_, record) => (
        <Space size="small">
          <Button size="small" icon={<EditOutlined />} onClick={() => openEditGroup(record)}>编辑</Button>
          <Popconfirm title="确定删除该模型组？" description="有成员的模型组需先在编辑窗口移除全部成员。" onConfirm={() => handleGroupDelete(record.id)}>
            <Button size="small" danger icon={<DeleteOutlined />}>删除</Button>
          </Popconfirm>
        </Space>
      )
    },
  ]

  const eligibleMemberModels = allModels.filter(model => (model.type || 'text') === groupType)

  return (
    <div>
      <div className="mobile-wrap-toolbar" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
        <div>
          <h2 style={{ marginBottom: 4 }}>模型组</h2>
          <Text type="secondary">为客户端提供统一模型名，并按策略在同类型成员间切换。</Text>
        </div>
        <Button type="primary" icon={<PlusOutlined />} onClick={openCreateGroup}>新增模型组</Button>
      </div>

      <Table columns={groupColumns} dataSource={groups} rowKey="id" loading={groupLoading} scroll={{ x: 1200 }} />

      <Modal title={editingGroup ? '编辑模型组' : '新增模型组'} open={groupModalOpen} onOk={handleGroupSave} onCancel={() => setGroupModalOpen(false)} confirmLoading={saveLoading} okButtonProps={{ disabled: saveLoading }} width={680}>
        <Form form={groupForm} layout="vertical">
          <Form.Item name="name" label="模型组名称" rules={[{ required: true, message: '请输入模型组名称' }]}><Input placeholder="客户端请求时使用的公开 model 值" /></Form.Item>
          <Space className="mobile-form-row" style={{ display: 'flex' }} align="start">
            <Form.Item name="type" label="类型" rules={[{ required: true }]} style={{ width: 180 }}>
              <Select options={Object.entries(TYPE_META).map(([value, meta]) => ({ value, label: meta.label }))} onChange={() => setGroupMembers([])} />
            </Form.Item>
            <Form.Item name="strategy" label="成员策略" rules={[{ required: true }]} style={{ width: 180 }}>
              <Select options={Object.entries(STRATEGY_META).map(([value, label]) => ({ value, label }))} />
            </Form.Item>
            <Form.Item name="maxAttempts" label="最多尝试次数" rules={[{ required: true }]} style={{ width: 180 }}>
              <InputNumber min={1} max={8} style={{ width: '100%' }} />
            </Form.Item>
          </Space>
          <Space size="large">
            <Form.Item name="enabled" label="启用" valuePropName="checked"><Switch /></Form.Item>
            <Form.Item name="adminOnly" label="仅管理员可用" valuePropName="checked"><Switch /></Form.Item>
          </Space>
          <Form.Item name="supportedLevels" label="支持等级" tooltip="不选表示对所有等级开放">
            <Select
              mode="multiple"
              placeholder="不选表示对所有等级开放"
              options={[1, 2, 3, 4, 5].map(l => ({ value: String(l), label: `Lv${l}` }))}
              style={{ width: '100%' }}
            />
          </Form.Item>

          {groupType === 'text' && <Space className="mobile-form-row" style={{ display: 'flex' }} align="start">
            <Form.Item name="inputPrice" label="输入价格（积分/百万token）"><InputNumber min={0} style={{ width: 190 }} /></Form.Item>
            <Form.Item name="outputPrice" label="输出价格（积分/百万token）"><InputNumber min={0} style={{ width: 190 }} /></Form.Item>
            <Form.Item name="cachedPrice" label="缓存价格（积分/百万token）"><InputNumber min={0} style={{ width: 190 }} /></Form.Item>
          </Space>}
          {groupType === 'image' && <Space className="mobile-form-row" style={{ display: 'flex' }} align="start">
            <Form.Item name="price1k" label="1K（积分/张）"><InputNumber min={0} style={{ width: 190 }} /></Form.Item>
            <Form.Item name="price2k" label="2K（积分/张）"><InputNumber min={0} style={{ width: 190 }} /></Form.Item>
            <Form.Item name="price4k" label="4K（积分/张）"><InputNumber min={0} style={{ width: 190 }} /></Form.Item>
          </Space>}
          {groupType === 'video' && <Space className="mobile-form-row" style={{ display: 'flex', flexWrap: 'wrap' }} align="start">
            <Form.Item name="price480p" label="480P（积分/秒）"><InputNumber min={0} style={{ width: 140 }} /></Form.Item>
            <Form.Item name="price720p" label="720P（积分/秒）"><InputNumber min={0} style={{ width: 140 }} /></Form.Item>
            <Form.Item name="price1080p" label="1080P（积分/秒）"><InputNumber min={0} style={{ width: 140 }} /></Form.Item>
            <Form.Item name="videoPrice4k" label="4K（积分/秒）"><InputNumber min={0} style={{ width: 140 }} /></Form.Item>
          </Space>}
          {groupType === 'audio' && <Space className="mobile-form-row" style={{ display: 'flex' }} align="start">
            <Form.Item name="priceStandard" label="标准（积分/秒）"><InputNumber min={0} style={{ width: 220 }} /></Form.Item>
            <Form.Item name="priceHd" label="高清（积分/秒）"><InputNumber min={0} style={{ width: 220 }} /></Form.Item>
          </Space>}

          <Form.Item label="成员模型">
            <Select mode="multiple" value={groupMembers.map(member => member.modelConfigId)} onChange={handleGroupMemberSelection} placeholder="只显示与模型组相同类型的模型" optionFilterProp="label" options={eligibleMemberModels.map(model => ({
              value: model.id,
              label: `${model.displayName || model.name}（${model.name}）`,
            }))} />
          </Form.Item>
          {groupMembers.map((member, index) => {
            const model = allModels.find(item => item.id === member.modelConfigId)
            return (
              <div key={member.modelConfigId} style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '8px 12px', marginBottom: 8, background: '#fafafa', borderRadius: 6 }}>
                <Text style={{ flex: 1 }}>{index + 1}. {model?.displayName || model?.name || `模型 #${member.modelConfigId}`}</Text>
                <Text type="secondary">权重</Text>
                {groupStrategy === 'random'
                  ? <InputNumber min={1} value={member.weight} onChange={value => setGroupMembers(current => current.map((item, itemIndex) => itemIndex === index ? { ...item, weight: value || 1 } : item))} style={{ width: 80 }} />
                  : <Text style={{ width: 80 }}>—</Text>}
                <Button aria-label="上移" icon={<ArrowUpOutlined />} disabled={index === 0} onClick={() => moveGroupMember(index, -1)} />
                <Button aria-label="下移" icon={<ArrowDownOutlined />} disabled={index === groupMembers.length - 1} onClick={() => moveGroupMember(index, 1)} />
              </div>
            )
          })}
        </Form>
      </Modal>
    </div>
  )
}
