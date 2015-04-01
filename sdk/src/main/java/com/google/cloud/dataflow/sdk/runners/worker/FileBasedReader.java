/*******************************************************************************
 * Copyright (C) 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 ******************************************************************************/

package com.google.cloud.dataflow.sdk.runners.worker;

import static com.google.api.client.util.Preconditions.checkNotNull;
import static com.google.cloud.dataflow.sdk.runners.worker.SourceTranslationUtils.cloudPositionToReaderPosition;
import static com.google.cloud.dataflow.sdk.runners.worker.SourceTranslationUtils.cloudProgressToReaderProgress;
import static com.google.cloud.dataflow.sdk.runners.worker.SourceTranslationUtils.splitRequestToApproximateProgress;

import com.google.api.services.dataflow.model.ApproximateProgress;
import com.google.cloud.dataflow.sdk.coders.Coder;
import com.google.cloud.dataflow.sdk.io.TextIO;
import com.google.cloud.dataflow.sdk.util.CoderUtils;
import com.google.cloud.dataflow.sdk.util.IOChannelFactory;
import com.google.cloud.dataflow.sdk.util.IOChannelUtils;
import com.google.cloud.dataflow.sdk.util.common.worker.ProgressTracker;
import com.google.cloud.dataflow.sdk.util.common.worker.Reader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.channels.Channels;
import java.util.Collection;
import java.util.NoSuchElementException;

import javax.annotation.Nullable;

/**
 * Abstract base class for sources that read from files.
 *
 * @param <T> the type of the elements read from the source
 */
public abstract class FileBasedReader<T> extends Reader<T> {
  protected static final int BUF_SIZE = 200;
  protected final String filename;

  @Nullable
  protected final Long startPosition;

  @Nullable
  protected final Long endPosition;
  protected final Coder<T> coder;
  protected final boolean useDefaultBufferSize;

  private static final Logger LOG = LoggerFactory.getLogger(FileBasedReader.class);

  protected FileBasedReader(String filename, @Nullable Long startPosition,
      @Nullable Long endPosition, Coder<T> coder, boolean useDefaultBufferSize) {
    this.filename = filename;
    this.startPosition = startPosition;
    this.endPosition = endPosition;
    this.coder = coder;
    this.useDefaultBufferSize = useDefaultBufferSize;
  }

  /**
   * Returns a new iterator for elements in the given range in the
   * given file.  If the range starts in the middle an element, this
   * element is skipped as it is considered part of the previous
   * range; if the last element that starts in the range finishes
   * beyond the end position, it is still considered part of this
   * range.  In other words, the start position and the end position
   * are "rounded up" to element boundaries.
   *
   * @param endPosition offset of the end position; null means end-of-file
   */
  protected abstract ReaderIterator<T> newReaderIteratorForRangeInFile(IOChannelFactory factory,
      String oneFile, long startPosition, @Nullable Long endPosition) throws IOException;

  /**
   * Returns a new iterator for elements in the given files.  Caller
   * must ensure that the file collection is not empty.
   */
  protected abstract ReaderIterator<T> newReaderIteratorForFiles(
      IOChannelFactory factory, Collection<String> files) throws IOException;

  @Override
  public ReaderIterator<T> iterator() throws IOException {
    IOChannelFactory factory = IOChannelUtils.getFactory(filename);
    Collection<String> inputs = factory.match(filename);
    if (inputs.isEmpty()) {
      throw new FileNotFoundException("No match for file pattern '" + filename + "'");
    }

    if (startPosition != null || endPosition != null) {
      if (inputs.size() != 1) {
        throw new IllegalArgumentException(
            "Offset range specified: [" + startPosition + ", " + endPosition + "), so "
            + "an exact filename was expected, but more than 1 file matched \"" + filename
            + "\" (total " + inputs.size() + "): apparently a filepattern was given.");
      }

      return newReaderIteratorForRangeInFile(factory, inputs.iterator().next(),
          startPosition == null ? 0 : startPosition, endPosition);
    } else {
      return newReaderIteratorForFiles(factory, inputs);
    }
  }

  /**
   * Abstract base class for file-based source iterators.
   */
  protected abstract class FileBasedIterator extends AbstractReaderIterator<T> {
    protected final CopyableSeekableByteChannel seeker;
    protected final PushbackInputStream stream;
    protected final long startOffset;
    protected Long endOffset;
    protected final ProgressTracker<Integer> tracker;
    protected ByteArrayOutputStream nextElement;
    protected boolean nextElementComputed = false;
    protected long offset;
    protected DecompressingStreamFactory compressionStreamFactory;

    FileBasedIterator(CopyableSeekableByteChannel seeker, long startOffset, long offset,
        @Nullable Long endOffset, ProgressTracker<Integer> tracker,
        DecompressingStreamFactory compressionStreamFactory) throws IOException {
      this.seeker = checkNotNull(seeker);
      this.seeker.position(startOffset);
      this.compressionStreamFactory = compressionStreamFactory;
      InputStream inputStream =
          compressionStreamFactory.createInputStream(Channels.newInputStream(seeker));
      BufferedInputStream bufferedStream =
          useDefaultBufferSize
              ? new BufferedInputStream(inputStream)
              : new BufferedInputStream(inputStream, BUF_SIZE);
      this.stream = new PushbackInputStream(bufferedStream, BUF_SIZE);
      this.startOffset = startOffset;
      this.offset = offset;
      this.endOffset = endOffset;
      this.tracker = checkNotNull(tracker);
    }

