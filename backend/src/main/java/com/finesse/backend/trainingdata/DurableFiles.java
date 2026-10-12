package com.finesse.backend.trainingdata;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * 프로세스가 쓰는 도중 죽어도 반쯤 쓰인 파일이 남지 않게 쓴다 (작업지시서 3.4·4.4절).
 * 새 파일은 임시 파일에 쓰고 fsync 후 rename, 기록 파일(users.csv 등)은 한 줄 append 후 fsync.
 */
public final class DurableFiles {

    private DurableFiles() {
    }

    public static void writeAtomically(Path target, byte[] bytes) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            writeFully(ch, bytes);
            ch.force(true);
        }
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    /** 파일 끝에 text를 붙이고 디스크까지 내린다. 파일이 없거나 비어 있으면 BOM과 header를 먼저 쓴다 */
    public static void append(Path file, String header, String text) throws IOException {
        boolean fresh = !Files.exists(file) || Files.size(file) == 0;
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.APPEND)) {
            if (fresh) {
                writeFully(ch, (CsvFiles.BOM + header).getBytes(StandardCharsets.UTF_8));
            }
            writeFully(ch, text.getBytes(StandardCharsets.UTF_8));
            ch.force(true);
        }
    }

    /**
     * append 도중 죽어 마지막 줄이 줄바꿈 없이 끊겼으면 그 줄을 버린다 — 그 유저는 처음부터 다시 수집된다.
     *
     * @return 잘라낸 것이 있으면 true
     */
    public static boolean dropPartialLastLine(Path file) throws IOException {
        if (!Files.exists(file)) {
            return false;
        }
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length == 0 || bytes[bytes.length - 1] == '\n') {
            return false;
        }
        int keep = 0;
        for (int i = bytes.length - 1; i >= 0; i--) {
            if (bytes[i] == '\n') {
                keep = i + 1;
                break;
            }
        }
        byte[] kept = new byte[keep];
        System.arraycopy(bytes, 0, kept, 0, keep);
        writeAtomically(file, kept);
        return true;
    }

    private static void writeFully(FileChannel ch, byte[] bytes) throws IOException {
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        while (buf.hasRemaining()) {
            ch.write(buf);
        }
    }
}
