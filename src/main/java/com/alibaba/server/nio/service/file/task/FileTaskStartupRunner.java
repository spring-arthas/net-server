package com.alibaba.server.nio.service.file.task;

import com.alibaba.server.common.FileTaskStatusEnum;
import com.alibaba.server.nio.repository.file.service.FileTaskService;
import com.alibaba.server.nio.repository.file.service.dto.FileTaskDto;
import com.alibaba.server.nio.service.file.adaptive.ServerResourceMonitor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;

import javax.annotation.Resource;

import java.util.Date;
import java.util.List;

/**
 * 文件传输任务恢复守护进程
 */
@Slf4j
public class FileTaskStartupRunner implements InitializingBean {

    @Resource
    private FileTaskService fileTaskService;

    @Override
    public void afterPropertiesSet() throws Exception {
        // [修改] 预热资源监控器，确保服务启动后立即开始采样，不等第一个请求触发
        ServerResourceMonitor.getInstance();
        log.info("执行启动恢复任务: 将异常中断的任务标记为 PAUSED...");
        try {
            // 1. 应用重启后查询file_task表如果存在状态为【上传中】的任务时需要将其更新为暂停状态
            resetTaskStatus(FileTaskStatusEnum.UPLOADING.getCode());

            // 2. 应用重启后查询file_task表如果存在状态为【下载中】的任务时需要将其更新为暂停状态
            resetTaskStatus(FileTaskStatusEnum.DOWNLOADING.getCode());

            log.info("启动恢复任务完成。");
        } catch (Exception e) {
            log.error("启动恢复任务执行失败", e);
        }
    }

    private void resetTaskStatus(Integer status) {
        int page = 1;
        int pageSize = 100;
        while (true) {
            com.alibaba.server.nio.repository.file.service.param.FileTaskQueryParam param = new com.alibaba.server.nio.repository.file.service.param.FileTaskQueryParam();
            param.setStatus(status);
            param.setCurrentPage(page);
            param.setPageSize(pageSize);

            com.alibaba.server.nio.core.result.PageResult<FileTaskDto> result = fileTaskService.queryPage(param);
            List<FileTaskDto> tasks = result.getModelList();

            if (tasks == null || tasks.isEmpty()) {
                break;
            }

            for (FileTaskDto task : tasks) {
                try {
                    log.warn("发现异常中断任务，正在重置为 PAUSED: taskId={}, status={}", task.getId(), task.getStatus());
                    FileTaskDto updateDto = new FileTaskDto();
                    updateDto.setId(task.getId());
                    updateDto.setStatus(FileTaskStatusEnum.PAUSED.getCode());
                    updateDto.setGmtModified(new Date());
                    fileTaskService.update(updateDto);
                } catch (Exception e) {
                    log.error("重置任务状态失败: taskId={}", task.getId(), e);
                }
            }

            if (tasks.size() < pageSize) {
                break;
            }
            page++;
        }
    }
}
