package top.howiehz.halo.transformer.util;

import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import run.halo.app.extension.MetadataOperator;

public final class OptimisticConcurrencyGuard {
    private OptimisticConcurrencyGuard() {
    }

    /**
     * why: Halo 已经提供 `metadata.version` 作为乐观并发语义；
     * 插件写接口必须显式复用这份约束，避免重新退回 silent last-write-wins。
     */
    public static void requireMatchingVersion(MetadataOperator persistedMetadata,
        MetadataOperator incomingMetadata,
        String resourceLabel) {
        Long persistedVersion = persistedMetadata == null ? null : persistedMetadata.getVersion();
        Long incomingVersion = incomingMetadata == null ? null : incomingMetadata.getVersion();
        if (Objects.equals(persistedVersion, incomingVersion)) {
            return;
        }
        throw new ResponseStatusException(
            HttpStatus.CONFLICT,
            resourceLabel + "已被其他人修改，请刷新后重试"
        );
    }
}
