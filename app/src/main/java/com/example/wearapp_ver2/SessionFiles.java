package com.example.wearapp_ver2;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
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
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.regex.Pattern;

final class SessionFiles {

    static final String HEADER = "Timestamp,Sensor,X,Y,Z,W";

    private static final String TAG = "SessionFiles";

    private static final int SAVE_INTENT_MAGIC = 0x57535631; // WSV1
    private final File root;
    private final CsvMover csvMover;

    interface CsvMover {
        void move(File source, File destination) throws IOException;
    }

    private static final class SaveIntent {
        final File destination;
        final long device;
        final long inode;
        final long size;
        final long startNs;
        final long stopNs;
        final boolean complete;

        SaveIntent(File destination, long device, long inode, long size,
                   long startNs, long stopNs, boolean complete) {
            this.destination = destination;
            this.device = device;
            this.inode = inode;
            this.size = size;
            this.startNs = startNs;
            this.stopNs = stopNs;
            this.complete = complete;
        }
    }

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
        this(filesDir, (source, destination) -> {
            // ATOMIC_MOVE は既存の移動先を置換し得るため、CSVには指定しない。
            // 呼び出しは既存の FILE_IO スレッドで直列化される。
            Files.move(source.toPath(), destination.toPath());
        });
    }

    // 保存失敗を再現するテスト用に、CSVの移動だけを差し替え可能にする。
    SessionFiles(File filesDir, CsvMover csvMover) throws IOException {
        this.csvMover = csvMover;
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
                    || exists(new File(temp.getPath() + ".save"))
                    || exists(new File(temp.getPath() + ".save.new"))
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
        // リネーム後は .tmp がないため、保存管理ファイルから先に復旧する。
        for (File intentFile : listFiles(".tmp.save")) {
            String tempName = intentFile.getName();
            tempName = tempName.substring(0, tempName.length() - ".save".length());
            Session moved = new Session(new File(root, tempName),
                    tempName.substring(0, tempName.length() - ".tmp".length()), 0L);
            if (!exists(moved.temp)) {
                finishSave(moved, readSaveIntent(moved));
            }
        }

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

            try {
                session.startNs = readStartTimestamp(temp);
                session.validHeader = true;

            } catch (IOException exception) {
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
                long csvStopNs = readStopTimestamp(temp);

                if (input.read() != -1
                        || stopNs < session.startNs
                        || (csvStopNs >= 0L && csvStopNs != stopNs)) {
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

        SaveIntent intent = null;
        if (exists(saveIntentFile(session))) {
            // 失敗後の再試行では、CSV化済みかどうかを先に確認する。
            try {
                intent = readSaveIntent(session);
            } catch (IOException exception) {
                if (!exists(session.temp)) {
                    throw exception; // 元データがなければ移動先を推測しない。
                }
                Log.w(TAG, "保存管理情報を元データから作り直します", exception);
            }
        }
        if (intent == null) {
            File linked = findLinkedCsv(session); // 旧版のハードリンク方式との互換
            if (linked != null) {
                syncFile(linked);
                syncDirectory(root);
                session.saved = true;
                cleanup(session);
                Log.i(TAG, "Saved legacy CSV: " + linked.getAbsolutePath());
                return;
            }
            if (!session.validHeader) {
                throw new IOException("有効な START 行がありません");
            }
            if (!session.complete) {
                trimIncompleteTail(session.temp);
            }
            syncFile(session.temp);
            if (readStartTimestamp(session.temp) != session.startNs) {
                throw new IOException("保存対象の START 時刻が一致しません");
            }
            StructStat stat = statRegularFile(session.temp);
            intent = new SaveIntent(nextDestination(session), stat.st_dev,
                    stat.st_ino, stat.st_size, session.startNs,
                    session.stopNs, session.complete);
            writeSaveIntent(session, intent);
        }

        for (;;) {
            if (!exists(session.temp)) {
                // move 済み・ディレクトリ同期未完了の再試行。
                finishSave(session, intent);
                return;
            }
            verifyIdentity(session.temp, intent);
            if (Files.isRegularFile(intent.destination.toPath(), LinkOption.NOFOLLOW_LINKS)
                    && Files.isSameFile(session.temp.toPath(), intent.destination.toPath())) {
                finishSave(session, intent);
                return;
            }
            if (!exists(intent.destination)) {
                // 管理ファイル作成時の同期が失敗していた場合も、移動前に確定する。
                syncFile(saveIntentFile(session));
                syncDirectory(root);
                try {
                    csvMover.move(session.temp, intent.destination);
                    finishSave(session, intent);
                    return;
                } catch (FileAlreadyExistsException exception) {
                    // 確認後に同名が現れた場合も、既存CSVは変更しない。
                    if (!exists(session.temp)) {
                        throw exception;
                    }
                }
            }
            // 名前衝突時だけ別名にする。アクセス拒否等で別方式へ逃げない。
            intent = new SaveIntent(nextDestination(session), intent.device,
                    intent.inode, intent.size, intent.startNs,
                    intent.stopNs, intent.complete);
            writeSaveIntent(session, intent);
        }
    }

    private File nextDestination(Session session) throws IOException {
        String base = session.stem + (session.complete ? "" : "_incomplete");
        for (int index = 0; index < Integer.MAX_VALUE; index++) {
            File candidate = new File(root, base
                    + (index == 0 ? "" : "_" + index) + ".csv");
            if (!exists(candidate)) {
                return candidate;
            }
        }
        throw new IOException("空いている保存名がありません");
    }

    private File saveIntentFile(Session session) {
        return new File(session.temp.getPath() + ".save");
    }

    private void writeSaveIntent(Session session, SaveIntent intent) throws IOException {
        File journal = saveIntentFile(session);
        File staging = new File(journal.getPath() + ".new");
        // 前回の書きかけを除去し、新規作成する。既存リンク先を切り詰めない。
        Files.deleteIfExists(staging.toPath());
        try (FileChannel channel = FileChannel.open(staging.toPath(),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
             DataOutputStream output = new DataOutputStream(Channels.newOutputStream(channel))) {
            output.writeInt(SAVE_INTENT_MAGIC);
            output.writeUTF(intent.destination.getName());
            output.writeLong(intent.device);
            output.writeLong(intent.inode);
            output.writeLong(intent.size);
            output.writeLong(intent.startNs);
            output.writeLong(intent.stopNs);
            output.writeBoolean(intent.complete);
            output.flush();
            channel.force(true);
        }
        // 置換するのは小さな保存管理ファイルだけ。CSVにはREPLACE_EXISTINGを使わない。
        Files.move(staging.toPath(), journal.toPath(), StandardCopyOption.REPLACE_EXISTING);
        // CSV移動直前に save() が管理ファイルとディレクトリを同期する。
    }

    private SaveIntent readSaveIntent(Session session) throws IOException {
        File journal = saveIntentFile(session);
        if (Files.size(journal.toPath()) > 512L) {
            throw new IOException("保存管理ファイルが不正です");
        }
        try (DataInputStream input = new DataInputStream(new FileInputStream(journal))) {
            if (input.readInt() != SAVE_INTENT_MAGIC) {
                throw new IOException("保存管理ファイルの形式が不正です");
            }
            String name = input.readUTF();
            if (!name.matches(Pattern.quote(session.stem) + "(?:_incomplete)?(?:_[0-9]+)?\\.csv")) {
                throw new IOException("保存先ファイル名が不正です");
            }
            SaveIntent intent = new SaveIntent(new File(root, name),
                    input.readLong(), input.readLong(), input.readLong(),
                    input.readLong(), input.readLong(), input.readBoolean());
            if (input.read() != -1 || intent.size < 0L || intent.startNs < 0L
                    || (intent.complete && intent.stopNs < intent.startNs)) {
                throw new IOException("保存管理情報が不正です");
            }
            return intent;
        }
    }

    private void finishSave(Session session, SaveIntent intent) throws IOException {
        verifyIdentity(intent.destination, intent);
        if (exists(session.temp)) {
            verifyIdentity(session.temp, intent);
        }
        if (readStartTimestamp(intent.destination) != intent.startNs) {
            throw new IOException("保存先の START 時刻が一致しません");
        }
        long csvStopNs = readStopTimestamp(intent.destination);
        if (intent.complete && csvStopNs >= 0L && csvStopNs != intent.stopNs) {
            throw new IOException("保存先の STOP 時刻が一致しません");
        }
        syncFile(intent.destination);
        syncDirectory(root);
        session.saved = true;
        cleanup(session);
        Log.i(TAG, "Saved CSV: " + intent.destination.getAbsolutePath()
                + " bytes=" + intent.size + " startNs=" + intent.startNs
                + " csvStopNs=" + csvStopNs + " complete=" + intent.complete);
    }

    private boolean exists(File file) {
        return Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS);
    }

    private StructStat statRegularFile(File file) throws IOException {
        try {
            StructStat stat = Os.lstat(file.getAbsolutePath());
            if (!OsConstants.S_ISREG(stat.st_mode)) {
                throw new IOException("通常ファイルではありません: " + file.getName());
            }
            return stat;
        } catch (ErrnoException exception) {
            throw new IOException("ファイル情報を確認できません: " + file.getName(), exception);
        }
    }

    private void verifyIdentity(File file, SaveIntent intent) throws IOException {
        StructStat stat = statRegularFile(file);
        if (stat.st_dev != intent.device || stat.st_ino != intent.inode
                || stat.st_size != intent.size) {
            throw new IOException("保存対象とファイルの識別情報が一致しません: " + file.getName());
        }
    }

    void discard(Session session) throws IOException {
        if (exists(saveIntentFile(session))) {
            SaveIntent intent = readSaveIntent(session);
            if (!exists(session.temp)) {
                // リネーム済みCSVをRESETで削除しない。同期を完了して後片付けする。
                finishSave(session, intent);
                return;
            }
            verifyIdentity(session.temp, intent);
        }
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

        Files.deleteIfExists(new File(saveIntentFile(session).getPath() + ".new").toPath());
        Files.deleteIfExists(saveIntentFile(session).toPath());
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
        if (!Files.isRegularFile(session.temp.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }

        for (File file : listFiles(".csv")) {
            if (matchesCsvStem(file.getName(), session.stem)
                    && Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)
                    && Files.isSameFile(
                    session.temp.toPath(),
                    file.toPath()
            )) {
                return file;
            }
        }

        return null;
    }

    private long readStartTimestamp(File file) throws IOException {
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            if (!HEADER.equals(readLine(input, 64))) {
                throw new IOException("CSV ヘッダーが不正です");
            }
            String[] start = readLine(input, 128).split(",", -1);
            if (start.length != 6 || !"START".equals(start[1])
                    || !start[2].isEmpty() || !start[3].isEmpty()
                    || !start[4].isEmpty() || !start[5].isEmpty()) {
                throw new IOException("START 行が不正です");
            }
            try {
                long timestamp = Long.parseLong(start[0]);
                if (timestamp < 0L) {
                    throw new IOException("開始時刻が不正です");
                }
                return timestamp;
            } catch (NumberFormatException exception) {
                throw new IOException("開始時刻が不正です", exception);
            }
        }
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

    private long readStopTimestamp(File file) throws IOException {
        // 全ファイルを読み込まず、末尾の1行だけを確認する。
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            long length = input.length();
            if (length == 0L) {
                throw new IOException("CSV が空です");
            }

            byte[] tail = new byte[(int) Math.min(length, 129L)];
            input.seek(length - tail.length);
            input.readFully(tail);
            if (tail[tail.length - 1] != '\n') {
                throw new IOException("CSV の末尾行が未完了です");
            }

            int begin = tail.length - 2;
            while (begin >= 0 && tail[begin] != '\n') {
                begin--;
            }
            if (begin < 0 && length > tail.length) {
                throw new IOException("CSV の末尾行が長すぎます");
            }
            begin++;
            String[] fields = new String(
                    tail, begin, tail.length - begin - 1,
                    StandardCharsets.US_ASCII
            ).split(",", -1);

            // 旧版の STOP 行がない記録は変更せず、従来のマーカーで復旧する。
            if (fields.length < 2 || !"STOP".equals(fields[1])) {
                return -1L;
            }
            if (fields.length != 6 || !fields[2].isEmpty()
                    || !fields[3].isEmpty() || !fields[4].isEmpty()
                    || !fields[5].isEmpty()) {
                throw new IOException("STOP 行が不正です");
            }
            try {
                long timestamp = Long.parseLong(fields[0]);
                if (timestamp < 0L) {
                    throw new IOException("STOP 時刻が不正です");
                }
                return timestamp;
            } catch (NumberFormatException exception) {
                throw new IOException("STOP 時刻が不正です", exception);
            }
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
                    OsConstants.O_RDONLY,
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
                new File(marker.getPath() + ".new"),
                new File(saveIntentFile(session).getPath() + ".new")
        };

        boolean removed = true;
        for (File file : temporaryFiles) {
            try {
                Files.deleteIfExists(file.toPath());
            } catch (IOException exception) {
                removed = false;
                Log.w(
                        TAG,
                        "保存後の一時ファイル削除を次回再試行します",
                        exception
                );
            }
        }
        // 他の一時ファイルが残った場合は、次回復旧用の管理情報を残す。
        if (removed) {
            try {
                Files.deleteIfExists(saveIntentFile(session).toPath());
            } catch (IOException exception) {
                Log.w(TAG, "保存管理ファイルの削除を次回再試行します", exception);
            }
        }
    }
}