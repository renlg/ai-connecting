import axios from 'axios';

const api = axios.create({
  baseURL: '',
  timeout: 30000,
});

api.interceptors.request.use((config) => {
  const token = localStorage.getItem('token');
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

api.interceptors.response.use(
  (response) => response.data,
  (error) => {
    if (error.response?.status === 401) {
      localStorage.removeItem('token');
      localStorage.removeItem('user');
      window.location.href = '/login';
    }
    return Promise.reject(error.response?.data || error);
  }
);

// Auth
export const login = (data) => api.post('/api/auth/login', data);
export const register = (data) => api.post('/api/auth/register', data);

// User
export const getProfile = () => api.get('/api/user/profile');
export const updateProfile = (data) => api.put('/api/user/profile', data);
export const changePassword = (data) => api.put('/api/user/password', data);

// Channels (Admin)
export const getChannels = () => api.get('/api/admin/channels');
export const getChannel = (id) => api.get(`/api/admin/channels/${id}`);
export const createChannel = (data) => api.post('/api/admin/channels', data);
export const updateChannel = (id, data) => api.put(`/api/admin/channels/${id}`, data);
export const deleteChannel = (id) => api.delete(`/api/admin/channels/${id}`);
export const updateChannelStatus = (id, status) => api.put(`/api/admin/channels/${id}/status`, { status });
export const testChannel = (id) => api.post(`/api/admin/channels/${id}/test`);

// Tokens
export const getTokens = (search) => api.get('/api/tokens', { params: search ? { search } : {} });
export const getToken = (id) => api.get(`/api/tokens/${id}`);
export const createToken = (data) => api.post('/api/tokens', data);
export const updateToken = (id, data) => api.put(`/api/tokens/${id}`, data);
export const deleteToken = (id) => api.delete(`/api/tokens/${id}`);
export const updateTokenStatus = (id, status) => api.put(`/api/tokens/${id}/status`, { status });
export const getTokenCreditHistory = (id) => api.get(`/api/tokens/${id}/credit-history`);

// Models (Admin)
export const getModels = () => api.get('/api/admin/models');
export const getEnabledModels = () => api.get('/api/admin/models/enabled');
export const createModel = (data) => api.post('/api/admin/models', data);
export const updateModel = (id, data) => api.put(`/api/admin/models/${id}`, data);
export const deleteModel = (id) => api.delete(`/api/admin/models/${id}`);
export const updateModelStatus = (id, status) => api.put(`/api/admin/models/${id}/status`, { status });
export const batchCreateModels = (names) => api.post('/api/admin/models/batch', { names });

// Admin
export const getDashboard = () => api.get('/api/admin/dashboard');
export const getUsers = (search) => api.get('/api/admin/users', { params: search ? { search } : {} });
export const updateUserStatus = (id, status) => api.put(`/api/admin/users/${id}/status`, { status });
export const resetUserPassword = (id) => api.put(`/api/admin/users/${id}/reset-password`);
export const updateUserCredits = (id, credits) => api.put(`/api/admin/users/${id}/credits`, { credits });
export const getLogs = (page = 0, size = 20) => api.get(`/api/admin/logs?page=${page}&size=${size}`);

// Coupons
export const redeemCoupon = (code) => api.post('/api/user/coupons/redeem', { code });
export const getCoupons = () => api.get('/api/admin/coupons');
export const generateCoupon = (data) => api.post('/api/admin/coupons', data);
export const updateCouponStatus = (id, status) => api.put(`/api/admin/coupons/${id}/status`, { status });
export const getCouponRedemptions = (id) => api.get(`/api/admin/coupons/${id}/redemptions`);

export default api;
