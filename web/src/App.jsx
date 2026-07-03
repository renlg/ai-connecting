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
import Coupons from './pages/Coupons'

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
          <Route path="coupons" element={<Coupons />} />
        </Route>
      </Routes>
    </BrowserRouter>
  )
}
