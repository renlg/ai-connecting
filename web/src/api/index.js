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
export const getInviteCode = () => api.get('/api/user/invite-code');

// Channels (Admin)
export const getChannels = (params) => api.get('/api/admin/channels', { params });
export const createChannel = (data) => api.post('/api/admin/channels', data);
export const updateChannel = (id, data) => api.put(`/api/admin/channels/${id}`, data);
export const deleteChannel = (id) => api.delete(`/api/admin/channels/${id}`);
export const updateChannelStatus = (id, status) => api.put(`/api/admin/channels/${id}/status`, { status });
export const getChannelHealth = () => api.get('/api/admin/channels/health');
export const getChannelApiKey = (id) => api.get(`/api/admin/channels/${id}/apikey`);
export const unblockChannel = (id) => api.post(`/api/admin/channels/${id}/unblock`);
export const fetchChannelModels = (data) => api.post('/api/admin/channels/fetch-models', data);
export const testChannelMedia = (data) => api.post('/api/admin/channels/test-media', data, { timeout: 120000 });
export const pollChannelTestVideo = (data) => api.post('/api/admin/channels/test-video-status', data, { timeout: 120000 });
export const downloadChannelTestVideo = (data) => api.post('/api/admin/channels/test-video-content', data, { responseType: 'blob', timeout: 120000 });

// 流式聊天测试的共享实现
async function createChatStream(url, data, onChunk, onComplete, onError) {
  const token = localStorage.getItem('token');
  const response = await fetch(url, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {})
    },
    body: JSON.stringify(data)
  });

  if (!response.ok) {
    throw new Error(`HTTP ${response.status}: ${response.statusText}`);
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split('\n');
      buffer = lines.pop() || '';

      for (const line of lines) {
        const trimmed = line.trim();
        if (!trimmed) continue;
        if (trimmed === 'data: [DONE]') {
          onComplete && onComplete();
          return;
        }
        if (trimmed.startsWith('data: ')) {
          try {
            const json = JSON.parse(trimmed.slice(6));
            onChunk && onChunk(json);
          } catch (e) {
            console.error('Parse SSE error:', e, trimmed);
          }
        }
      }
    }
    onComplete && onComplete();
  } catch (err) {
    onError && onError(err);
    throw err;
  }
}

// 流式测试渠道聊天（返回 ReadableStream）
export async function testChannelChatStream(data, onChunk, onComplete, onError) {
  return createChatStream('/api/admin/channels/test-chat-stream', data, onChunk, onComplete, onError);
}

// Token 流式测试
export async function testTokenChatStream(data, onChunk, onComplete, onError) {
  return createChatStream('/api/tokens/test-chat-stream', data, onChunk, onComplete, onError);
}

// Tokens
export const getTokens = (search) => api.get('/api/tokens', { params: search ? { search } : {} });
export const getToken = (id) => api.get(`/api/tokens/${id}`);
export const createToken = (data) => api.post('/api/tokens', data);
export const updateToken = (id, data) => api.put(`/api/tokens/${id}`, data);
export const deleteToken = (id) => api.delete(`/api/tokens/${id}`);
export const updateTokenStatus = (id, status) => api.put(`/api/tokens/${id}/status`, { status });
export const getTokenCreditHistory = (id) => api.get(`/api/tokens/${id}/credit-history`);
export const getTokenCreditHistoryDetail = (id, date) => api.get(`/api/tokens/${id}/credit-history/${date}`);
export const getTokenModels = () => api.get('/api/tokens/models');

// Models (Admin)
export const getModels = (params) => api.get('/api/admin/models', { params });
export const getEnabledModels = () => api.get('/api/admin/models/enabled');
export const createModel = (data) => api.post('/api/admin/models', data);
export const updateModel = (id, data) => api.put(`/api/admin/models/${id}`, data);
export const deleteModel = (id) => api.delete(`/api/admin/models/${id}`);
export const updateModelStatus = (id, status) => api.put(`/api/admin/models/${id}/status`, { status });

