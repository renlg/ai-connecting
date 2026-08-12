import React from 'react'
import {
  Alert,
  Anchor,
  Button,
  Card,
  Col,
  Divider,
  Flex,
  Grid,
  Row,
  Space,
  Steps,
  Tag,
  Typography,
  message,
  theme,
} from 'antd'
import {
  ApiOutlined,
  CheckOutlined,
  CopyOutlined,
  KeyOutlined,
  RocketOutlined,
} from '@ant-design/icons'

const { Title, Paragraph, Text } = Typography
const { useBreakpoint } = Grid

function CodeBlock({ children }) {
  const { token } = theme.useToken()
  const [copied, setCopied] = React.useState(false)

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(children)
      setCopied(true)
      message.success('代码已复制')
      window.setTimeout(() => setCopied(false), 1500)
    } catch {
      message.error('复制失败，请手动选择代码')
    }
  }

  return (
    <div style={{ position: 'relative', margin: '12px 0 20px' }}>
      <Button
        type="text"
        size="small"
        icon={copied ? <CheckOutlined /> : <CopyOutlined />}
        onClick={copy}
        style={{ position: 'absolute', zIndex: 1, top: 8, right: 8, color: token.colorTextSecondary }}
      >
        {copied ? '已复制' : '复制'}
      </Button>
      <pre style={{
        margin: 0,
        padding: '42px 16px 16px',
        overflowX: 'auto',
        border: `1px solid ${token.colorBorderSecondary}`,
        borderRadius: token.borderRadiusLG,
        background: token.colorFillQuaternary,
        color: token.colorText,
        fontSize: 13,
        lineHeight: 1.65,
        whiteSpace: 'pre',
      }}><code>{children}</code></pre>
    </div>
  )
}

function Section({ id, title, children }) {
  return (
    <section id={id} style={{ scrollMarginTop: 24, marginBottom: 28 }}>
      <Title level={2} style={{ marginTop: 0, fontSize: 22 }}>{title}</Title>
      {children}
    </section>
  )
}

