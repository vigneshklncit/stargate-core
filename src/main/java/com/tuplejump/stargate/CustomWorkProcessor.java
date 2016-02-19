/*
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.tuplejump.stargate;

import com.lmax.disruptor.*;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * This file is taken as is from com.lmax.disruptor.WorkProcessor v3.0.1
 * except the following
 * the fix in https://github.com/LMAX-Exchange/disruptor/commit/7828e685594325e1716c412f907d442ddef4f4ac has
 * been applied
 * the performance bug fix in https://github.com/LMAX-Exchange/disruptor/commit/d3a4b0794b8ba56f29dbb0dd993e311b3c14dd28 has also
 * been applied
 * <p/>
 * <p/>
 * A {@link CustomWorkProcessor} wraps a single {@link WorkHandler}, effectively consuming the sequence
 * and ensuring appropriate barriers.<p/>
 * <p/>
 * Generally, this will be used as part of a {@link WorkerPool}.
 *
 * @param <T> event implementation storing the details for the work to processed.
 */

public final class CustomWorkProcessor<T>
        implements EventProcessor {
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Sequence sequence = new Sequence(Sequencer.INITIAL_CURSOR_VALUE);
    private final RingBuffer<T> ringBuffer;
    private final SequenceBarrier sequenceBarrier;
    private final WorkHandler<T> workHandler;
    private final ExceptionHandler exceptionHandler;
    private final Sequence workSequence;

    /**
     * Construct a {@link CustomWorkProcessor}.
     *
     * @param ringBuffer       to which events are published.
     * @param sequenceBarrier  on which it is waiting.
     * @param workHandler      is the delegate to which events are dispatched.
     * @param exceptionHandler to be called back when an error occurs
     * @param workSequence     from which to claim the next event to be worked on.  It should always be initialised
     *                         as {@link Sequencer#INITIAL_CURSOR_VALUE}
     */
    public CustomWorkProcessor(final RingBuffer<T> ringBuffer,
                               final SequenceBarrier sequenceBarrier,
                               final WorkHandler<T> workHandler,
                               final ExceptionHandler exceptionHandler,
                               final Sequence workSequence) {
        this.ringBuffer = ringBuffer;
        this.sequenceBarrier = sequenceBarrier;
        this.workHandler = workHandler;
        this.exceptionHandler = exceptionHandler;
        this.workSequence = workSequence;
    }

    @Override
    public Sequence getSequence() {
        return sequence;
    }

    @Override
    public void halt() {
        running.set(false);
        sequenceBarrier.alert();
    }

    /**
     * It is ok to have another thread re-run this method after a halt().
     *
     * @throws IllegalStateException if this processor is already running
     */
    @Override
    public void run() {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("Thread is already running");
        }
        sequenceBarrier.clearAlert();

        notifyStart();

        boolean processedSequence = true;
        long cachedAvailableSequence = Long.MIN_VALUE;
        long nextSequence = sequence.get();
        T event = null;
        while (true) {
            try {
                // if previous sequence was processed - fetch the next sequence and set
                // that we have successfully processed the previous sequence
                // typically, this will be true
                // this prevents the sequence getting too far forward if an exception
                // is thrown from the WorkHandler
                if (processedSequence) {
                    processedSequence = false;
                    nextSequence = workSequence.incrementAndGet();
                    sequence.set(nextSequence - 1L);
                }

                if (cachedAvailableSequence >= nextSequence) {
                    event = ringBuffer.get(nextSequence);
                    workHandler.onEvent(event);
                    processedSequence = true;
                } else {
                    cachedAvailableSequence = sequenceBarrier.waitFor(nextSequence);
                }
            } catch (final AlertException ex) {
                if (!running.get()) {
                    break;
                }
            } catch (final Throwable ex) {
                // handle, mark as procesed, unless the exception handler threw an exception
                exceptionHandler.handleEventException(ex, nextSequence, event);
                processedSequence = true;
            }
        }

        notifyShutdown();

        running.set(false);
    }

    private void notifyStart() {
        if (workHandler instanceof LifecycleAware) {
            try {
                ((LifecycleAware) workHandler).onStart();
            } catch (final Throwable ex) {
                exceptionHandler.handleOnStartException(ex);
            }
        }
    }

    private void notifyShutdown() {
        if (workHandler instanceof LifecycleAware) {
            try {
                ((LifecycleAware) workHandler).onShutdown();
            } catch (final Throwable ex) {
                exceptionHandler.handleOnShutdownException(ex);
            }
        }
    }
}
