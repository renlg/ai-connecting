package com.aiconnecting.service;

import com.aiconnecting.common.BusinessException;

import java.util.Locale;
import java.util.Set;

/**
 * 语义化的上游/渠道失败分类：决定单模型 fallback_group_id 与模型组内成员切换时是否允许"换一个继续试"，
 * 还是应当立即向客户端报错（换了大概率仍会失败，或错误与上游/渠道本身无关）。
 * <p>
 * 分类依据 {@link BusinessException#getCode()}（各转发方法已将 HTTP 状态码或本地网络异常映射为
 * 对应的伪 HTTP 状态码，如 502/504）以及错误消息中携带的上游原始响应体（可能包含
 * error.code / error.type 等字段），不引入新的异常类型，避免改动 forwardRequest 等
 * 被单模型路径共用的方法对客户端可见的错误格式。
 */
final class FailureClassifier {

    private FailureClassifier() {
    }

    /** 出现在上游错误消息中即视为"模型不存在/配额/限流"类错误，允许切换 */
    private static final Set<String> SWITCH_KEYWORDS = Set.of(
            "model_not_found", "model_decommissioned", "quota", "rate_limit", "insufficient");

    /**
     * @return true = 允许切换（换渠道/换成员/转入故障转移组）；false = 快速失败，直接向客户端报错
     */
    static boolean isSwitchable(BusinessException e) {
        return isSwitchable(e.getCode(), e.getMessage());
    }

    /** 供直接持有 HTTP 状态码 + 上游原始响应体的调用方使用（如 SSE 路径读取 HttpURLConnection 状态） */
    static boolean isSwitchable(int code, String errorBody) {
        String lower = errorBody == null ? "" : errorBody.toLowerCase(Locale.ROOT);
        // 429 限流、408/5xx 超时或上游/渠道故障、401/403 上游鉴权失败（渠道密钥可能已失效）均应切换
        if (code == 429 || code == 408 || code >= 500 || code == 401 || code == 403) {
            return true;
        }
        // 400/404 本身通常是客户端请求问题，但 model_not_found / 配额 / 限流 类错误仍应切换
        if (code == 400 || code == 404) {
            for (String kw : SWITCH_KEYWORDS) {
                if (lower.contains(kw)) {
                    return true;
                }
            }
            return false;
        }
        // 413（请求体过大）、422（参数校验失败）等：客户端请求本身有问题，换成员大概率仍会失败
        return false;
    }
}
