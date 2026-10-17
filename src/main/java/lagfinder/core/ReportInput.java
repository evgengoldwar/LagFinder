package lagfinder.core;

import java.io.DataInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

final class ReportInput extends DataInputStream {

    /** Elements across the whole report; the expanded size limit below still caps what a file can make us read. */
    private int remaining = 20_000_000;

    ReportInput(InputStream input) {
        super(new Limited(input));
    }

    static int count(DataInputStream input, int count, int max) throws IOException {
        if (count < 0 || count > max) {
            throw new IOException("Invalid report collection length");
        }
        if (input instanceof ReportInput) {
            ReportInput bounded = (ReportInput) input;
            if (count > bounded.remaining) {
                throw new IOException("Report contains too many elements");
            }
            bounded.remaining -= count;
        }
        return count;
    }

    static int index(int index, int size) throws IOException {
        if (index < 0 || index >= size) {
            throw new IOException("Invalid report index");
        }
        return index;
    }

    private static final class Limited extends FilterInputStream {

        private long remaining = 128 * 1024 * 1024;

        Limited(InputStream input) {
            super(input);
        }

        @Override
        public int read() throws IOException {
            int value = in.read();
            if (value >= 0 && --remaining < 0) {
                throw new IOException("Expanded report exceeds the size limit");
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int count = in.read(bytes, offset, (int) Math.min(length, remaining + 1));
            if (count > 0 && (remaining -= count) < 0) {
                throw new IOException("Expanded report exceeds the size limit");
            }
            return count;
        }
    }
}
