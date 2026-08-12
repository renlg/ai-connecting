package com.aiconnecting.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.aiconnecting.service.RelayProtocol;
import com.aiconnecting.service.RelayProtocolAdapter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.io.IOException;
import java.util.stream.Collectors;

@RestControllerAdvice
@RequiredArgsConstructor
@Slf4j
public class GlobalExceptionHandler {

    private final ObjectMapper objectMapper;

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<?> handleBusinessException(BusinessException e, HttpServletRequest request,
                                                     HttpServletResponse response) throws IOException {
        HttpStatusCode httpStatus = mapToHttpStatus(e.getCode());
        // 终端用户中转接口（/v1/**）：真实上游错误隐藏细节，仅返回通用错误 + traceId；
        // 本地业务错误（模型不存在、Token 无效、余额不足等）返回具体错误信息；
        // 管理后台/自测接口（/api/**，如渠道测试）继续返回详细错误
        // SSE 请求在首字节前保留映射后的 HTTP 状态，并使用客户端协议的 error 事件。
        if (isSseRequest(request) && isEndUserRelayPath(request)) {
            if (!response.isCommitted()) {
                response.setStatus(httpStatus.value());
                SseUtils.setSseHeaders(response);
            }
            String message = e.isUpstreamResponse() || e.getEnglishMessage() == null
                    ? SseUtils.GENERIC_UPSTREAM_ERROR_MESSAGE : e.getEnglishMessage();
            protocolAdapter().writeSseError(protocol(request), response, httpStatus.value(), message,
                    e.isUpstreamResponse());
            return null;
        }
        if (isEndUserRelayPath(request)) {
            String message = e.isUpstreamResponse() || e.getEnglishMessage() == null
                    ? SseUtils.GENERIC_UPSTREAM_ERROR_MESSAGE
                    : e.getEnglishMessage();
            return ResponseEntity.status(httpStatus).body(protocolAdapter().errorEnvelope(
                    protocol(request), httpStatus.value(), message, e.isUpstreamResponse()));
        }
        return ResponseEntity
                .status(httpStatus)
                .body(ApiResponse.error(e.getCode(), e.getMessage()));
    }

    private boolean isClaudePath(HttpServletRequest request) {
        return "/v1/messages".equals(request.getRequestURI());
    }

    private boolean isGeminiPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri != null && uri.startsWith("/v1/models/")
                && (uri.endsWith(":generateContent") || uri.endsWith(":streamGenerateContent"));
    }

    /**
     * 将业务错误码映射为 HTTP 状态码
     */
    private HttpStatusCode mapToHttpStatus(int code) {
        HttpStatus known = switch (code) {
            case 401 -> HttpStatus.UNAUTHORIZED;
            case 402 -> HttpStatus.PAYMENT_REQUIRED;
            case 403 -> HttpStatus.FORBIDDEN;
            case 404 -> HttpStatus.NOT_FOUND;
            case 409 -> HttpStatus.CONFLICT;
            case 413 -> HttpStatus.PAYLOAD_TOO_LARGE;
            case 429 -> HttpStatus.TOO_MANY_REQUESTS;
            case 500 -> HttpStatus.INTERNAL_SERVER_ERROR;
            case 502 -> HttpStatus.BAD_GATEWAY;
            case 503 -> HttpStatus.SERVICE_UNAVAILABLE;
            case 504 -> HttpStatus.GATEWAY_TIMEOUT;
            default -> null;
        };
        if (known != null) return known;
        if (code >= 400 && code <= 599) return HttpStatusCode.valueOf(code);
        return HttpStatus.BAD_REQUEST;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> handleValidationException(MethodArgumentNotValidException e,
                                                       HttpServletRequest request) {
        String chineseMessage = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("参数校验失败");
        String message = localized(request, "Validation failed", chineseMessage);
        return errorResponse(request, 400, message);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<?> handleMethodNotAllowed(HttpRequestMethodNotSupportedException e,
                                                    HttpServletRequest request) {
        return errorResponse(request, 405, localized(request, "Unsupported request method: " + e.getMethod(),
                "不支持的请求方法: " + e.getMethod()));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<?> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException e,
                                                         HttpServletRequest request) {
        return errorResponse(request, 415, localized(request, "Unsupported media type: " + e.getContentType(),
                "不支持的媒体类型: " + e.getContentType()));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<?> handleMissingParam(MissingServletRequestParameterException e,
                                                HttpServletRequest request) {
        return errorResponse(request, 400, localized(request,
                "Missing required parameter: " + e.getParameterName(), "缺少必要参数: " + e.getParameterName()));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<?> handleConstraintViolation(ConstraintViolationException e,
                                                       HttpServletRequest request) {
        String chineseMessage = e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.joining("; "));
        String message = localized(request, "Constraint validation failed", chineseMessage);
        return errorResponse(request, 400, message);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<?> handleMaxUploadSize(MaxUploadSizeExceededException e,
                                                 HttpServletRequest request) {
        return errorResponse(request, 413, localized(request, "Uploaded file exceeds the size limit",
                "上传文件大小超过限制"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleException(Exception e, HttpServletRequest request,
                                                              HttpServletResponse response) throws IOException {
        log.error("服务器内部错误: {}", e.getMessage(), e);
        String message = localized(request, "Internal server error, please try again later", "服务器内部错误，请稍后重试");
        if (isSseRequest(request) && isEndUserRelayPath(request)) {
            if (!response.isCommitted()) {
                response.setStatus(500);
                SseUtils.setSseHeaders(response);
            }
            protocolAdapter().writeSseError(protocol(request), response, 500, message, false);
            return null;
        }
        if (isEndUserRelayPath(request)) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(protocolAdapter().errorEnvelope(protocol(request), 500, message, false));
        }
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(500, message));
    }

    private boolean isEndUserRelayPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri != null && uri.startsWith("/v1/");
    }

    private boolean isSseRequest(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        return accept != null && accept.contains("text/event-stream");
    }

    private RelayProtocol protocol(HttpServletRequest request) {
        if (isClaudePath(request)) return RelayProtocol.CLAUDE;
        if (isGeminiPath(request)) return RelayProtocol.GEMINI;
        return RelayProtocol.OPENAI;
    }

    private RelayProtocolAdapter protocolAdapter() {
        return new RelayProtocolAdapter(objectMapper);
    }

    private ResponseEntity<?> errorResponse(HttpServletRequest request, int status, String message) {
        if (isEndUserRelayPath(request)) {
            return ResponseEntity.status(status).body(
                    protocolAdapter().errorEnvelope(protocol(request), status, message, false));
        }
        return ResponseEntity.status(status).body(ApiResponse.error(status, message));
    }

    private String localized(HttpServletRequest request, String english, String chinese) {
        return isEndUserRelayPath(request) ? english : chinese;
    }
}
