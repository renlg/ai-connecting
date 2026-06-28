import React, { useEffect, useState } from 'react'
import { Table, Tag, Switch, message } from 'antd'
import { getUsers, updateUserStatus } from '../api'
import dayjs from 'dayjs'

export default function Users() {
  const [users, setUsers] = useState([])
  const [loading, setLoading] = useState(false)

  const load = () => {
    setLoading(true)
    getUsers().then(res => {
      if (res.code === 200) setUsers(res.data || [])
    }).finally(() => setLoading(false))
  }

  useEffect(() => { load() }, [])

  const handleStatusChange = async (id, status) => {
    await updateUserStatus(id, status ? 1 : 0)
    message.success('状态已更新')
    load()
  }

  const columns = [
    { title: 'ID', dataIndex: 'id', width: 60 },
    { title: '用户名', dataIndex: 'username', width: 120 },
    { title: '昵称', dataIndex: 'nickname', width: 120 },
    { title: '邮箱', dataIndex: 'email' },
    { title: '角色', dataIndex: 'role', width: 100, render: v => <Tag color={v === 'admin' ? 'red' : 'blue'}>{v === 'admin' ? '管理员' : '用户'}</Tag> },
    { title: '额度', dataIndex: 'quota', width: 100, render: v => v === -1 ? '无限' : v },
    { title: '已用额度', dataIndex: 'usedQuota', width: 100 },
    { title: '状态', dataIndex: 'status', width: 80, render: (v, r) => <Switch checked={v === 1} onChange={(c) => handleStatusChange(r.id, c)} /> },
    { title: '注册时间', dataIndex: 'createdAt', width: 170, render: v => v ? dayjs(v).format('YYYY-MM-DD HH:mm') : '-' },
  ]

  return (
    <div>
      <h2 style={{ marginBottom: 16 }}>用户管理</h2>
      <Table columns={columns} dataSource={users} rowKey="id" loading={loading} scroll={{ x: 1000 }} />
    </div>
  )
}
