package com.example.wearapp_ver2;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Comparator;

final class SessionFiles {

    static final String HEADER = "Timestamp,Sensor,X,Y,Z,W";

    private static final String TAG = "SessionFiles";

    private final File root;

    static final class Session {
        final File temp;
        final String stem;

        long startNs;
        long stopNs = -1L;

        boolean validHeader;
        boolean complete;
        boolean saved;

        String problem = "";

        Session(File temp, String stem, long startNs) {
            this.temp = temp;
            this.stem = stem;
            this.startNs = startNs;
        }
    }

    SessionFiles(File filesDir) throws IOException {
        root = new File(filesDir, "measurements");

        if (!root.isDirectory() && !root.mkdirs()) {
            throw new IOException("記録用ディレクトリを作成できません");
        }

        syncDirectory(filesDir);
    }

    Session create(String stem, long startNs) throws IOException {
        if (!stem.matches("[0-9]{8}_[0-9]{6}") || startNs < 0L) {
            throw new IOException("開始情報が不正です");
        }

        for (int index = 0; index < Integer.MAX_VALUE; index++) {
            String name = stem + (index == 0 ? "" : "_" + index);
            File temp = new File(root, name + ".tmp");

            if (temp.exists()
                    || new File(temp.getPath() + ".ok").exists()
                    || new File(temp.getPath() + ".ok.new").exists()
                    || hasCsvName(name)) {
                continue;
            }

            try {
                Files.createFile(temp.toPath());
            } catch (FileAlreadyExistsException exception) {
                continue;
            }

            syncDirectory(root);
            return new Session(temp, name, startNs);
        }

        throw new IOException("空いているファイル名がありません");
    }

    Session recover() throws IOException {
        File[] pending = listFiles(".tmp");

        Arrays.sort(
                pending,
                Comparator.comparingLong(File::lastModified)
                        .thenComparing(File::getName)
        );

        for (File temp : pending) {
            String name = temp.getName();

            Session session = new Session(
                    temp,
                    name.substring(0, name.length() - 4),
                    0L
            );

            // 保存名が作成済みなら、内容を変更せず保存確定を再確認する。
            File linked = findLinkedCsv(session);

            if (linked != null) {
                syncFile(linked);
                syncDirectory(root);

                session.saved = true;
                cleanup(session);
                continue;
            }

            try (RandomAccessFile input = new RandomAccessFile(temp, "r")) {
                if (!HEADER.equals(readLine(input, 64))) {
                    throw new IOException("CSV ヘッダーが不正です");
                }

                String[] start = readLine(input, 128).split(",", -1);

                if (start.length != 6
                        || !"START".equals(start[1])
                        || !start[2].isEmpty()
                        || !start[3].isEmpty()
                        || !start[4].isEmpty()
                        || !start[5].isEmpty()) {
                    throw new IOException("START 行が不正です");
                }

                session.startNs = Long.parseLong(start[0]);

                if (session.startNs < 0L) {
                    throw new IOException("開始時刻が不正です");
                }

                session.validHeader = true;

            } catch (IOException | NumberFormatException exception) {
                session.problem = "一時ファイルを復旧できません";
                Log.e(TAG, session.problem, exception);
                return session;
            }

            File marker = marker(session);

            if (!marker.exists()) {
                session.problem = "正常終了を確認できない中断データです";
                return session;
            }

            try (DataInputStream input =
                         new DataInputStream(new FileInputStream(marker))) {

                long stopNs = input.readLong();

                if (input.read() != -1
                        || stopNs < session.startNs
                        || !endsWithNewline(temp)) {
                    throw new IOException("正常終了情報が不正です");
                }

                session.stopNs = stopNs;
                session.complete = true;

            } catch (IOException exception) {
                session.problem = "正常終了情報を確認できません";
                Log.e(TAG, session.problem, exception);
            }

            return session;
        }

        return null;
    }

    void complete(Session session, long stopNs) throws IOException {
        if (!session.validHeader || stopNs < session.startNs) {
            throw new IOException("終了情報が不正です");
        }

        File marker = marker(session);
        File temporaryMarker = new File(marker.getPath() + ".new");

        try (FileOutputStream stream = new FileOutputStream(temporaryMarker);
             DataOutputStream output = new DataOutputStream(stream)) {

            output.writeLong(stopNs);
            output.flush();
            stream.getFD().sync();
        }

        Files.move(temporaryMarker.toPath(), marker.toPath());
        syncDirectory(root);

        session.stopNs = stopNs;
        session.complete = true;
        session.problem = "";
    }

    void save(Session session) throws IOException {
        if (session.saved) {
            cleanup(session);
            return;
        }

        // リンク作成後の tmp は CSV と同じ実体なので、先に確認する。
        File destination = findLinkedCsv(session);

        if (destination == null) {
            if (!session.validHeader) {
                throw new IOException("有効な START 行がありません");
            }

            if (!session.complete) {
                trimIncompleteTail(session.temp);
            }

            syncFile(session.temp);

            String base = session.stem
                    + (session.complete ? "" : "_incomplete");

            for (int index = 0; index < Integer.MAX_VALUE; index++) {
                String suffix = index == 0 ? "" : "_" + index;
                File candidate = new File(root, base + suffix + ".csv");

                try {
                    Files.createLink(
                            candidate.toPath(),
                            session.temp.toPath()
                    );

                    destination = candidate;
                    break;

                } catch (FileAlreadyExistsException exception) {
                    // 既存 CSV を上書きせず、別の名前を試す。
                }
            }

            if (destination == null) {
                throw new IOException("空いている保存名がありません");
            }

        } else {
            syncFile(destination);
        }

        syncDirectory(root);

        session.saved = true;
        cleanup(session);
    }

