// Copyright 2016 Google Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.archivepatcher.generator.bsdiff;

import java.io.IOException;
import java.util.Deque;
import java.util.LinkedList;

/**
 * A Java implementation of the Quick Suffix Sort (qsufsort) algorithm.
 *
 * The original was written in C and is available at http://www.larsson.dogma.net/qsufsort.c. It was
 * originally presented in "Faster Suffix Sorting" by N. Jesper Larsson (jesper@cs.lth.se) and
 * Kunihiko Sadakane (sada@is.s.u-tokyo.ac.jp).
 */
public class QuickSuffixSorter implements SuffixSorter {

  private final RandomAccessObjectFactory randomAccessObjectFactory;

  public QuickSuffixSorter(RandomAccessObjectFactory randomAccessObjectFactory) {
    this.randomAccessObjectFactory = randomAccessObjectFactory;
  }

  /**
   * Base case for the recursive split(), below.
   */
  // Visible for testing only
  static void splitBaseCase(
      final RandomAccessObject groupArray,
      final RandomAccessObject inverseArray,
      final int start,
      final int length,
      final int inverseOffset)
      throws IOException {
    int step = 0;

    for (int outer = start; outer < start + length; outer += step) {
      step = 1;
      groupArray.seekToIntAligned(outer);
      inverseArray.seekToIntAligned(groupArray.readInt() + inverseOffset);
      int x = inverseArray.readInt();

      for (int inner = 1; outer + inner < start + length; inner++) {
        groupArray.seekToIntAligned(outer + inner);
        inverseArray.seekToIntAligned(groupArray.readInt() + inverseOffset);
        final int tempX = inverseArray.readInt();
        if (tempX < x) {
          x = tempX;
          step = 0;
        }

        if (tempX == x) {
          groupArray.seekToIntAligned(outer + step);
          final int temp = groupArray.readInt();
          groupArray.seekToIntAligned(outer + inner);
          final int outerInner = groupArray.readInt();
          groupArray.seekToIntAligned(outer + step);
          groupArray.writeInt(outerInner);
          groupArray.seekToIntAligned(outer + inner);
          groupArray.writeInt(temp);
          step++;
        }
      }

      groupArray.seekToIntAligned(outer);
      for (int innerIndex = 0; innerIndex < step; innerIndex++) {
        inverseArray.seekToIntAligned(groupArray.readInt());
        inverseArray.writeInt(outer + step - 1);
      }

      if (step == 1) {
        groupArray.seekToIntAligned(outer);
        groupArray.writeInt(-1);
      }
    }
  }

  /**
   * Part of the quick suffix sort algorithm.
   */
  // Visible for testing only
  static void split(
      final RandomAccessObject groupArray,
      final RandomAccessObject inverseArray,
      final int start,
      final int length,
      final int inverseOffset)
      throws IOException {
    Deque<SplitTask> taskStack = new LinkedList<>();
    taskStack.add(new SplitTaskStage1(start, length, inverseOffset));
    while (!taskStack.isEmpty()) {
      taskStack.removeFirst().run(groupArray, inverseArray, taskStack);
    }
  }

  /**
   * An interface for split tasks. Split tasks are executed on a stack. A SplitTask can produce
   * other SplitTasks that will be pushed onto the stack and immediately executed.
   */
  private static interface SplitTask {
    /**
     * Execute the task, optionally adding more tasks to be executed.
     */
    public void run(
        final RandomAccessObject groupArray,
        final RandomAccessObject inverseArray,
        Deque<SplitTask> taskStack)
        throws IOException;
  }

  private static class SplitTaskStage1 implements SplitTask {
    private final int start;
    private final int length;
    private final int inverseOffset;

    public SplitTaskStage1(final int start, final int length, final int inverseOffset) {
      this.start = start;
      this.length = length;
      this.inverseOffset = inverseOffset;
    }

