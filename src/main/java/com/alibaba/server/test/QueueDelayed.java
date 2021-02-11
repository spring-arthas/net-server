package com.alibaba.server.test;

import java.util.concurrent.*;

/**
 * @author spring
 * 任务延迟队列
 */
public class QueueDelayed implements Delayed, Callable<Boolean> {

    /**
     * 延迟时间
     * */
    private Long fireTime;

    /**
     * 延迟处理结果
     * */
    private String result;

    public QueueDelayed(Long delayTime, String result) {
        // 延迟delayTime时间后的触发时间
        this.fireTime = System.currentTimeMillis() + delayTime;
        this.result = result;
    }

    public Long getFireTime() {
        return this.fireTime;
    }

    @Override
    public String toString() {
        return System.currentTimeMillis() + " : " + result;
    }

    @Override
    public long getDelay(TimeUnit unit) {
        return unit.convert(this.fireTime - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public int compareTo(Delayed o) {
        return (int) (this.getDelay(TimeUnit.MILLISECONDS) - o.getDelay(TimeUnit.MILLISECONDS));
    }

    @Override
    public Boolean call() {
        System.out.println("当前线程 thread = " + Thread.currentThread().getName() + "开始进行回流操作");
        // 模拟耗时回流操作
        try {
            TimeUnit.MILLISECONDS.sleep(5000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        System.out.println("当前线程 thread = " + Thread.currentThread().getName() + "回流操作完成");

        return Boolean.FALSE;
    }

    public static void main(String[] args) {
        System.out.println(System.currentTimeMillis() + ": start");
        QueueDelayed queueDelayed1 = new QueueDelayed(1000 * 5L, "延迟任务");
        DelayQueue<QueueDelayed> delayeds = new DelayQueue<QueueDelayed>();
        delayeds.put(queueDelayed1);

        Long loopTime = 5L * 1000;
        while(delayeds.size() > 0) {
            QueueDelayed delayed = null;
            try {
                delayed = delayeds.take();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            /*if(delayed == null) {
                continue;
            }
*/
            FutureTask<Boolean> futureTask = new FutureTask<Boolean>(delayed);
            new Thread(futureTask).start();
            try {
                if(!futureTask.get()) {
                    QueueDelayed queueDelayed2 = new QueueDelayed(1000 * (delayed.getFireTime() + loopTime), "延迟任务2");
                    delayeds.put(queueDelayed1);
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            } catch (ExecutionException e) {
                e.printStackTrace();
            }
        }

        System.out.println("延迟任务执行完成");
    }
}
