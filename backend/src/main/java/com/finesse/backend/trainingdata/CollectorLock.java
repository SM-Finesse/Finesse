package com.finesse.backend.trainingdata;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 수집기 단일 실행 보장 (작업지시서 4.7절) — 두 개가 뜨면 같은 유저를 중복 수집하고 기록이 어긋난다.
 * OS 파일 잠금이라 프로세스가 죽으면 잠금도 풀린다(남은 lock 파일을 지울 필요 없음).
 */
public final class CollectorLock implements AutoCloseable {

    private final FileChannel channel;
    private final FileLock lock;

    private CollectorLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    /** @return 다른 수집기가 이미 잡고 있으면 null */
    public static CollectorLock tryAcquire(Path lockFile) throws IOException {
        FileChannel ch = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock;
        try {
            lock = ch.tryLock();
        } catch (OverlappingFileLockException e) {
            lock = null; // 같은 JVM 안에서 이미 잡음
        }
        if (lock == null) {
            ch.close();
            return null;
        }
        return new CollectorLock(ch, lock);
    }

    @Override
    public void close() throws IOException {
        try {
            lock.release();
        } finally {
            channel.close();
        }
    }
}