    @Override
    public void run(
        final RandomAccessObject groupArray,
        final RandomAccessObject inverseArray,
        final Deque<SplitTask> taskStack)
        throws IOException {
      if (length < 16) {
        // Length is too short to bother recursing.
        splitBaseCase(groupArray, inverseArray, start, length, inverseOffset);
        return;
      }

      // Else, length >= 16
      groupArray.seekToIntAligned(start + length / 2);
      inverseArray.seekToIntAligned(groupArray.readInt() + inverseOffset);
      final int x = inverseArray.readInt();
      int jj = 0;
      int kk = 0;

      groupArray.seekToIntAligned(start);
      for (int index = start; index < start + length; index++) {
        inverseArray.seekToIntAligned(groupArray.readInt() + inverseOffset);
        final int i = inverseArray.readInt();

        if (i < x) {
          jj++;
        } else if (i == x) {
          kk++;
        }
      }

      jj += start;
      kk += jj;

      { // scoping block
        int j = 0;
        int k = 0;
        {
          int i = start;
          while (i < jj) {
            groupArray.seekToIntAligned(i);
            final int groupArrayInt = groupArray.readInt();
            inverseArray.seekToIntAligned(groupArrayInt + inverseOffset);
            final int inverseInt = inverseArray.readInt();

            if (inverseInt < x) {
              i++;
            } else if (inverseInt == x) {
              groupArray.seekToIntAligned(jj + j);
              final int temp = groupArray.readInt();
              groupArray.seekToIntAligned(i);
              groupArray.writeInt(temp);
              groupArray.seekToIntAligned(jj + j);
              groupArray.writeInt(groupArrayInt);
              j++;
            } else { // >x
              groupArray.seekToIntAligned(kk + k);
              final int temp = groupArray.readInt();
              groupArray.seekToIntAligned(i);
              groupArray.writeInt(temp);
              groupArray.seekToIntAligned(kk + k);
              groupArray.writeInt(groupArrayInt);
              k++;
            }
          }
        }

        while (jj + j < kk) {
          groupArray.seekToIntAligned(jj + j);
          final int temp = groupArray.readInt();
          inverseArray.seekToIntAligned(temp + inverseOffset);
          if (inverseArray.readInt() == x) {
            j++;
          } else { // != x
            groupArray.seekToIntAligned(kk + k);
            final int tempkk = groupArray.readInt();
            groupArray.seekToIntAligned(jj + j);
            groupArray.writeInt(tempkk);
            groupArray.seekToIntAligned(kk + k);
            groupArray.writeInt(temp);
            k++;
          }
        }
      }

      // Enqueue tasks to finish all remaining work.
      if (start + length > kk) {
        taskStack.addFirst(new SplitTaskStage1(kk, start + length - kk, inverseOffset));
      }
      taskStack.addFirst(new SplitTaskStage2(jj, kk));
      if (jj > start) {
        taskStack.addFirst(new SplitTaskStage1(start, jj - start, inverseOffset));
      }
    }
  }

  private static class SplitTaskStage2 implements SplitTask {
    private final int jj;
    private final int kk;

    public SplitTaskStage2(final int jj, final int kk) {
      this.jj = jj;
      this.kk = kk;
    }

    @Override
    public void run(
        final RandomAccessObject groupArray,
        final RandomAccessObject inverseArray,
        final Deque<SplitTask> taskStack)
        throws IOException {
      groupArray.seekToIntAligned(jj);
      for (int i = 0; i < kk - jj; i++) {
        inverseArray.seekToIntAligned(groupArray.readInt());
        inverseArray.writeInt(kk - 1);
      }

      if (jj == kk - 1) {
        groupArray.seekToIntAligned(jj);
        groupArray.writeInt(-1);
      }
    }
  }

