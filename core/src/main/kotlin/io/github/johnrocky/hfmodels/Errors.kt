package io.github.johnrocky.hfmodels

/**
 * Every failure the SDK raises, by code (spec v1.0 §13). Cancellation stays a
 * [kotlinx.coroutines.CancellationException]. [details] never carries a token, a prompt, an
 * image or generated text.
 */
enum class ErrorCode {
    MODEL_NOT_REGISTERED,
    MODEL_NOT_FOUND_OR_INACCESSIBLE,
    AUTH_REQUIRED,
    ACCESS_DENIED,
    REVISION_NOT_FOUND,
    MANIFEST_INVALID,
    UNSUPPORTED_SCHEMA,
    VARIANT_NOT_FOUND,
    HANDLER_NOT_INSTALLED,
    RUNTIME_VERSION_MISMATCH,
    METADATA_MISMATCH,
    NO_COMPATIBLE_PROFILE,
    NATIVE_MODULE_MISSING,
    DOWNLOAD_POLICY_BLOCKED,
    ADDITIONAL_DOWNLOAD_REQUIRED,
    NETWORK_ERROR,
    STORAGE_FULL,
    CHECKSUM_MISMATCH,
    OFFLINE_CACHE_MISS,
    UNSUPPORTED_INPUT,
    INVALID_INPUT,
    UNSUPPORTED_CONFIGURATION,
    CONTEXT_LIMIT_EXCEEDED,
    MODEL_BUSY,
    MODEL_CLOSED,
    SESSION_INVALIDATED,
    STREAM_ALREADY_COLLECTED,
    SLOW_CONSUMER,
    INITIALIZATION_FAILED,
    INFERENCE_FAILED,
    NATIVE_STOP_TIMEOUT,
}

class ModelException(
    val code: ErrorCode,
    message: String,
    val retryable: Boolean = false,
    val details: Map<String, String> = emptyMap(),
    cause: Throwable? = null,
) : Exception("$code: $message", cause) {
    /** The message without the code prefix. */
    val reason: String = message

    override fun toString(): String =
        "ModelException($code, retryable=$retryable, details=$details): $reason"
}
