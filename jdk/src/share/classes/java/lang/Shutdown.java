/*
 * Copyright (c) 1999, 2005, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package java.lang;


/**
 * Package-private utility class containing data structures and logic
 * governing the virtual-machine shutdown sequence.
 *
 * @author   Mark Reinhold
 * @since    1.3
 */

public class Shutdown {

    /* Shutdown state */
    private static final int RUNNING = 0;
    private static final int HOOKS = 1;
    private static final int FINALIZERS = 2;
    private static int state = RUNNING;

    /* Should we run all finalizers upon exit? */
    private static boolean runFinalizersOnExit = false;

    // The system shutdown hooks are registered with a predefined slot.
    // The list of shutdown hooks is as follows:
    // (0) Console restore hook
    // (1) Application hooks
    // (2) DeleteOnExit hook
    private static final int MAX_SYSTEM_HOOKS = 10;
    private static final Runnable[] hooks = new Runnable[MAX_SYSTEM_HOOKS];

    // the index of the currently running shutdown hook to the hooks array
    private static int currentRunningHook = 0;

    /* The preceding static fields are protected by this lock */
    private static class Lock { };
    private static Object lock = new Lock();

    /* Lock object for the native halt method */
    private static Object haltLock = new Lock();

    /* Invoked by Runtime.runFinalizersOnExit */
    static void setRunFinalizersOnExit(boolean run) {
        synchronized (lock) {
            runFinalizersOnExit = run;
        }
    }


    /**
     * Add a new shutdown hook.  Checks the shutdown state and the hook itself,
     * but does not do any security checks.
     *
     * The registerShutdownInProgress parameter should be false except
     * registering the DeleteOnExitHook since the first file may
     * be added to the delete on exit list by the application shutdown
     * hooks.
     *
     * @params slot  the slot in the shutdown hook array, whose element
     *               will be invoked in order during shutdown
     * @params registerShutdownInProgress true to allow the hook
     *               to be registered even if the shutdown is in progress.
     * @params hook  the hook to be registered
     *
     * @throw IllegalStateException
     *        if registerShutdownInProgress is false and shutdown is in progress; or
     *        if registerShutdownInProgress is true and the shutdown process
     *           already passes the given slot
     */
    static void add(int slot, boolean registerShutdownInProgress, Runnable hook) {
        synchronized (lock) {
            if (hooks[slot] != null)
                throw new InternalError("Shutdown hook at slot " + slot + " already registered");

            if (!registerShutdownInProgress) {
                if (state > RUNNING)
                    throw new IllegalStateException("Shutdown in progress");
            } else {
                if (state > HOOKS || (state == HOOKS && slot <= currentRunningHook))
                    throw new IllegalStateException("Shutdown in progress");
            }

            hooks[slot] = hook;
        }
    }

    /* Run all registered shutdown hooks
     */
    private static void runHooks() {
        for (int i=0; i < MAX_SYSTEM_HOOKS; i++) {
            try {
                Runnable hook;
                synchronized (lock) {
                    // acquire the lock to make sure the hook registered during
                    // shutdown is visible here.
                    currentRunningHook = i;
                    hook = hooks[i];
                }
                if (hook != null) hook.run();
            } catch(Throwable t) {
                if (t instanceof ThreadDeath) {
                    ThreadDeath td = (ThreadDeath)t;
                    throw td;
                }
            }
        }
    }

    /* The halt method is synchronized on the halt lock
     * to avoid corruption of the delete-on-shutdown file list.
     * It invokes the true native halt method.
     */
    static void halt(int status) {
        synchronized (haltLock) {
            if (isHotTubVM()) {
                System.err.print("[HotTub] in System.halt at " + System.currentTimeMillis() + "\n");
                saveRetVal(status);
                //runHooks();
                // should never return
                kill_threads();
            }
            halt0(status);
        }
    }

    static native void halt0(int status);

    /* Wormhole for invoking java.lang.ref.Finalizer.runAllFinalizers */
    private static native void runAllFinalizers();

    private static native void saveRetVal(int status);
    private static native boolean isHotTubVM();

    /* The actual shutdown sequence is defined here.
     *
     * If it weren't for runFinalizersOnExit, this would be simple -- we'd just
     * run the hooks and then halt.  Instead we need to keep track of whether
     * we're running hooks or finalizers.  In the latter case a finalizer could
     * invoke exit(1) to cause immediate termination, while in the former case
     * any further invocations of exit(n), for any n, simply stall.  Note that
     * if on-exit finalizers are enabled they're run iff the shutdown is
     * initiated by an exit(0); they're never run on exit(n) for n != 0 or in
     * response to SIGINT, SIGTERM, etc.
     */
    private static void sequence() {
        synchronized (lock) {
            /* Guard against the possibility of a daemon thread invoking exit
             * after DestroyJavaVM initiates the shutdown sequence
             */
            if (state != HOOKS) return;
        }
        runHooks();
        boolean rfoe;
        synchronized (lock) {
            state = FINALIZERS;
            rfoe = runFinalizersOnExit;
        }
        if (rfoe) runAllFinalizers();
    }