  /**
   * Initialize a quick suffix sort. Note: the returned {@link RandomAccessObject} should be closed
   * by the caller.
   */
  // Visible for testing only
  RandomAccessObject quickSuffixSortInit(
      final RandomAccessObject data, final RandomAccessObject inverseArray) throws IOException {
    // Generate a histogram of the counts of each byte in the old data:
    // 1. Initialize buckets 0-255 to zero
    // 2. Read each byte and count the number of occurrences of each byte
    // 3. For each bucket, add the previous bucket's value.
    // 4. For each bucket past the first, set the value to the previous
    //    bucket's value
    final int[] buckets = new int[256];

    data.seek(0);
    for (int i = 0; i < data.length(); i++) {
      buckets[data.readUnsignedByte()]++;
    }

    for (int i = 1; i < 256; i++) {
      buckets[i] += buckets[i - 1];
    }

    for (int i = 255; i > 0; i--) {
      buckets[i] = buckets[i - 1];
    }

    buckets[0] = 0;

    if (4 * (data.length() + 1) >= Integer.MAX_VALUE) {
      throw new IllegalArgumentException("Input too large");
    }
    final RandomAccessObject groupArray =
        randomAccessObjectFactory.create(((int) data.length() + 1) * 4);

    try {
      data.seek(0);
      for (int i = 0; i < data.length(); i++) {
        groupArray.seekToIntAligned(++buckets[data.readUnsignedByte()]);
        groupArray.writeInt(i);
      }

      data.seek(0);
      groupArray.seekToIntAligned(0);
      groupArray.writeInt((int) data.length());
      inverseArray.seekToIntAligned(0);
      for (int i = 0; i < data.length(); i++) {
        inverseArray.writeInt(buckets[data.readUnsignedByte()]);
      }

      inverseArray.seekToIntAligned((int) data.length());
      inverseArray.writeInt(0);
      for (int i = 1; i < 256; i++) {
        if (buckets[i] == buckets[i - 1] + 1) {
          groupArray.seekToIntAligned(buckets[i]);
          groupArray.writeInt(-1);
        }
      }

      groupArray.seekToIntAligned(0);
      groupArray.writeInt(-1);
    } catch (IOException e) {
      groupArray.close();
      throw new IOException("Unable to init suffix sorting on groupArray", e);
    }

    return groupArray;
  }

  /**
   * Perform a "quick suffix sort". Note: the returned {@link RandomAccessObject} should be closed
   * by the caller.
   *
   * @param data the data to sort
   */
  @Override
  public RandomAccessObject suffixSort(final RandomAccessObject data) throws IOException {
    if (4 * (data.length() + 1) >= Integer.MAX_VALUE) {
      throw new IllegalArgumentException("Input too large");
    }
    RandomAccessObject groupArray = null;

    try (final RandomAccessObject inverseArray =
        randomAccessObjectFactory.create(((int) data.length() + 1) * 4)) {
      groupArray = quickSuffixSortInit(data, inverseArray);

      int h = 1;
      while (true) {
        groupArray.seekToIntAligned(0);
        if (groupArray.readInt() == -(data.length() + 1)) {
          break;
        }

        int length = 0;
        int i = 0;

        while (i < data.length() + 1) {
          groupArray.seekToIntAligned(i);
          final int groupArrayIndex = groupArray.readInt();
          if (groupArrayIndex < 0) {
            length -= groupArrayIndex;
            i -= groupArrayIndex;
          } else {
            if (length > 0) {
              groupArray.seekToIntAligned(i - length);
              groupArray.writeInt(-length);
            }

            inverseArray.seekToIntAligned(groupArrayIndex);
            length = inverseArray.readInt() + 1 - i;
            split(groupArray, inverseArray, i, length, h);
            i += length;
            length = 0;
          }
        }

        if (length > 0) {
          groupArray.seekToIntAligned(i - length);
          groupArray.writeInt(-length);
        }

        h *= 2;
      }

      inverseArray.seekToIntAligned(0);
      for (int i = 0; i < data.length() + 1; i++) {
        groupArray.seekToIntAligned(inverseArray.readInt());
        groupArray.writeInt(i);
      }
    } catch (Exception e) {
      if (groupArray != null) {
        groupArray.close();
      }
      throw new IOException("Unable to finish suffix sorting groupArray", e);
    }

    return groupArray;
  }

}
