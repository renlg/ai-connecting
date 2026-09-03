import React, { Suspense, lazy, useEffect, useState } from 'react'
import { BrowserRouter, Routes, Route, Navigate, useOutletContext } from 'react-router-dom'
import Layout from './components/Layout'
import { getProfile } from './api'

const Login = lazy(() => import('./pages/Login'))
const Dashboard = lazy(() => import('./pages/Dashboard'))
const Channels = lazy(() => import('./pages/Channels'))
const Tokens = lazy(() => import('./pages/Tokens'))
const Profile = lazy(() => import('./pages/Profile'))
const Users = lazy(() => import('./pages/Users'))
const Models = lazy(() => import('./pages/Models'))
const ModelGroups = lazy(() => import('./pages/ModelGroups'))
const Coupons = lazy(() => import('./pages/Coupons'))
const InviteCodes = lazy(() => import('./pages/InviteCodes'))
const Announcements = lazy(() => import('./pages/Announcements'))
const FailureLogs = lazy(() => import('./pages/FailureLogs'))
const ChannelFailureLogs = lazy(() => import('./pages/ChannelFailureLogs'))
const Cost = lazy(() => import('./pages/Cost'))
const UsageDocs = lazy(() => import('./pages/UsageDocs'))
const RateLimitStrategies = lazy(() => import('./pages/RateLimitStrategies'))
const FailureStrategies = lazy(() => import('./pages/FailureStrategies'))
const CircuitBreakers = lazy(() => import('./pages/CircuitBreakers'))
const NotFound = lazy(() => import('./pages/NotFound'))

function AuthenticatedLayout() {
  const [user, setUser] = useState()
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    getProfile()
      .then(res => setUser(res.code === 200 ? res.data : null))
      .catch(() => setUser(null))
      .finally(() => setLoading(false))
  }, [])

  if (loading) return <div style={{ padding: 48, textAlign: 'center' }}>正在加载...</div>
  if (!user) return <Navigate to="/login" replace />
  return <Layout user={user} />
}

function AdminRoute({ children }) {
  const { user } = useOutletContext()
  return user.role === 'admin' ? children : <Navigate to="/" replace />
}

export default function App() {
  return (
    <BrowserRouter>
      <Suspense fallback={<div style={{ padding: 48, textAlign: 'center' }}>正在加载...</div>}>
      <Routes>
        <Route path="/login" element={<Login />} />
        <Route path="/" element={<AuthenticatedLayout />}>
          <Route index element={<Dashboard />} />
          <Route path="channels" element={<Channels />} />
          <Route path="tokens" element={<Tokens />} />
          <Route path="profile" element={<Profile />} />
          <Route path="users" element={<Users />} />
          <Route path="models" element={<Models />} />
          <Route path="model-groups" element={<ModelGroups />} />
          <Route path="coupons" element={<Coupons />} />
          <Route path="invite-codes" element={<AdminRoute><InviteCodes /></AdminRoute>} />
          <Route path="announcements" element={<Announcements />} />
          <Route path="failure-logs" element={<Navigate to="/failure-logs/request" replace />} />
          <Route path="failure-logs/channel" element={<ChannelFailureLogs />} />
          <Route path="failure-logs/request" element={<FailureLogs />} />
          <Route path="cost" element={<AdminRoute><Cost /></AdminRoute>} />
          <Route path="risk/rate-limit" element={<RateLimitStrategies />} />
          <Route path="risk/failure" element={<FailureStrategies />} />
          <Route path="risk/circuit-breaker" element={<CircuitBreakers />} />
          <Route path="docs" element={<UsageDocs />} />
          <Route path="*" element={<NotFound />} />
        </Route>
        <Route path="*" element={<NotFound />} />
      </Routes>
      </Suspense>
    </BrowserRouter>
  )
}
