package lee.study.down.boot;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.resolver.NoopAddressResolverGroup;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import lee.study.down.constant.HttpDownStatus;
import lee.study.down.dispatch.HttpDownCallback;
import lee.study.down.exception.BootstrapException;
import lee.study.down.handle.HttpDownInitializer;
import lee.study.down.model.ChunkInfo;
import lee.study.down.model.ConnectInfo;
import lee.study.down.model.HttpDownInfo;
import lee.study.down.model.HttpRequestInfo;
import lee.study.down.model.TaskInfo;
import lee.study.down.util.ByteUtil;
import lee.study.down.util.FileUtil;
import lee.study.down.util.HttpDownUtil;
import lee.study.proxyee.util.ProtoUtil.RequestProto;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Getter
@Setter
public class HttpDownBootstrap {

  protected static final Logger LOGGER = LoggerFactory.getLogger(HttpDownBootstrap.class);

  private HttpDownInfo httpDownInfo;
  private NioEventLoopGroup loopGroup;
  private HttpDownCallback callback;

  private ProgressThread progressThread;

  HttpDownBootstrap() {

  }

  public void startDown() throws Exception {
    TaskInfo taskInfo = httpDownInfo.getTaskInfo();
    HttpDownUtil.buildChunkInfoList(taskInfo);
    if (taskInfo.getFilePath() == null || "".equals(taskInfo.getFilePath().trim())) {
      throw new BootstrapException("下载路径不能为空");
    }
    if (!FileUtil.exists(taskInfo.getFilePath())) {
      FileUtil.createDirSmart(taskInfo.getFilePath());
    }
    if (!FileUtil.canWrite(taskInfo.getFilePath())) {
      throw new BootstrapException("无权访问下载路径，请修改路径或开放目录写入权限");
    }
    //磁盘空间不足
    if (taskInfo.getTotalSize() > FileUtil.getDiskFreeSize(taskInfo.getFilePath())) {
      throw new BootstrapException("磁盘空间不足，请修改路径");
    }
    //有文件同名
    if (new File(HttpDownUtil.getTaskFilePath(taskInfo)).exists()) {
      if (httpDownInfo.isAutoRename()) {
        taskInfo.setFileName(FileUtil.renameIfExists(HttpDownUtil.getTaskFilePath(taskInfo)));
      } else {
        throw new BootstrapException("文件名已存在，请修改文件名");
      }
    }
    //创建文件
    if (taskInfo.isSupportRange()) {
      FileUtil.createSparseFile(HttpDownUtil.getTaskFilePath(taskInfo), taskInfo.getTotalSize());
    } else {
      FileUtil.createFile(HttpDownUtil.getTaskFilePath(taskInfo));
    }
    //文件下载开始回调
    HttpDownUtil.reset(taskInfo);
    taskInfo.setStartTime(System.currentTimeMillis());
    commonStart();
    for (int i = 0; i < taskInfo.getConnectInfoList().size(); i++) {
      ConnectInfo connectInfo = taskInfo.getConnectInfoList().get(i);
      connect(connectInfo);
    }
    saveRecord();
    if (callback != null) {
      callback.onStart(httpDownInfo);
    }
  }

  protected void connect(ConnectInfo connectInfo) {
    HttpRequestInfo requestInfo = (HttpRequestInfo) httpDownInfo.getRequest();
    RequestProto requestProto = requestInfo.requestProto();
    LOGGER.debug("开始下载：" + connectInfo);
    Bootstrap bootstrap = new Bootstrap()
        .channel(NioSocketChannel.class)
        .group(loopGroup)
        .handler(new HttpDownInitializer(requestProto.getSsl(), this, connectInfo));
    if (httpDownInfo.getProxyConfig() != null) {
      //代理服务器解析DNS和连接
      bootstrap.resolver(NoopAddressResolverGroup.INSTANCE);
    }
    ChannelFuture cf = bootstrap.connect(requestProto.getHost(), requestProto.getPort());
    //重置最后下载时间
    connectInfo.setConnectChannel(cf.channel());
    cf.addListener((ChannelFutureListener) future -> {
      if (future.isSuccess()) {
        LOGGER.debug("连接成功：" + connectInfo);
        if (httpDownInfo.getTaskInfo().isSupportRange()) {
          requestInfo.headers().set(HttpHeaderNames.RANGE, "bytes=" + connectInfo.getStartPosition() + "-" + connectInfo.getEndPosition());
        } else {
          requestInfo.headers().remove(HttpHeaderNames.RANGE);
        }
        future.channel().writeAndFlush(httpDownInfo.getRequest());
        if (requestInfo.content() != null) {
          //请求体写入
          HttpContent content = new DefaultLastHttpContent();
          content.content().writeBytes(requestInfo.content());
          future.channel().writeAndFlush(content);
        }
      } else {
        future.channel().close();
      }
    });
  }

