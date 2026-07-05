import React from 'react'
import { Button, Result } from 'antd'
import { useNavigate } from 'react-router-dom'

export default function NotFound() {
  const navigate = useNavigate()

  return (
    <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: '100vh', background: '#f0f2f5' }}>
      <Result
        status="404"
        title="404"
        subTitle="抱歉，您访问的页面不存在"
        extra={
          <Button type="primary" onClick={() => navigate('/')}>
            返回首页
          </Button>
        }
      />
    </div>
  )
}
