package com.github.guoyyit.containerimagebuilderjava.build;

import java.util.concurrent.atomic.AtomicBoolean;

public class BuildCancellationToken {
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public void cancel() {
        cancelled.set(true);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public void throwIfCancelled() {
        if (cancelled.get()) {
            throw new com.github.guoyyit.containerimagebuilderjava.exception.BuildCancelledException(
                    "build was cancelled");
        }
    }

    public static BuildCancellationToken never() {
        return new BuildCancellationToken();
    }
}