    /* Invoked by Runtime.exit, which does all the security checks.
     * Also invoked by handlers for system-provided termination events,
     * which should pass a nonzero status code.
     */
    static void exit(int status) {
        boolean runMoreFinalizers = false;
        synchronized (lock) {
            if (status != 0) runFinalizersOnExit = false;
            switch (state) {
            case RUNNING:       /* Initiate shutdown */
                state = HOOKS;
                break;
            case HOOKS:         /* Stall and halt */
                break;
            case FINALIZERS:
                if (status != 0) {
                    /* Halt immediately on nonzero status */
                    halt(status);
                } else {
                    /* Compatibility with old behavior:
                     * Run more finalizers and then halt
                     */
                    runMoreFinalizers = runFinalizersOnExit;
                }
                break;
            }
        }
        if (runMoreFinalizers) {
            runAllFinalizers();
            halt(status);
        }
        synchronized (Shutdown.class) {
            /* Synchronize on the class object, causing any other thread
             * that attempts to initiate shutdown to stall indefinitely
             */
            sequence();
            halt(status);
        }
    }


    /* Invoked by the JNI DestroyJavaVM procedure when the last non-daemon
     * thread has finished.  Unlike the exit method, this method does not
     * actually halt the VM.
     */
    static void shutdown() {
        synchronized (lock) {
            switch (state) {
            case RUNNING:       /* Initiate shutdown */
                state = HOOKS;
                break;
            case HOOKS:         /* Stall and then return */
            case FINALIZERS:
                break;
            }
        }
        synchronized (Shutdown.class) {
            sequence();
        }
    }

    static final Thread.UncaughtExceptionHandler UEH = new Thread.UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
            // pass
        }
    };
    static void kill_daemon_threads() {
        if (Thread.currentThread().isDaemon()) {
            throw new IllegalStateException("Shutdown.kill_daemon_threads running in a Daemon thread!");
        }
        int count = 0;
        String buf0 = "";
        for (int i = 1; i < 2; i++) {
            for (Thread th : Thread.getAllStackTraces().keySet()) {
                // daemons 2, 3, 4 from JVM: reference handler, finalizer, signal dispatcher
                if (th.isDaemon() && th.getId() > 4) {
                    buf0 += "[HotTub][info][Shutdown::kill_daemon_threads] killing daemon " + th + "\n";
                    System.err.print("[HotTub] in kill daemon threads, killing " + th + "\n");
                    so_it_goes(th);
                    count++;
                } else {
                    System.err.print("[HotTub][info][Shutdown::kill_daemon_threads] not killing non daemon " + th + "\n");
                }
            }
            if (i == 0) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ex) {}
            }
        }
        System.err.print(buf0);
        System.err.print("[HotTub][info][Shutdown::kill_daemon_threads] killed " + count + " daemons.\n");
    }

    public static volatile boolean hottub_death = false;
    static void kill_threads() {
        hottub_death = true;
        final Runnable r = new Runnable() {
            @Override
            public void run() {
                final Thread self = Thread.currentThread();
                int count = 0;
                int daemons = 0;
                String buf0 = "";
                String buf1 = "";
                int round = 0;
                while (true) {
                    int alive = 0;
                    System.err.print("[HotTub] kill_threads round " + round + " start\n");
                    for (java.util.Map.Entry<Thread, StackTraceElement[]> pair : Thread.getAllStackTraces().entrySet()) {
                        Thread th = pair.getKey();
                        if (th == self) {
                            continue;
                        }
                        if (th.isDaemon()) {
                            buf0 += "[HotTub][info][Shutdown::kill_threads] not killing daemon " + th + "\n";
                            daemons++;
                        } else {
                            buf1 += "[HotTub][info][Shutdown::kill_threads] killing non-daemon " + th + "\n";
                            System.err.print("[HotTub][Shutdown::kill_threads killing " + th + "\n");
                            if (round == 0) {
                                for (StackTraceElement ste : pair.getValue()) {
                                    System.err.print("- " + ste + "\n");
                                }
                            }
                            so_it_goes(th);
                            count++;
                            alive++;
                        }
                    }
                    break;
                    /*
                    if (alive == 0) {
                        break;
                    }
                    try {
                        Thread.sleep(1000);
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                    System.err.print("[HotTub] kill_threads round " + round + " end\n");
                    round++;
                    */
                }
                System.err.print(buf0 + buf1);
                System.err.print("[HotTub][info][Shutdown::kill_threads] killed " + count + " (excluding self), there are " + daemons + " daemons.\n");
                /*
                Thread.yield();
                for (java.util.Map.Entry<Thread, StackTraceElement[]> pair : Thread.getAllStackTraces().entrySet()) {
                    Thread th = pair.getKey();
                    if (th == self) {
                        System.err.print("[HotTub] join skipping self " + th + "\n");
                        continue;
                    }
                    if (th.isDaemon()) {
                        System.err.print("[HotTub] join skipping daemon " + th + "\n");
                    } else {
                        long t0 = System.currentTimeMillis();
                        System.err.print("[HotTub] join waiting for " + th + "\n");
                        for (StackTraceElement ste : pair.getValue()) {
                            System.err.print("- " + ste + "\n");
                        }
                        try {
                            th.join();
                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                        System.err.print("[HotTub] join done with " + th + " after " + (System.currentTimeMillis() - t0) + " ms\n");
                    }
                }
                */
                so_it_goes(self);
            }
        };
        r.run();
        /*
        Thread grim_reaper = new Thread(r, "Grim Reaper");
        grim_reaper.setDaemon(false);
        grim_reaper.start();
        try {
            grim_reaper.join();
        } catch (InterruptedException ex) {
            so_it_goes(Thread.currentThread());
        }
        */
        //hottub_death = false;
    }

    static void so_it_goes(Thread th) {
        th.setUncaughtExceptionHandler(UEH);
        try {
            //th.interrupt();
            //java.util.concurrent.locks.LockSupport.unpark(th);
            th.stop();
            //th.stop_hottub();
        } catch (IllegalMonitorStateException ex) {}
    }
}
