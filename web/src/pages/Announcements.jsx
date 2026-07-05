import React, { useEffect, useState } from 'react'
import { Table, Tag, Button, Modal, Form, Input, message, Switch, Popconfirm } from 'antd'
import { PlusOutlined, EditOutlined, DeleteOutlined } from '@ant-design/icons'
import { getAnnouncements, createAnnouncement, updateAnnouncement, deleteAnnouncement } from '../api'
import dayjs from 'dayjs'

export default function Announcements() {
  const [announcements, setAnnouncements] = useState([])
  const [loading, setLoading] = useState(false)
  const [modalOpen, setModalOpen] = useState(false)
  const [editingId, setEditingId] = useState(null)
  const [form] = Form.useForm()

  const load = () => {
    setLoading(true)
    getAnnouncements().then(res => {
      if (res.code === 200) setAnnouncements(res.data || [])
    }).finally(() => setLoading(false))
  }

  useEffect(() => { load() }, [])

  const handleSave = async () => {
    const values = await form.validateFields()
    try {
      let res
      if (editingId) {
        res = await updateAnnouncement(editingId, values)
      } else {
        res = await createAnnouncement(values)
      }
      if (res.code === 200) {
        message.success(editingId ? '更新成功' : '创建成功')
        setModalOpen(false)
        setEditingId(null)
        form.resetFields()
        load()
      } else {
        message.error(res.message || '操作失败')
      }
    } catch (err) {
      message.error(err?.message || '操作失败')
    }
  }

  const handleEdit = (record) => {
    setEditingId(record.id)
    form.setFieldsValue({
      title: record.title,
      content: record.content,
      status: record.status,
    })
    setModalOpen(true)
  }

  const handleDelete = async (id) => {
    try {
      const res = await deleteAnnouncement(id)
      if (res.code === 200) {
        message.success('删除成功')
        load()
      } else {
        message.error(res.message || '删除失败')
      }
    } catch (err) {
      message.error(err?.message || '删除失败')
    }
  }

  const handleToggleStatus = async (record, checked) => {
    const newStatus = checked ? 1 : 0
    try {
      const res = await updateAnnouncement(record.id, { 
        title: record.title, 
        content: record.content, 
        status: newStatus 
      })
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

  const columns = [
    { title: 'ID', dataIndex: 'id', width: 60 },
    { title: '标题', dataIndex: 'title', width: 200 },
    { title: '内容', dataIndex: 'content', ellipsis: true },
    {
      title: '状态',
      dataIndex: 'status',
      width: 100,
      render: (v, r) => (
        <Switch
          checked={v === 1}
          checkedChildren="显示"
          unCheckedChildren="隐藏"
          onChange={(checked) => handleToggleStatus(r, checked)}
        />
      ),
    },
    { title: '创建时间', dataIndex: 'createdAt', width: 180, render: v => v ? dayjs(v).format('YYYY-MM-DD HH:mm:ss') : '-' },
    {
      title: '操作',
      width: 150,
      fixed: 'right',
      render: (_, r) => (
        <>
          <Button type="link" icon={<EditOutlined />} onClick={() => handleEdit(r)}>编辑</Button>
          <Popconfirm
            title="确定要删除这条公告吗？"
            onConfirm={() => handleDelete(r.id)}
            okText="确定"
            cancelText="取消"
          >
            <Button type="link" danger icon={<DeleteOutlined />}>删除</Button>
          </Popconfirm>
        </>
      ),
    },
  ]

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
        <h2>公告管理</h2>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => {
          setEditingId(null)
          form.resetFields()
          setModalOpen(true)
        }}>
          新增公告
        </Button>
      </div>
      <Table columns={columns} dataSource={announcements} rowKey="id" loading={loading} scroll={{ x: 900 }} />

      {/* 新增/编辑弹窗 */}
      <Modal
        title={editingId ? '编辑公告' : '新增公告'}
        open={modalOpen}
        onOk={handleSave}
        onCancel={() => { setModalOpen(false); setEditingId(null); form.resetFields() }}
        okText="保存"
        cancelText="取消"
        width={600}
      >
        <Form form={form} layout="vertical" initialValues={{ status: 1 }}>
          <Form.Item name="title" label="标题" rules={[{ required: true, message: '请输入标题' }]}>
            <Input placeholder="请输入公告标题" maxLength={200} showCount />
          </Form.Item>
          <Form.Item name="content" label="内容" rules={[{ required: true, message: '请输入内容' }]}>
            <Input.TextArea rows={4} placeholder="请输入公告内容" />
          </Form.Item>
          <Form.Item name="status" label="状态" valuePropName="checked">
            <Switch checkedChildren="显示" unCheckedChildren="隐藏" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  )
}
