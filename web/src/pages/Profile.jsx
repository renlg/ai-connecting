import React, { useEffect, useState } from 'react'
import { Card, Form, Input, Button, message, Descriptions, Divider } from 'antd'
import { getProfile, updateProfile, changePassword } from '../api'

export default function Profile() {
  const [user, setUser] = useState(null)
  const [profileForm] = Form.useForm()
  const [pwdForm] = Form.useForm()

  const load = () => {
    getProfile().then(res => {
      if (res.code === 200) {
        setUser(res.data)
        profileForm.setFieldsValue({ nickname: res.data.nickname, email: res.data.email })
      }
    })
  }

  useEffect(() => { load() }, [])

  const handleUpdateProfile = async (values) => {
    await updateProfile(values)
    message.success('更新成功')
    load()
  }

  const handleChangePassword = async (values) => {
    await changePassword(values)
    message.success('密码修改成功')
    pwdForm.resetFields()
  }

  if (!user) return null

  return (
    <div>
      <h2 style={{ marginBottom: 24 }}>个人中心</h2>
      <Card title="用户信息" style={{ marginBottom: 24 }}>
        <Descriptions column={2}>
          <Descriptions.Item label="用户名">{user.username}</Descriptions.Item>
          <Descriptions.Item label="角色">{user.role === 'admin' ? '管理员' : '普通用户'}</Descriptions.Item>
          <Descriptions.Item label="额度">{user.quota === -1 ? '无限' : user.quota}</Descriptions.Item>
          <Descriptions.Item label="已用额度">{user.usedQuota}</Descriptions.Item>
          <Descriptions.Item label="创建时间">{user.createdAt}</Descriptions.Item>
        </Descriptions>
      </Card>

      <Card title="修改资料" style={{ marginBottom: 24 }}>
        <Form form={profileForm} onFinish={handleUpdateProfile} style={{ maxWidth: 400 }}>
          <Form.Item name="nickname" label="昵称"><Input /></Form.Item>
          <Form.Item name="email" label="邮箱"><Input /></Form.Item>
          <Form.Item><Button type="primary" htmlType="submit">保存</Button></Form.Item>
        </Form>
      </Card>

      <Card title="修改密码">
        <Form form={pwdForm} onFinish={handleChangePassword} style={{ maxWidth: 400 }}>
          <Form.Item name="oldPassword" label="原密码" rules={[{ required: true }]}><Input.Password /></Form.Item>
          <Form.Item name="newPassword" label="新密码" rules={[{ required: true, min: 6, message: '至少6位' }]}><Input.Password /></Form.Item>
          <Form.Item name="confirmPassword" label="确认密码" dependencies={['newPassword']}
            rules={[{ required: true }, ({ getFieldValue }) => ({
              validator(_, value) {
                return value === getFieldValue('newPassword') ? Promise.resolve() : Promise.reject(new Error('两次密码不一致'))
              }
            })]}>
            <Input.Password />
          </Form.Item>
          <Form.Item><Button type="primary" htmlType="submit">修改密码</Button></Form.Item>
        </Form>
      </Card>
    </div>
  )
}
