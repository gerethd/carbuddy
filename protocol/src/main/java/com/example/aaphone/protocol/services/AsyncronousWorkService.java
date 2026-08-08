package com.example.aaphone.protocol.services;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public class AsyncronousWorkService {

    private static ScheduledExecutorService executorService = Executors.newScheduledThreadPool(10);

    public static void runTask(Runnable myRunnable) {
        executorService.execute(myRunnable);
    }
}