export default function UsageDocs() {
  const screens = useBreakpoint()
  const origin = window.location.origin
  const baseUrl = `${origin}/v1`

  const curlExample = `# 流式对话
curl ${baseUrl}/chat/completions \\
  -H "Authorization: Bearer <你的 API Key>" \\
  -H "Content-Type: application/json" \\
  -d '{
    "model": "<模型名或模型组名>",
    "messages": [{"role": "user", "content": "你好"}],
    "stream": true
  }'

# 非流式对话（将 stream 设为 false）
curl ${baseUrl}/chat/completions \\
  -H "Authorization: Bearer <你的 API Key>" \\
  -H "Content-Type: application/json" \\
  -d '{
    "model": "<模型名或模型组名>",
    "messages": [{"role": "user", "content": "用一句话介绍你自己"}],
    "stream": false
  }'`

  const pythonExample = `from openai import OpenAI

client = OpenAI(
    base_url="${baseUrl}",
    api_key="<你的 API Key>",
)

response = client.chat.completions.create(
    model="<模型名或模型组名>",
    messages=[{"role": "user", "content": "你好"}],
)
print(response.choices[0].message.content)`

  const nodeExample = `const response = await fetch("${baseUrl}/chat/completions", {
  method: "POST",
  headers: {
    "Authorization": "Bearer <你的 API Key>",
    "Content-Type": "application/json",
  },
  body: JSON.stringify({
    model: "<模型名或模型组名>",
    messages: [{ role: "user", content: "你好" }],
    stream: false,
  }),
});

if (!response.ok) throw new Error(await response.text());
console.log(await response.json());`

  const imageExample = `curl ${baseUrl}/images/generations \\
  -H "Authorization: Bearer <你的 API Key>" \\
  -H "Content-Type: application/json" \\
  -d '{
    "model": "<图片模型名>",
    "prompt": "一只在月球上散步的橘猫，电影感灯光",
    "n": 1,
    "size": "1024x1024"
  }'`

  const faqItems = [
    ['401 认证失败', '确认 API Key 完整且未被禁用，并检查 Authorization 是否使用 Bearer 格式。'],
    ['余额不足', '前往个人中心查看积分余额；充值或兑换积分后再发起请求。'],
    ['404 模型不存在', '模型名可能填写错误或当前 Token 无权使用；请以 Token 管理页展示的可用模型为准。'],
    ['429 请求过多', '已触发并发或频率限制，请降低请求速率并使用指数退避后重试。'],
    ['内容审核被拒', '调整提示词，移除违法、危险、侵权或其他不合规内容后重试。'],
  ]

  return (
    <div style={{ maxWidth: 1200, margin: '0 auto' }}>
      <Card bordered={false} style={{ marginBottom: 20 }}>
        <Space direction="vertical" size={8}>
          <Tag color="blue" icon={<RocketOutlined />}>API 接入指南</Tag>
          <Title level={1} style={{ margin: 0 }}>使用文档</Title>
          <Paragraph type="secondary" style={{ margin: 0, fontSize: 16 }}>
            通过 OpenAI 兼容接口调用文本、图片、视频和音频模型，同时支持 Claude 与 Gemini 协议。
          </Paragraph>
        </Space>
      </Card>

      <Row gutter={24} align="top">
        <Col xs={24} lg={18}>
          <Card>
            <Section id="quick-start" title="快速开始">
              <Steps
                direction={screens.md ? 'horizontal' : 'vertical'}
                responsive
                items={[
                  { title: '注册或登录', description: '进入账号控制台' },
                  { title: '创建 API Key', description: '前往「Token 管理」创建 Token' },
                  { title: '选择模型', description: '选择可用模型或模型组' },
                  { title: '发起调用', description: '使用 Base URL 与 API Key' },
                ]}
              />
              <Alert
                style={{ marginTop: 20 }}
                type="info"
                showIcon
                message="请妥善保存 API Key"
                description="API Key 代表你的账号权限，请勿写入公开代码仓库或发送给他人。"
              />
            </Section>

            <Divider />
            <Section id="base-url" title="Base URL 与端点">
              <Paragraph>客户端应使用当前站点的同源 API 地址：</Paragraph>
              <CodeBlock>{baseUrl}</CodeBlock>
              <Space direction="vertical" size={10} style={{ width: '100%' }}>
                <Text><Tag color="green">POST</Tag><Text code>/v1/chat/completions</Text> 对话补全，支持流式响应</Text>
                <Text><Tag color="blue">GET</Tag><Text code>/v1/models</Text> 获取可用模型列表</Text>
                <Text><Tag color="green">POST</Tag><Text code>/v1/images/generations</Text> 文生图</Text>
                <Text>视频与音频请使用对应媒体模型支持的端点和参数；可用能力以模型说明为准。</Text>
              </Space>
            </Section>

            <Divider />
            <Section id="authentication" title="认证方式">
              <Paragraph>所有 API 请求都应在请求头中携带 Token：</Paragraph>
              <CodeBlock>{`Authorization: Bearer <你的 API Key>\nContent-Type: application/json`}</CodeBlock>
            </Section>

            <Divider />
            <Section id="examples" title="调用示例">
              <Title level={3}>curl：流式与非流式对话</Title>
              <CodeBlock>{curlExample}</CodeBlock>
              <Title level={3}>Python：OpenAI SDK</Title>
              <CodeBlock>{pythonExample}</CodeBlock>
              <Title level={3}>Node.js：fetch</Title>
              <CodeBlock>{nodeExample}</CodeBlock>
              <Title level={3}>媒体生成：图片</Title>
              <CodeBlock>{imageExample}</CodeBlock>
            </Section>

            <Divider />
            <Section id="models" title="模型与模型组">
              <Paragraph>
                网关支持 OpenAI 兼容协议，并支持 Claude、Gemini 协议及文本、图片、视频、音频等模型类型。
                单模型按模型名调用；模型组（例如 <Text code>free</Text> 组）可直接将组名填写为 <Text code>model</Text> 调用，并按该组规则计费。
              </Paragraph>
              <Alert type="warning" showIcon message="具体可用模型、模型组及权限以「Token 管理」页展示为准。" />
            </Section>

            <Divider />
            <Section id="billing" title="计费说明">
              <Row gutter={[16, 16]}>
                <Col xs={24} md={12}>
                  <Card size="small" title={<><ApiOutlined /> 文本模型</>}>
                    按输入 token 与输出 token 分别折算积分，并按模型或模型组费率结算。
                  </Card>
                </Col>
                <Col xs={24} md={12}>
                  <Card size="small" title={<><KeyOutlined /> 媒体模型</>}>
                    图片、视频、音频通常按分辨率、时长等档位预扣积分，完成后按实际结果结算。
                  </Card>
                </Col>
              </Row>
            </Section>

            <Divider />
            <Section id="faq" title="常见问题">
              <Flex vertical gap={12}>
                {faqItems.map(([title, answer]) => (
                  <Card key={title} size="small" title={title}>{answer}</Card>
                ))}
              </Flex>
            </Section>
          </Card>
        </Col>

        <Col xs={0} lg={6}>
          <div style={{ position: 'sticky', top: 24 }}>
            <Card size="small" title="本页目录">
              <Anchor
                offsetTop={24}
                items={[
                  { key: 'quick-start', href: '#quick-start', title: '快速开始' },
                  { key: 'base-url', href: '#base-url', title: 'Base URL 与端点' },
                  { key: 'authentication', href: '#authentication', title: '认证方式' },
                  { key: 'examples', href: '#examples', title: '调用示例' },
                  { key: 'models', href: '#models', title: '模型与模型组' },
                  { key: 'billing', href: '#billing', title: '计费说明' },
                  { key: 'faq', href: '#faq', title: '常见问题' },
                ]}
              />
            </Card>
          </div>
        </Col>
      </Row>
    </div>
  )
}