  /**
   * 重新发起连接
   */
  public void reConnect(ConnectInfo connectInfo) {
    reConnect(connectInfo, false);
  }

  /**
   * 重新发起连接
   *
   * @param connectInfo 连接相关信息
   * @param isHelp 是否为帮助其他分段下载发起的连接
   */
  public void reConnect(ConnectInfo connectInfo, boolean isHelp) {
    TaskInfo taskInfo = httpDownInfo.getTaskInfo();
    if (!isHelp && taskInfo.isSupportRange()) {
      connectInfo.setStartPosition(connectInfo.getStartPosition() + connectInfo.getDownSize());
    }
    connectInfo.setDownSize(0);
    if (connectInfo.getErrorCount() < httpDownInfo.getRetryCount()) {
      connect(connectInfo);
    } else {
      if (taskInfo.getConnectInfoList().stream()
          .filter(connect -> connect.getStatus() != HttpDownStatus.DONE)
          .allMatch(connect -> connect.getErrorCount() >= httpDownInfo.getRetryCount())) {
        close();
        taskInfo.setStatus(HttpDownStatus.FAIL);
        taskInfo.getChunkInfoList().forEach(chunkInfo -> chunkInfo.setStatus(HttpDownStatus.FAIL));
        if (callback != null) {
          callback.onError(httpDownInfo, null);
        }
      }
    }
  }

  /**
   * 暂停下载
   */
  public void pauseDown() throws Exception {
    TaskInfo taskInfo = httpDownInfo.getTaskInfo();
    synchronized (taskInfo) {
      if (taskInfo.getStatus() == HttpDownStatus.PAUSE
          || taskInfo.getStatus() == HttpDownStatus.DONE) {
        return;
      }
      close();
      taskInfo.setStatus(HttpDownStatus.PAUSE);
      long time = System.currentTimeMillis();
      for (ChunkInfo chunkInfo : taskInfo.getChunkInfoList()) {
        if (chunkInfo.getStatus() != HttpDownStatus.DONE) {
          chunkInfo.setStatus(HttpDownStatus.PAUSE);
          chunkInfo.setLastPauseTime(time);
        }
      }
    }
    saveRecord();
    if (callback != null) {
      callback.onPause(httpDownInfo);
    }
  }

  /**
   * 继续下载
   */
  public void continueDown()
      throws Exception {
    TaskInfo taskInfo = httpDownInfo.getTaskInfo();
    synchronized (taskInfo) {
      if (taskInfo.getStatus() == HttpDownStatus.RUNNING
          || taskInfo.getStatus() == HttpDownStatus.DONE) {
        return;
      }
      if (!FileUtil.exists(HttpDownUtil.getTaskFilePath(taskInfo))) {
        close();
        startDown();
      } else {
        commonStart();
        long time = System.currentTimeMillis();
        for (ChunkInfo chunkInfo : taskInfo.getChunkInfoList()) {
          if (chunkInfo.getStatus() == HttpDownStatus.PAUSE) {
            chunkInfo.setStatus(HttpDownStatus.RUNNING);
            chunkInfo.setPauseTime(chunkInfo.getPauseTime() + (time - chunkInfo.getLastPauseTime()));
          }
        }
        for (ConnectInfo connectInfo : taskInfo.getConnectInfoList()) {
          if (connectInfo.getStatus() == HttpDownStatus.RUNNING) {
            reConnect(connectInfo);
          }
        }
      }
    }
    saveRecord();
    if (callback != null) {
      callback.onContinue(httpDownInfo);
    }
  }

  public void done() throws IOException {
    deleteRecord();
    stopThreads();
    if (callback != null) {
      callback.onDone(httpDownInfo);
    }
  }

