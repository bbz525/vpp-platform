package io.vpp.streamprocessor.sink;

public enum RedisProjectionResult {
    OLDER_IGNORED,
    UPDATED,
    ALREADY_PROJECTED
}
