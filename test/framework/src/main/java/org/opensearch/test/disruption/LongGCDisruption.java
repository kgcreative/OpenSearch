/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

/*
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.test.disruption;

import org.opensearch.common.Nullable;
import org.opensearch.common.SuppressForbidden;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.common.util.concurrent.AbstractRunnable;
import org.opensearch.test.InternalTestCluster;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.Arrays;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Suspends all threads on the specified node in order to simulate a long gc.
 */
public class LongGCDisruption extends SingleNodeDisruption {

    private static final Pattern[] unsafeClasses = new Pattern[]{
        // logging has shared JVM locks; we may suspend a thread and block other nodes from doing their thing
        Pattern.compile("logging\\.log4j"),
        // security manager is shared across all nodes and it uses synchronized maps internally
        Pattern.compile("java\\.lang\\.SecurityManager"),
        // SecureRandom instance from SecureRandomHolder class is shared by all nodes
        Pattern.compile("java\\.security\\.SecureRandom")
    };

    private static final ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();

    protected final String disruptedNode;
    private Set<Thread> suspendedThreads;
    private Thread blockDetectionThread;

    private final AtomicBoolean sawSlowSuspendBug = new AtomicBoolean(false);

    public LongGCDisruption(Random random, String disruptedNode) {
        super(random);
        this.disruptedNode = disruptedNode;
    }

    /**
     * Checks if during disruption we ran into a known JVM issue that makes {@link Thread#suspend()} calls block for multiple seconds
     * was observed.
     * @see <a href=https://bugs.openjdk.java.net/browse/JDK-8218446>JDK-8218446</a>
     * @return true if during thread suspending a call to {@link Thread#suspend()} took more than 3s
     */
    public boolean sawSlowSuspendBug() {
        return sawSlowSuspendBug.get();
    }