// Model Groups (Admin)
export const getModelGroups = () => api.get('/api/admin/model-groups');
export const getModelGroup = (id) => api.get(`/api/admin/model-groups/${id}`);
export const createModelGroup = (data) => api.post('/api/admin/model-groups', data);
export const updateModelGroup = (id, data) => api.put(`/api/admin/model-groups/${id}`, data);
export const deleteModelGroup = (id) => api.delete(`/api/admin/model-groups/${id}`);
export const updateModelGroupStatus = (id, enabled) => api.put(`/api/admin/model-groups/${id}/status`, { enabled });

// Admin
export const getDashboard = () => api.get('/api/admin/dashboard');
export const getDailyStats = (days = 7) => api.get('/api/admin/dashboard/daily-stats', { params: { days } });
export const getBlockedChannels = () => api.get('/api/admin/channels/blocked');
export const getUsers = (search) => api.get('/api/admin/users', { params: search ? { search } : {} });
export const updateUserStatus = (id, status) => api.put(`/api/admin/users/${id}/status`, { status });
export const resetUserPassword = (id) => api.put(`/api/admin/users/${id}/reset-password`);
export const updateUserCredits = (id, credits) => api.put(`/api/admin/users/${id}/credits`, { credits });
export const updateUserLevel = (id, level) => api.put(`/api/admin/users/${id}/level`, { level });
export const getFailureLogs = (params) => api.get('/api/admin/failure-logs', { params });
export const getCostAggregate = (params) => api.get('/api/admin/cost/aggregate', { params });
export const getCostModels = (params) => api.get('/api/admin/cost/models', { params });
export const exportCostCsv = (params) => api.get('/api/admin/cost/export', { params, responseType: 'blob' });

// Announcements
export const getLatestAnnouncements = (limit = 5) => api.get('/api/announcements/latest', { params: { limit } });
export const getAnnouncements = () => api.get('/api/admin/announcements');
export const createAnnouncement = (data) => api.post('/api/admin/announcements', data);
export const updateAnnouncement = (id, data) => api.put(`/api/admin/announcements/${id}`, data);
export const deleteAnnouncement = (id) => api.delete(`/api/admin/announcements/${id}`);

// Coupons
export const redeemCoupon = (code) => api.post('/api/user/coupons/redeem', { code });
export const getCoupons = () => api.get('/api/admin/coupons');
export const generateCoupon = (data) => api.post('/api/admin/coupons', data);
export const updateCouponStatus = (id, status) => api.put(`/api/admin/coupons/${id}/status`, { status });
export const getCouponRedemptions = (id) => api.get(`/api/admin/coupons/${id}/redemptions`);

// Risk Management - Rate Limit Policies
export const getRiskPolicies = () => api.get('/api/admin/risk/policies');
export const createRiskPolicy = (data) => api.post('/api/admin/risk/policies', data);
export const updateRiskPolicy = (id, data) => api.put(`/api/admin/risk/policies/${id}`, data);
export const deleteRiskPolicy = (id) => api.delete(`/api/admin/risk/policies/${id}`);
export const updateRiskPolicyStatus = (id, status) => api.put(`/api/admin/risk/policies/${id}/status`, { status });

// Risk Management - Failure Strategies
export const getFailureStrategies = () => api.get('/api/admin/risk/failure-strategies');
export const createFailureStrategy = (data) => api.post('/api/admin/risk/failure-strategies', data);
export const updateFailureStrategy = (id, data) => api.put(`/api/admin/risk/failure-strategies/${id}`, data);
export const deleteFailureStrategy = (id) => api.delete(`/api/admin/risk/failure-strategies/${id}`);
export const updateFailureStrategyStatus = (id, enabled) => api.put(`/api/admin/risk/failure-strategies/${id}/status`, { enabled });

// Risk Management - Circuit Breaker Records
export const getCircuitBreakerRecords = () => api.get('/api/admin/risk/records');
export const releaseCircuitBreaker = (id) => api.post(`/api/admin/risk/records/${id}/release`);
export const createManualCircuitBreaker = (data) => api.post('/api/admin/risk/records/manual', data);

export default api;
