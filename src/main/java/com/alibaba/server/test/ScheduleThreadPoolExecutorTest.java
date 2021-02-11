package com.alibaba.server.test;

import com.google.common.collect.Lists;

import java.beans.ExceptionListener;
import java.util.List;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * @author spring
 * 定时任务执行器
 */
public class ScheduleThreadPoolExecutorTest {

    public static void main(String[] args) {
        //ScheduleThreadPoolExecutorTest.scheduleExecutor();

        List<String> list1 = Lists.newArrayList();
        list1.add("1");
        list1.add("2");
        list1.add("3");
        list1.add("4");
        list1.add("5");
        list1.add("6");
        list1.add("7");
        list1.add("8");
        list1.add("9");
        list1.add("10");

        List<String> list2 = Lists.newArrayList();
        list2.add("8");
        list2.add("5");
        list2.add("2");
        list2.add("1");

        list1.removeAll(list2);

        List<String> newList = list1.stream().filter(predict -> list2.contains(predict)).collect(Collectors.toList()); // 8,5,2,1
        newList = list1.stream().filter(predict -> !list2.contains(predict)).collect(Collectors.toList()); // 3,4,6,7,9,10
        System.out.println(newList);
    }

    /*
    * 基于ScheduleThreadPoolExecutor定时器
    * */

    public static void scheduleExecutor() {
        ScheduledExecutorService scheduledExecutorService = new ScheduledThreadPoolExecutor(Runtime.getRuntime().availableProcessors());

        // 1、按照设置的延迟时间到达后执行定时任务
        /*ScheduledFuture scheduledFuture = scheduledExecutorService.schedule(new Runnable() {
            @Override
            public void run() {
                try {
                    System.out.println("正在执行回流任务");
                    TimeUnit.MILLISECONDS.sleep(5000);
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    System.out.println("回流任务执行完成");
                }
            }
        }, 5, TimeUnit.SECONDS);

        try {
            Object obj = scheduledFuture.get();
            System.out.println(obj);
        } catch (InterruptedException e) {
            e.printStackTrace();
        } catch (ExecutionException e) {
            e.printStackTrace();
        }*/

        System.out.println();
        System.out.println();

        // 2、
        /*scheduledExecutorService.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                try {
                    System.out.println("[scheduleAtFixedRate]  --> 正在执行回流任务, thread = " + Thread.currentThread().getName());
                    TimeUnit.SECONDS.sleep(10);
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    System.out.println("[scheduleAtFixedRate]  --> 回流任务执行完成, thread = " + Thread.currentThread().getName());
                }
            }
        }, 5, 2, TimeUnit.SECONDS);*/

        // 3、
        scheduledExecutorService.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                try {
                    System.out.println("[scheduleWithFixedDelay]  --> 正在执行回流任务, thread = " + Thread.currentThread().getName());
                    TimeUnit.SECONDS.sleep(3);
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    System.out.println("[scheduleWithFixedDelay]  --> 回流任务执行完成, thread = " + Thread.currentThread().getName());
                }
            }
        }, 5, 5, TimeUnit.SECONDS);
    }

    /**
     * 基于单线程的Timer定时器
     */
    public static void timerSchedule() {
    }
}
