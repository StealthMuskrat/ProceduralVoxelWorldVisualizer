package io.voxelcreator.storage;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;

/** Package-private archive write boundary; production bytes still use RandomAccessFile. */
interface ArchiveOutput extends AutoCloseable {
  @FunctionalInterface
  interface Factory {
    ArchiveOutput open(Path path) throws IOException;
  }

  long position() throws IOException;

  void writeInt(int value) throws IOException;

  void writeLong(long value) throws IOException;

  void writeUTF(String value) throws IOException;

  void write(byte[] value) throws IOException;

  void rollback(long position) throws IOException;

  void sync() throws IOException;

  @Override
  void close() throws IOException;

  static ArchiveOutput open(Path path) throws IOException {
    return new Disk(path);
  }

  final class Disk implements ArchiveOutput {
    private final RandomAccessFile file;

    Disk(Path path) throws IOException {
      file = new RandomAccessFile(path.toFile(), "rw");
    }

    public long position() throws IOException {
      return file.getFilePointer();
    }

    public void writeInt(int value) throws IOException {
      file.writeInt(value);
    }

    public void writeLong(long value) throws IOException {
      file.writeLong(value);
    }

    public void writeUTF(String value) throws IOException {
      file.writeUTF(value);
    }

    public void write(byte[] value) throws IOException {
      file.write(value);
    }

    public void rollback(long position) throws IOException {
      file.setLength(position);
      file.seek(position);
    }

    public void sync() throws IOException {
      file.getFD().sync();
    }

    public void close() throws IOException {
      file.close();
    }
  }
}
