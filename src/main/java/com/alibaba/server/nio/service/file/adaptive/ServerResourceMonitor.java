package com.alibaba.server.nio.service.file.adaptive;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.FileReader;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 服务端资源监控器（单例）
 * 每 2 秒采样一次 CPU、JVM 堆、磁盘IO，输出综合压力等级供各文件处理器自适应调压。
 * 采样线程为 daemon 线程，不阻止 JVM 退出。
 */
@Slf4j
public class ServerResourceMonitor {

    private static final ServerResourceMonitor INSTANCE = new ServerResourceMonitor();

    public static ServerResourceMonitor getInstance() {
        return INSTANCE;
    }

    // ---- MXBean ----
    private final com.sun.management.OperatingSystemMXBean osMXBean;
    private final MemoryMXBean memMXBean;
    private final boolean isLinux;

    // ---- 磁盘IO差分采样状态 ----
    private final Map<String, Long> lastIoTimeMap = new HashMap<>();
    private long lastSampleTimeMs = 0;

    // ---- 当前指标（volatile 保证跨线程可见） ----
    private volatile ResourcePressureLevel currentLevel = ResourcePressureLevel.NORMAL;
    private volatile double cpuUsage    = 0.0;
    private volatile double heapUsage   = 0.0;
    private volatile double diskIOUsage = 0.0;

    // ---- 分级阈值：[MODERATE, HIGH, CRITICAL] ----
    private static final double CPU_MODERATE  = 0.60, CPU_HIGH  = 0.75, CPU_CRITICAL  = 0.90;
    private static final double HEAP_MODERATE = 0.70, HEAP_HIGH = 0.80, HEAP_CRITICAL = 0.90;
    private static final double DISK_MODERATE = 0.60, DISK_HIGH = 0.75, DISK_CRITICAL = 0.90;

    private final ScheduledExecutorService scheduler;

    private ServerResourceMonitor() {
        com.sun.management.OperatingSystemMXBean bean = null;
        try {
            bean = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        } catch (Exception e) {
            log.warn("OperatingSystemMXBean 获取失败，CPU 指标将不可用", e);
        }
        this.osMXBean = bean;
        this.memMXBean = ManagementFactory.getMemoryMXBean();
        this.isLinux = System.getProperty("os.name", "").toLowerCase().contains("linux");

        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "server-resource-monitor");
            t.setDaemon(true);
            return t;
        });
        // 首次延迟 2s，之后每 2s 采样一次
        this.scheduler.scheduleAtFixedRate(this::sample, 2, 2, TimeUnit.SECONDS);
        log.info("ServerResourceMonitor 已启动，采样间隔 2s，平台: {}", isLinux ? "Linux（/proc/diskstats）" : "非Linux（磁盘IO以CPU代理）");
    }

    // ======================== 采样 ========================

    private void sample() {
        try {
            // 1. CPU：取进程级与系统级的较大值（更保守）
            double cpu = 0.0;
            if (osMXBean != null) {
                double process = osMXBean.getProcessCpuLoad();
                double system  = osMXBean.getSystemCpuLoad();
                cpu = Math.max(process < 0 ? 0 : process, system < 0 ? 0 : system);
            }
            this.cpuUsage = cpu;

            // 2. JVM 堆内存：used / max
            MemoryUsage heap = memMXBean.getHeapMemoryUsage();
            this.heapUsage = heap.getMax() > 0 ? (double) heap.getUsed() / heap.getMax() : 0.0;

            // 3. 磁盘IO：Linux 读 /proc/diskstats；其他平台用 CPU 代理
            this.diskIOUsage = isLinux ? sampleDiskIO() : this.cpuUsage;

            // 4. 重新计算综合等级
            ResourcePressureLevel prev = this.currentLevel;
            this.currentLevel = computeLevel();

            if (prev != this.currentLevel) {
                log.warn("资源压力等级变更: {} → {} | CPU={}% 堆={}% 磁盘IO={}%",
                        prev, this.currentLevel,
                        String.format("%.1f", cpuUsage * 100),
                        String.format("%.1f", heapUsage * 100),
                        String.format("%.1f", diskIOUsage * 100));
            } else {
                log.debug("资源采样 | CPU={}% 堆={}% 磁盘IO={}% 等级={}",
                        String.format("%.1f", cpuUsage * 100),
                        String.format("%.1f", heapUsage * 100),
                        String.format("%.1f", diskIOUsage * 100),
                        currentLevel);
            }
        } catch (Exception e) {
            log.warn("资源采样异常，维持上次等级: {}", currentLevel, e);
        }
    }

    /**
     * 读取 /proc/diskstats 差分计算磁盘 %util（iostat -x 算法）
     * io_util = delta(io_time_ms) / delta(elapsed_ms)，取所有物理盘最大值
     */
    private double sampleDiskIO() {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/diskstats"))) {
            Map<String, Long> currentMap = new HashMap<>();
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 13) {
                    continue;
                }
                String device = parts[2];
                // 跳过虚拟设备：loop、ram、sr（光驱）
                if (device.startsWith("loop") || device.startsWith("ram") || device.startsWith("sr")) {
                    continue;
                }
                try {
                    // 第13字段（index 12）= io_time_ms（该设备累计 IO 时间，毫秒）
                    currentMap.put(device, Long.parseLong(parts[12]));
                } catch (NumberFormatException ignored) {
                }
            }

            long nowMs = System.currentTimeMillis();
            double maxUtil = 0.0;

            if (!lastIoTimeMap.isEmpty() && lastSampleTimeMs > 0) {
                long deltaMs = nowMs - lastSampleTimeMs;
                if (deltaMs > 0) {
                    for (Map.Entry<String, Long> entry : currentMap.entrySet()) {
                        Long prev = lastIoTimeMap.get(entry.getKey());
                        if (prev != null) {
                            // delta_io_time / delta_elapsed = 利用率（0.0~1.0+）
                            double util = (double) (entry.getValue() - prev) / deltaMs;
                            maxUtil = Math.max(maxUtil, util);
                        }
                    }
                }
            }

            lastIoTimeMap.clear();
            lastIoTimeMap.putAll(currentMap);
            lastSampleTimeMs = nowMs;
            return Math.min(1.0, maxUtil);
        } catch (Exception e) {
            // /proc/diskstats 不可读时 fallback 到 CPU
            return cpuUsage;
        }
    }

    private ResourcePressureLevel computeLevel() {
        if (cpuUsage >= CPU_CRITICAL || heapUsage >= HEAP_CRITICAL || diskIOUsage >= DISK_CRITICAL) {
            return ResourcePressureLevel.CRITICAL;
        }
        if (cpuUsage >= CPU_HIGH || heapUsage >= HEAP_HIGH || diskIOUsage >= DISK_HIGH) {
            return ResourcePressureLevel.HIGH;
        }
        if (cpuUsage >= CPU_MODERATE || heapUsage >= HEAP_MODERATE || diskIOUsage >= DISK_MODERATE) {
            return ResourcePressureLevel.MODERATE;
        }
        return ResourcePressureLevel.NORMAL;
    }

    // ======================== 对外接口 ========================

    public ResourcePressureLevel getCurrentLevel() {
        return currentLevel;
    }

    public double getCpuUsage() {
        return cpuUsage;
    }

    public double getHeapUsage() {
        return heapUsage;
    }

    public double getDiskIOUsage() {
        return diskIOUsage;
    }
}