    void discard(Session session) throws IOException {
        // SAVE の途中で保存名ができていた場合も、その CSV は残す。
        File linked = findLinkedCsv(session);

        if (linked != null) {
            syncFile(linked);
            syncDirectory(root);
            session.saved = true;
        }

        Files.deleteIfExists(session.temp.toPath());
        Files.deleteIfExists(marker(session).toPath());

        Files.deleteIfExists(
                new File(marker(session).getPath() + ".new").toPath()
        );

        syncDirectory(root);
    }

    private File marker(Session session) {
        return new File(session.temp.getPath() + ".ok");
    }

    private File[] listFiles(String suffix) throws IOException {
        File[] files = root.listFiles(
                (directory, name) -> name.endsWith(suffix)
        );

        if (files == null) {
            throw new IOException("記録用ディレクトリを読み取れません");
        }

        return files;
    }

    private boolean matchesCsvStem(String fileName, String stem) {
        return fileName.equals(stem + ".csv")
                || (fileName.startsWith(stem + "_")
                && fileName.endsWith(".csv"));
    }

    private boolean hasCsvName(String stem) throws IOException {
        for (File file : listFiles(".csv")) {
            if (matchesCsvStem(file.getName(), stem)) {
                return true;
            }
        }

        return false;
    }

    private File findLinkedCsv(Session session) throws IOException {
        if (!session.temp.exists()) {
            return null;
        }

        for (File file : listFiles(".csv")) {
            if (matchesCsvStem(file.getName(), session.stem)
                    && Files.isSameFile(
                    session.temp.toPath(),
                    file.toPath()
            )) {
                return file;
            }
        }

        return null;
    }

    private String readLine(
            RandomAccessFile input,
            int limit
    ) throws IOException {
        StringBuilder result = new StringBuilder();

        while (true) {
            int value = input.read();

            if (value == '\n') {
                return result.toString();
            }

            if (value < 32 || value > 126 || result.length() >= limit) {
                throw new IOException("完全な CSV 行を読み取れません");
            }

            result.append((char) value);
        }
    }

    private boolean endsWithNewline(File file) throws IOException {
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            if (input.length() == 0L) {
                return false;
            }

            input.seek(input.length() - 1L);
            return input.read() == '\n';
        }
    }

    private void trimIncompleteTail(File file) throws IOException {
        try (FileChannel channel = FileChannel.open(
                file.toPath(),
                StandardOpenOption.READ,
                StandardOpenOption.WRITE
        )) {
            long end = channel.size();
            ByteBuffer buffer = ByteBuffer.allocate(8192);

            while (end > 0L) {
                int count = (int) Math.min(end, buffer.capacity());
                end -= count;

                buffer.clear();
                buffer.limit(count);
                channel.position(end);

                while (buffer.hasRemaining()) {
                    if (channel.read(buffer) < 0) {
                        throw new IOException("一時ファイルが途中で終了しました");
                    }
                }

                byte[] bytes = buffer.array();

                for (int index = count - 1; index >= 0; index--) {
                    if (bytes[index] == '\n') {
                        channel.truncate(end + index + 1L);
                        return;
                    }
                }
            }

            throw new IOException("完全な CSV 行がありません");
        }
    }

    private void syncFile(File file) throws IOException {
        // CREATE を指定しないため、欠落したファイルを空で作成しない。
        try (FileChannel channel = FileChannel.open(
                file.toPath(),
                StandardOpenOption.WRITE
        )) {
            channel.force(true);
        }
    }

    private void syncDirectory(File directory) throws IOException {
        FileDescriptor descriptor = null;

        try {
            descriptor = Os.open(
                    directory.getAbsolutePath(),
                    OsConstants.O_RDONLY | OsConstants.O_DIRECTORY,
                    0
            );

            Os.fsync(descriptor);

        } catch (ErrnoException exception) {
            throw new IOException("ディレクトリを同期できません", exception);

        } finally {
            if (descriptor != null) {
                try {
                    Os.close(descriptor);
                } catch (ErrnoException exception) {
                    Log.w(TAG, "ディレクトリを閉じられませんでした", exception);
                }
            }
        }
    }

    private void cleanup(Session session) {
        File marker = marker(session);

        File[] temporaryFiles = {
                session.temp,
                marker,
                new File(marker.getPath() + ".new")
        };

        for (File file : temporaryFiles) {
            try {
                Files.deleteIfExists(file.toPath());
            } catch (IOException exception) {
                Log.w(
                        TAG,
                        "保存後の一時ファイル削除を次回再試行します",
                        exception
                );
            }
        }
    }
}