    /**
     * Reads the next element.
     *
     * @return a {@code ByteArrayOutputStream} containing the contents
     *     of the element, or {@code null} if the end of the stream
     *     has been reached.
     * @throws IOException if an I/O error occurs
     */
    protected abstract ByteArrayOutputStream readElement() throws IOException;

    @Override
    public boolean hasNext() throws IOException {
      computeNextElement();
      return nextElement != null;
    }

    @Override
    public T next() throws IOException {
      advance();
      return CoderUtils.decodeFromByteArray(coder, nextElement.toByteArray());
    }

    void advance() throws IOException {
      computeNextElement();
      if (nextElement == null) {
        throw new NoSuchElementException();
      }
      nextElementComputed = false;
    }

    @Override
    public Progress getProgress() {
      // Currently we assume that only a offset position and fraction are reported as
      // current progress. An implementor can override this method to update
      // other metrics, e.g. report a different completion percentage or remaining time.
      com.google.api.services.dataflow.model.Position currentPosition =
          new com.google.api.services.dataflow.model.Position();
      currentPosition.setByteOffset(offset);

      ApproximateProgress progress = new ApproximateProgress();
      progress.setPosition(currentPosition);

      // If endOffset is null, we don't know the fraction consumed.
      if (endOffset != null) {
        // offset, in principle, can go beyond endOffset, e.g.:
        // - We just read the last record and offset points to its end, which is after endOffset
        // - This is some block-based file format where not every record is a "split point" and some
        //   records can *start* after endOffset (though the first record of the next shard would
        //   start still later).
        progress.setPercentComplete(
            Math.min(1.0f, 1.0f * (offset - startOffset) / (endOffset - startOffset)));
      }

      return cloudProgressToReaderProgress(progress);
    }

    @Override
    public DynamicSplitResult requestDynamicSplit(DynamicSplitRequest splitRequest) {
      checkNotNull(splitRequest);

      // Currently, file-based Reader only supports split at a byte offset.
      ApproximateProgress splitProgress = splitRequestToApproximateProgress(splitRequest);
      com.google.api.services.dataflow.model.Position splitPosition = splitProgress.getPosition();
      if (splitPosition == null) {
        LOG.warn("FileBasedReader only supports split at a Position. Requested: {}",
            splitRequest);
        return null;
      }
      Long splitOffset = splitPosition.getByteOffset();
      if (splitOffset == null) {
        LOG.warn("FileBasedReader only supports split at byte offset. Requested: {}",
            splitPosition);
        return null;
      }
      if (splitOffset <= offset) {
        LOG.info("Already progressed to offset {}, which is after the requested split offset {}",
            offset, splitOffset);
        return null;
      }

      if (endOffset != null && splitOffset >= endOffset) {
        LOG.info(
            "Split requested at an offset beyond the end of the current range: {} >= {}",
            splitOffset, endOffset);
        return null;
      }

      this.endOffset = splitOffset;
      LOG.info("Split FileBasedReader at offset {}", splitOffset);

      return new DynamicSplitResultWithPosition(cloudPositionToReaderPosition(splitPosition));
    }

    /**
     * Returns the end offset of the iterator.
     * The method is called for test ONLY.
     */
    Long getEndOffset() {
      return this.endOffset;
    }

    @Override
    public void close() throws IOException {
      stream.close();
    }

    private void computeNextElement() throws IOException {
      if (nextElementComputed) {
        return;
      }

      if (endOffset == null || offset < endOffset) {
        nextElement = readElement();
      } else {
        nextElement = null;
      }
      nextElementComputed = true;
    }
  }

  /**
   * Factory interface for creating a decompressing {@link InputStream}.
   */
  public interface DecompressingStreamFactory {
    /**
     * Create a decompressing {@link InputStream} from an existing {@link InputStream}.
     *
     * @param inputStream the existing stream
     * @return a stream that decompresses the contents of the existing stream
     * @throws IOException
     */
    public InputStream createInputStream(InputStream inputStream) throws IOException;
  }

  /**
   * Factory for creating decompressing input streams based on a filename and
   * a {@link TextIO.CompressionType}.  If the compression mode is AUTO, the filename
   * is checked against known extensions to determine a compression type to use.
   */
  protected static class FilenameBasedStreamFactory
      implements DecompressingStreamFactory {
    private String filename;
    private TextIO.CompressionType compressionType;

    public FilenameBasedStreamFactory(String filename, TextIO.CompressionType compressionType) {
      this.filename = filename;
      this.compressionType = compressionType;
    }

    protected TextIO.CompressionType getCompressionTypeForAuto() {
      for (TextIO.CompressionType type : TextIO.CompressionType.values()) {
        if (type.matches(filename) && type != TextIO.CompressionType.AUTO
            && type != TextIO.CompressionType.UNCOMPRESSED) {
          return type;
        }
      }
      return TextIO.CompressionType.UNCOMPRESSED;
    }

    @Override
    public InputStream createInputStream(InputStream inputStream) throws IOException {
      if (compressionType == TextIO.CompressionType.AUTO) {
        return getCompressionTypeForAuto().createInputStream(inputStream);
      }
      return compressionType.createInputStream(inputStream);
    }
  }
}
