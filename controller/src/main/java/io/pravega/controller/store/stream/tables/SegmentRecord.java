/**
 * Copyright (c) 2017 Dell Inc., or its subsidiaries.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.pravega.controller.store.stream.tables;

import io.pravega.common.util.BitConverter;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Data
/**
 * Class represents one row/record in SegmentTable.
 * Segment table is chunked into multiple files, each containing #SEGMENT_CHUNK_SIZE records.
 * New segment chunk-name is highest-chunk-name + 1
 * Row: [segment-number, segment-creation-time, routing-key-floor-inclusive, routing-key-ceiling-exclusive]
 */
public class SegmentRecord {
    public static final int SEGMENT_RECORD_SIZE = Integer.BYTES + Long.BYTES + Double.BYTES + Double.BYTES;

    private final int segmentNumber;
    private final long startTime;
    private final double routingKeyStart;
    private final double routingKeyEnd;

    static Optional<SegmentRecord> readRecord(final byte[] segmentTable, final int number) {
        int offset = number * SegmentRecord.SEGMENT_RECORD_SIZE;

        if (offset >= segmentTable.length) {
            return Optional.empty();
        }
        return Optional.of(parse(segmentTable, offset));
    }

    static List<SegmentRecord> readLastN(final byte[] segmentTable, final int count) {
        int totalSegments = segmentTable.length / SEGMENT_RECORD_SIZE;
        List<SegmentRecord> result = new ArrayList<>(count);
        for (int i = totalSegments - count; i < totalSegments; i++) {
            int offset = i * SegmentRecord.SEGMENT_RECORD_SIZE;
            if (offset >= 0) {
                result.add(parse(segmentTable, offset));
            }
        }
        return result;
    }

    private static SegmentRecord parse(final byte[] table, final int offset) {
        return new SegmentRecord(BitConverter.readInt(table, offset),
                BitConverter.readLong(table, offset + Integer.BYTES),
                toDouble(table, offset + Integer.BYTES + Long.BYTES),
                toDouble(table, offset + Integer.BYTES + Long.BYTES + Double.BYTES));
    }

    private static double toDouble(byte[] b, int offset) {
        return Double.longBitsToDouble(BitConverter.readLong(b, offset));
    }

    byte[] toByteArray() {
        byte[] b = new byte[SEGMENT_RECORD_SIZE];
        BitConverter.writeInt(b, 0, segmentNumber);
        BitConverter.writeLong(b, Integer.BYTES, startTime);
        BitConverter.writeLong(b, Integer.BYTES + Long.BYTES, Double.doubleToRawLongBits(routingKeyStart));
        BitConverter.writeLong(b, Integer.BYTES + 2 * Long.BYTES, Double.doubleToRawLongBits(routingKeyEnd));

        return b;
    }
}