  public void close() {
    TaskInfo taskInfo = httpDownInfo.getTaskInfo();
    synchronized (taskInfo) {
      for (ConnectInfo connectInfo : httpDownInfo.getTaskInfo().getConnectInfoList()) {
        close(connectInfo);
      }
    }
    stopThreads();
  }

  public void close(ConnectInfo connectInfo) {
    try {
      HttpDownUtil.safeClose(connectInfo.getConnectChannel(), connectInfo.getFileChannel());
    } catch (Exception e) {
      LOGGER.error("closeChunk error", e);
    }
  }

  public void delete(boolean delFile) throws Exception {
    TaskInfo taskInfo = httpDownInfo.getTaskInfo();
    //删除任务进度记录文件
    synchronized (taskInfo) {
      close();
      deleteRecord();
      if (delFile) {
        FileUtil.deleteIfExists(HttpDownUtil.getTaskFilePath(taskInfo));
      }
      if (callback != null) {
        callback.onDelete(httpDownInfo);
      }
    }
  }

  private synchronized void saveRecord() {
    TaskInfo taskInfo = httpDownInfo.getTaskInfo();
    try {
      ByteUtil.serialize(httpDownInfo, HttpDownUtil.getTaskRecordFilePath(taskInfo), HttpDownUtil.getTaskRecordBakFilePath(taskInfo), true);
    } catch (IOException e) {
      LOGGER.error("saveRecord error", e);
    }
  }

  private void deleteRecord() {
    TaskInfo taskInfo = httpDownInfo.getTaskInfo();
    try {
      FileUtil.deleteIfExists(HttpDownUtil.getTaskRecordFilePath(taskInfo));
      FileUtil.deleteIfExists(HttpDownUtil.getTaskRecordBakFilePath(taskInfo));
    } catch (IOException e) {
      LOGGER.error("deleteRecord error", e);
    }
  }

  private void stopThreads() {
    loopGroup.shutdownGracefully();
    loopGroup = null;
    progressThread.close();
    progressThread = null;
  }

  private void commonStart() {
    TaskInfo taskInfo = httpDownInfo.getTaskInfo();
    taskInfo.setStatus(HttpDownStatus.RUNNING);
    taskInfo.setLastStartTime(0);
    taskInfo.getChunkInfoList().forEach(chunkInfo -> {
      if (chunkInfo.getStatus() != HttpDownStatus.DONE) {
        chunkInfo.setStatus(HttpDownStatus.RUNNING);
      }
    });
    taskInfo.getConnectInfoList().forEach(connectInfo -> {
      connectInfo.setErrorCount(0);
      if (connectInfo.getStatus() != HttpDownStatus.DONE) {
        connectInfo.setStatus(HttpDownStatus.RUNNING);
      }
    });
    if (loopGroup == null) {
      loopGroup = new NioEventLoopGroup(1);
    }
    if (progressThread == null) {
      progressThread = new ProgressThread();
      progressThread.start();
    }
  }

  class ProgressThread extends Thread {

    private volatile boolean run = true;
    private int period = 1;

    @Override
    public void run() {
      TaskInfo taskInfo = getHttpDownInfo().getTaskInfo();
      while (run) {
        if (taskInfo.getStatus() != HttpDownStatus.DONE) {
          for (ChunkInfo chunkInfo : taskInfo.getChunkInfoList()) {
            synchronized (chunkInfo) {
              if (chunkInfo.getStatus() != HttpDownStatus.DONE) {
                chunkInfo.setSpeed((chunkInfo.getDownSize() - chunkInfo.getLastCountSize()) / period);
                chunkInfo.setLastCountSize(chunkInfo.getDownSize());
              }
            }
          }
          //计算瞬时速度
          taskInfo.setSpeed(taskInfo.getChunkInfoList()
              .stream()
              .filter(chunkInfo -> chunkInfo.getStatus() != HttpDownStatus.DONE)
              .mapToLong(ChunkInfo::getSpeed)
              .sum());
          callback.onProgress(httpDownInfo);
        }
        try {
          TimeUnit.SECONDS.sleep(period);
        } catch (InterruptedException e) {
          e.printStackTrace();
        }
      }
    }

    public void close() {
      run = false;
    }

  }
}
