import React from 'react'
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import Login from './pages/Login'
import Layout from './components/Layout'
import Dashboard from './pages/Dashboard'
import Channels from './pages/Channels'
import Tokens from './pages/Tokens'
import Profile from './pages/Profile'
import Users from './pages/Users'
import Models from './pages/Models'
import ModelGroups from './pages/ModelGroups'
import Coupons from './pages/Coupons'
import Announcements from './pages/Announcements'
import FailureLogs from './pages/FailureLogs'
import Cost from './pages/Cost'
import UsageDocs from './pages/UsageDocs'
import NotFound from './pages/NotFound'

function PrivateRoute({ children }) {
  const token = localStorage.getItem('token')

  if (!token) {
    return <Navigate to="/login" replace />
  }

  try {
    const payload = JSON.parse(atob(token.split('.')[1]))
    if (payload.exp && payload.exp * 1000 < Date.now()) {
      localStorage.clear()
      return <Navigate to="/login" replace />
    }
  } catch {
    localStorage.clear()
    return <Navigate to="/login" replace />
  }

  return children
}

function AdminRoute({ children }) {
  const user = JSON.parse(localStorage.getItem('user') || '{}')
  return user.role === 'admin' ? children : <Navigate to="/" replace />
}

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/login" element={<Login />} />
        <Route path="/" element={<PrivateRoute><Layout /></PrivateRoute>}>
          <Route index element={<Dashboard />} />
          <Route path="channels" element={<Channels />} />
          <Route path="tokens" element={<Tokens />} />
          <Route path="profile" element={<Profile />} />
          <Route path="users" element={<Users />} />
          <Route path="models" element={<Models />} />
          <Route path="model-groups" element={<ModelGroups />} />
          <Route path="coupons" element={<Coupons />} />
          <Route path="announcements" element={<Announcements />} />
          <Route path="failure-logs" element={<FailureLogs />} />
          <Route path="cost" element={<AdminRoute><Cost /></AdminRoute>} />
          <Route path="docs" element={<UsageDocs />} />
          <Route path="*" element={<NotFound />} />
        </Route>
        <Route path="*" element={<NotFound />} />
      </Routes>
    </BrowserRouter>
  )
}