    @Override
    public synchronized void startDisrupting() {
        if (suspendedThreads == null) {
            boolean success = false;
            try {
                suspendedThreads = ConcurrentHashMap.newKeySet();

                final String currentThreadName = Thread.currentThread().getName();
                assert isDisruptedNodeThread(currentThreadName) == false :
                    "current thread match pattern. thread name: " + currentThreadName + ", node: " + disruptedNode;
                // we spawn a background thread to protect against deadlock which can happen
                // if there are shared resources between caller thread and suspended threads
                // see unsafeClasses to how to avoid that
                final AtomicReference<Exception> suspendingError = new AtomicReference<>();
                final Thread suspendingThread = new Thread(new AbstractRunnable() {
                    @Override
                    public void onFailure(Exception e) {
                        suspendingError.set(e);
                    }

                    @Override
                    protected void doRun() throws Exception {
                        // keep trying to suspend threads, until no new threads are discovered.
                        while (suspendThreads(suspendedThreads)) {
                            if (Thread.interrupted()) {
                                return;
                            }
                        }
                    }
                });
                suspendingThread.setName(currentThreadName + "[LongGCDisruption][threadSuspender]");
                suspendingThread.start();
                try {
                    suspendingThread.join(getSuspendingTimeoutInMillis());
                } catch (InterruptedException e) {
                    suspendingThread.interrupt(); // best effort to signal suspending
                    throw new RuntimeException(e);
                }
                if (suspendingError.get() != null) {
                    throw new RuntimeException("unknown error while suspending threads", suspendingError.get());
                }
                if (suspendingThread.isAlive()) {
                    logger.warn(
                        "failed to suspend node [{}]'s threads within [{}] millis. Suspending thread stack trace:\n {}" +
                            "\nThreads that weren't suspended:\n {}"
                        , disruptedNode, getSuspendingTimeoutInMillis(), stackTrace(suspendingThread.getStackTrace()),
                        suspendedThreads.stream()
                            .map(t -> t.getName() + "\n----\n" + stackTrace(t.getStackTrace()))
                            .collect(Collectors.joining("\n"))
                    );
                    suspendingThread.interrupt(); // best effort;
                    try {
                        /*
                         * We need to join on the suspending thread in case it has suspended a thread that is in a critical section and
                         * needs to be resumed.
                         */
                        suspendingThread.join();
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    throw new RuntimeException("suspending node threads took too long");
                }
                // block detection checks if other threads are blocked waiting on an object that is held by one
                // of the threads that was suspended
                if (isBlockDetectionSupported()) {
                    blockDetectionThread = new Thread(new AbstractRunnable() {
                        @Override
                        public void onFailure(Exception e) {
                            if (e instanceof InterruptedException == false) {
                                throw new AssertionError("unexpected exception in blockDetectionThread", e);
                            }
                        }

                        @Override
                        protected void doRun() throws Exception {
                            while (Thread.currentThread().isInterrupted() == false) {
                                ThreadInfo[] threadInfos = threadBean.dumpAllThreads(true, true);
                                for (ThreadInfo threadInfo : threadInfos) {
                                    if (isDisruptedNodeThread(threadInfo.getThreadName()) == false &&
                                        threadInfo.getLockOwnerName() != null &&
                                        isDisruptedNodeThread(threadInfo.getLockOwnerName())) {

                                        // find ThreadInfo object of the blocking thread (if available)
                                        ThreadInfo blockingThreadInfo = null;
                                        for (ThreadInfo otherThreadInfo : threadInfos) {
                                            if (otherThreadInfo.getThreadId() == threadInfo.getLockOwnerId()) {
                                                blockingThreadInfo = otherThreadInfo;
                                                break;
                                            }
                                        }
                                        onBlockDetected(threadInfo, blockingThreadInfo);
                                    }
                                }
                                Thread.sleep(getBlockDetectionIntervalInMillis());
                            }
                        }
                    });
                    blockDetectionThread.setName(currentThreadName + "[LongGCDisruption][blockDetection]");
                    blockDetectionThread.start();
                }
                success = true;
            } finally {
                if (success == false) {
                    stopBlockDetection();
                    // resume threads if failed
                    resumeThreads(suspendedThreads);
                    suspendedThreads = null;
                }
            }
        } else {
            throw new IllegalStateException("can't disrupt twice, call stopDisrupting() first");
        }
    }

    public boolean isDisruptedNodeThread(String threadName) {
        return threadName.contains("[" + disruptedNode + "]");
    }

    private String stackTrace(StackTraceElement[] stackTraceElements) {
        return Arrays.stream(stackTraceElements).map(Object::toString).collect(Collectors.joining("\n"));
    }

    @Override
    public synchronized void stopDisrupting() {
        stopBlockDetection();
        if (suspendedThreads != null) {
            resumeThreads(suspendedThreads);
            suspendedThreads = null;
        }
    }

    private void stopBlockDetection() {
        if (blockDetectionThread != null) {
            try {
                blockDetectionThread.interrupt(); // best effort
                blockDetectionThread.join(getSuspendingTimeoutInMillis());
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            blockDetectionThread = null;
        }
    }

    @Override
    public void removeAndEnsureHealthy(InternalTestCluster cluster) {
        removeFromCluster(cluster);
        ensureNodeCount(cluster);
    }

    @Override
    public TimeValue expectedTimeToHeal() {
        return TimeValue.timeValueMillis(0);
    }

    /**
     * resolves all threads belonging to given node and suspends them if their current stack trace
     * is "safe". Threads are added to nodeThreads if suspended.
     *
     * returns true if some live threads were found. The caller is expected to call this method
     * until no more "live" are found.
     */
    @SuppressWarnings("deprecation") // suspends/resumes threads intentionally
    @SuppressForbidden(reason = "suspends/resumes threads intentionally")
    protected boolean suspendThreads(Set<Thread> nodeThreads) {
        Thread[] allThreads = null;
        while (allThreads == null) {
            allThreads = new Thread[Thread.activeCount()];
            if (Thread.enumerate(allThreads) > allThreads.length) {
                // we didn't make enough space, retry
                allThreads = null;
            }
        }
        boolean liveThreadsFound = false;
        for (Thread thread : allThreads) {
            if (thread == null) {
                continue;
            }
            String threadName = thread.getName();
            if (isDisruptedNodeThread(threadName)) {
                if (thread.isAlive() && nodeThreads.add(thread)) {
                    liveThreadsFound = true;
                    logger.trace("suspending thread [{}]", threadName);
                    // we assume it is not safe to suspend the thread
                    boolean safe = false;
                    try {
                        /*
                         * At the bottom of this try-block we will know whether or not it is safe to suspend the thread; we start by
                         * assuming that it is safe.
                         */
                        boolean definitelySafe = true;
                        final long startTime = System.nanoTime();
                        thread.suspend();
                        if (System.nanoTime() - startTime > TimeUnit.SECONDS.toNanos(3L)) {
                            sawSlowSuspendBug.set(true);
                        }
                        // double check the thread is not in a shared resource like logging; if so, let it go and come back
                        safe:
                        for (StackTraceElement stackElement : thread.getStackTrace()) {
                            String className = stackElement.getClassName();
                            for (Pattern unsafePattern : getUnsafeClasses()) {
                                if (unsafePattern.matcher(className).find()) {
                                    // it is definitely not safe to suspend the thread
                                    definitelySafe = false;
                                    break safe;
                                }
                            }
                        }
                        safe = definitelySafe;
                    } finally {
                        if (!safe) {
                            /*
                             * Do not log before resuming as we might be interrupted while logging in which case we will throw an
                             * interrupted exception and never resume the suspended thread that is in a critical section. Also, logging
                             * before resuming makes for confusing log messages if we never hit the resume.
                             */
                            thread.resume();
                            logger.trace("resumed thread [{}] as it is in a critical section", threadName);
                            nodeThreads.remove(thread);
                        }
                    }
                }
            }
        }
        return liveThreadsFound;
    }

    // for testing
    protected Pattern[] getUnsafeClasses() {
        return unsafeClasses;
    }

    // for testing
    protected long getSuspendingTimeoutInMillis() {
        return TimeValue.timeValueSeconds(30).getMillis();
    }

    public boolean isBlockDetectionSupported() {
        return threadBean.isObjectMonitorUsageSupported() && threadBean.isSynchronizerUsageSupported();
    }

    // for testing
    protected long getBlockDetectionIntervalInMillis() {
        return 3000L;
    }

    // for testing
    protected void onBlockDetected(ThreadInfo blockedThread, @Nullable ThreadInfo blockingThread) {
        String blockedThreadStackTrace = stackTrace(blockedThread.getStackTrace());
        String blockingThreadStackTrace = blockingThread != null ?
            stackTrace(blockingThread.getStackTrace()) : "not available";
        throw new AssertionError("Thread [" + blockedThread.getThreadName() + "] is blocked waiting on the resource [" +
            blockedThread.getLockInfo() + "] held by the suspended thread [" + blockedThread.getLockOwnerName() +
            "] of the disrupted node [" + disruptedNode + "].\n" +
            "Please add this occurrence to the unsafeClasses list in [" + LongGCDisruption.class.getName() + "].\n" +
            "Stack trace of blocked thread: " + blockedThreadStackTrace + "\n" +
            "Stack trace of blocking thread: " + blockingThreadStackTrace);
    }

    @SuppressWarnings("deprecation") // suspends/resumes threads intentionally
    @SuppressForbidden(reason = "suspends/resumes threads intentionally")
    protected void resumeThreads(Set<Thread> threads) {
        for (Thread thread : threads) {
            thread.resume();
        }
    }
}
