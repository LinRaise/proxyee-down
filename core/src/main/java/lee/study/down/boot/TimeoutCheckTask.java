package lee.study.down.boot;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import lee.study.down.constant.HttpDownStatus;
import lee.study.down.model.ChunkInfo;
import lee.study.down.model.TaskInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TimeoutCheckTask extends Thread {

  private static final Logger LOGGER = LoggerFactory.getLogger(TimeoutCheckTask.class);

  private static volatile TimeoutCheckTask timeoutCheckTask;

  private int seconds = 10;
  private Map<String, HttpDownBootstrap> bootstrapContent = new HashMap<>();

  private TimeoutCheckTask() {
  }

  private TimeoutCheckTask(int seconds) {
    this.seconds = seconds;
  }

  public static TimeoutCheckTask getInstance() {
    if (timeoutCheckTask == null) {
      synchronized (TimeoutCheckTask.class) {
        if (timeoutCheckTask == null) {
          timeoutCheckTask = new TimeoutCheckTask();
        }
      }
    }
    return timeoutCheckTask;
  }

  public synchronized void setTimeout(int seconds) {
    this.seconds = seconds;
  }


  public void addBoot(HttpDownBootstrap bootstrap) {
    bootstrapContent.put(bootstrap.getHttpDownInfo().getTaskInfo().getId(), bootstrap);
  }

  public void delBoot(String id) {
    bootstrapContent.remove(id);
  }

  @Override
  public void run() {
    while (true) {
      try {
        for (HttpDownBootstrap bootstrap : bootstrapContent.values()) {
          TaskInfo taskInfo = bootstrap.getHttpDownInfo().getTaskInfo();
          if (taskInfo.getChunkInfoList() != null) {
            for (ChunkInfo chunkInfo : taskInfo.getChunkInfoList()) {
              //指定时间没有反应则重新建立连接下载
              if (taskInfo.getStatus() == HttpDownStatus.RUNNING
                  && chunkInfo.getStatus() != HttpDownStatus.DONE
                  && chunkInfo.getStatus() != HttpDownStatus.WAIT
                  && chunkInfo.getStatus() != HttpDownStatus.PAUSE) {
                long nowTime = System.currentTimeMillis();
                taskInfo.setLastTime(System.currentTimeMillis());
                if (nowTime - chunkInfo.getLastDownTime() > seconds * 1000) {
                  LOGGER.debug(seconds + "秒内无响应重试：" + chunkInfo);
                  if (chunkInfo.getStatus() == HttpDownStatus.ERROR_WAIT_CONNECT) {
                    chunkInfo.setErrorCount(chunkInfo.getErrorCount() + 1);
                  }
                  //重试下载
                  //bootstrap.reConnect(chunkInfo);
                }
              }
            }
          }

        }
        TimeUnit.MILLISECONDS.sleep(1000);
      } catch (Exception e) {
        LOGGER.error("checkTask:" + e);
      }
    }
  }
}
