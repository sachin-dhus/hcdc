package ai.sapper.hcdc.core.io.impl.local;

import ai.sapper.hcdc.core.io.PathInfo;
import ai.sapper.hcdc.core.io.Reader;
import com.google.common.base.Preconditions;
import lombok.NonNull;

import java.io.IOException;
import java.io.RandomAccessFile;

public class LocalReader extends Reader {
    private RandomAccessFile inputStream;

    public LocalReader(@NonNull PathInfo path) {
        super(path);
        Preconditions.checkArgument(path instanceof LocalPathInfo);
    }


    /**
     * @return
     * @throws IOException
     */
    @Override
    public Reader open() throws IOException {
        LocalPathInfo pi = (LocalPathInfo) path();
        if (!pi.exists()) {
            throw new IOException(String.format("File not found. [path=%s]", pi.file().getAbsolutePath()));
        }
        inputStream = new RandomAccessFile(pi.file(), "r");

        return this;
    }

    /**
     * @param buffer
     * @param offset
     * @param length
     * @return
     * @throws IOException
     */
    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        LocalPathInfo pi = (LocalPathInfo) path();
        if (inputStream == null) {
            throw new IOException(String.format("File Stream not open. [path=%s]", pi.file().getAbsolutePath()));
        }
        return inputStream.read(buffer, offset, length);
    }

    /**
     * @param offset
     * @throws IOException
     */
    @Override
    public void seek(int offset) throws IOException {
        LocalPathInfo pi = (LocalPathInfo) path();
        if (inputStream == null) {
            throw new IOException(String.format("File Stream not open. [path=%s]", pi.file().getAbsolutePath()));
        }
        inputStream.seek(offset);
    }

    /**
     * Closes this stream and releases any system resources associated
     * with it. If the stream is already closed then invoking this
     * method has no effect.
     *
     * <p> As noted in {@link AutoCloseable#close()}, cases where the
     * close may fail require careful attention. It is strongly advised
     * to relinquish the underlying resources and to internally
     * <em>mark</em> the {@code Closeable} as closed, prior to throwing
     * the {@code IOException}.
     *
     * @throws IOException if an I/O error occurs
     */
    @Override
    public void close() throws IOException {
        if (inputStream != null) {
            inputStream.close();
            inputStream = null;
        }
    }
}
