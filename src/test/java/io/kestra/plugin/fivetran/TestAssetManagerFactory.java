package io.kestra.plugin.fivetran;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import io.kestra.core.assets.AssetManagerFactory;
import io.kestra.core.runners.AssetEmit;
import io.kestra.core.runners.AssetEmitter;

import io.micronaut.context.annotation.Replaces;
import jakarta.inject.Singleton;

@Singleton
@Replaces(AssetManagerFactory.class)
public class TestAssetManagerFactory extends AssetManagerFactory {
    private final List<AssetEmit> allEmitted = Collections.synchronizedList(new ArrayList<>());
    private volatile boolean throwUnsupportedOperationOnEmit = false;
    private volatile RuntimeException failureOnEmit;
    private final AtomicInteger failedEmitAttempts = new AtomicInteger();

    @Override
    public AssetEmitter of(boolean enable) {
        if (throwUnsupportedOperationOnEmit) {
            return new ThrowingAssetEmitter();
        }
        if (failureOnEmit != null) {
            return new FailingAssetEmitter(failureOnEmit, failedEmitAttempts);
        }
        return new TrackingAssetEmitter(allEmitted, enable);
    }

    /** All assets emitted across all RunContexts (for runner/integration tests). */
    public List<AssetEmit> allEmitted() {
        return List.copyOf(allEmitted);
    }

    /**
     * Makes the next emitter's {@code emit()} throw {@link UnsupportedOperationException}, mirroring the
     * real {@link AssetManagerFactory#of(boolean)} default on OSS where the EE emitter isn't available.
     */
    public void throwUnsupportedOperationOnEmit(boolean throwing) {
        this.throwUnsupportedOperationOnEmit = throwing;
    }

    /** Makes the next emitter's {@code emit()} throw the given exception. */
    public void failOnEmit(RuntimeException failure) {
        this.failureOnEmit = failure;
    }

    /** Number of {@code emit()} calls rejected by {@link #failOnEmit}, across all RunContexts. */
    public int failedEmitAttempts() {
        return failedEmitAttempts.get();
    }

    public void clear() {
        allEmitted.clear();
        throwUnsupportedOperationOnEmit = false;
        failureOnEmit = null;
        failedEmitAttempts.set(0);
    }

    private static final class TrackingAssetEmitter implements AssetEmitter {
        private final List<AssetEmit> shared;
        private final List<AssetEmit> local = new ArrayList<>();
        private final boolean enabled;

        TrackingAssetEmitter(List<AssetEmit> shared, boolean enabled) {
            this.shared = shared;
            this.enabled = enabled;
        }

        @Override
        public void emit(AssetEmit assetEmit) {
            if (!enabled) {
                return;
            }
            local.add(assetEmit);
            shared.add(assetEmit);
        }

        @Override
        public List<AssetEmit> emitted() {
            return List.copyOf(local);
        }
    }

    private static final class ThrowingAssetEmitter implements AssetEmitter {
        @Override
        public void emit(AssetEmit assetEmit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<AssetEmit> emitted() {
            return List.of();
        }
    }

    private static final class FailingAssetEmitter implements AssetEmitter {
        private final RuntimeException failure;
        private final AtomicInteger attempts;

        FailingAssetEmitter(RuntimeException failure, AtomicInteger attempts) {
            this.failure = failure;
            this.attempts = attempts;
        }

        @Override
        public void emit(AssetEmit assetEmit) {
            attempts.incrementAndGet();
            throw failure;
        }

        @Override
        public List<AssetEmit> emitted() {
            return List.of();
        }
    }
}
