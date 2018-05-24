package lee.study.down.boot;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.resolver.NoopAddressResolverGroup;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import javax.net.ssl.SSLException;
import lee.study.down.constant.HttpDownStatus;
import lee.study.down.dispatch.HttpDownCallback;
import lee.study.down.exception.BootstrapException;
import lee.study.down.handle.HttpDownInitializer;
import lee.study.down.model.ChunkInfo;
import lee.study.down.model.ConnectInfo;
import lee.study.down.model.HttpDownInfo;
import lee.study.down.model.HttpRequestInfo;
import lee.study.down.model.TaskInfo;
import lee.study.down.util.FileUtil;
import lee.study.down.util.HttpDownUtil;
import lee.study.proxyee.util.ProtoUtil.RequestProto;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.Accessors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Data
public class HttpDownBootstrap {

  protected static final Logger LOGGER = LoggerFactory.getLogger(HttpDownBootstrap.class);

  private static SslContext trustSslContext;

  private HttpDownInfo httpDownInfo;
  private int retryCount;
  private HttpDownCallback callback;

  private NioEventLoopGroup clientLoopGroup;

  private HttpDownBootstrap() {

  }

  public static HttpDownBootstrapBuilder builder() {
    return new HttpDownBootstrapBuilder();
  }

  public void startDown() throws Exception {
    TaskInfo taskInfo = httpDownInfo.getTaskInfo();
    taskInfo.buildChunkInfoList();
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
    if (new File(taskInfo.buildTaskFilePath()).exists()) {
      throw new BootstrapException("文件名已存在，请修改文件名");
    }
    //创建文件
    FileUtil.createSparseFile(taskInfo.buildTaskFilePath(), taskInfo.getTotalSize());
    //文件下载开始回调
    taskInfo.reset();
    taskInfo.setStatus(HttpDownStatus.RUNNING);
    taskInfo.setStartTime(System.currentTimeMillis());
    //线程池初始化
    clientLoopGroup = new NioEventLoopGroup(1);
    for (int i = 0; i < taskInfo.getConnectInfoList().size(); i++) {
      ChunkInfo chunkInfo = taskInfo.getChunkInfoList().get(i);
      ConnectInfo connectInfo = taskInfo.getConnectInfoList().get(i);
      chunkInfo.setStartTime(System.currentTimeMillis());
      connect(connectInfo);
    }
    if (callback != null) {
      callback.onStart(httpDownInfo);
    }
  }

  protected void connect(ConnectInfo connectInfo) throws Exception {
    HttpRequestInfo requestInfo = (HttpRequestInfo) httpDownInfo.getRequest();
    RequestProto requestProto = requestInfo.requestProto();
    LOGGER.debug("开始下载：" + connectInfo);
    Bootstrap bootstrap = new Bootstrap()
        .channel(NioSocketChannel.class)
        .group(clientLoopGroup)
        .handler(new HttpDownInitializer(requestProto.getSsl(), this, connectInfo));
    if (httpDownInfo.getProxyConfig() != null) {
      //代理服务器解析DNS和连接
      bootstrap.resolver(NoopAddressResolverGroup.INSTANCE);
    }
    ChannelFuture cf = bootstrap.connect(requestProto.getHost(), requestProto.getPort());
    //重置最后下载时间
    connectInfo.setLastActionTime(System.currentTimeMillis());
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
  public void reConnect(ConnectInfo connectInfo)
      throws Exception {
    TaskInfo taskInfo = httpDownInfo.getTaskInfo();
    if (taskInfo.isSupportRange()) {
      connectInfo.setStartPosition(connectInfo.getStartPosition() + connectInfo.getDownSize());
    }
    if (connectInfo.getErrorCount() < retryCount) {
      connect(connectInfo);
    } else {
      if (taskInfo.getConnectInfoList().stream()
          .filter(connect -> connect.getStatus() != HttpDownStatus.DONE)
          .allMatch(connect -> connect.getErrorCount() >= retryCount)) {
        taskInfo.setStatus(HttpDownStatus.FAIL);
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
      taskInfo.setStatus(HttpDownStatus.PAUSE);
      for (ChunkInfo chunkInfo : taskInfo.getChunkInfoList()) {
        synchronized (chunkInfo) {
          if (chunkInfo.getStatus() != HttpDownStatus.DONE) {
            chunkInfo.setStatus(HttpDownStatus.PAUSE);
          }
        }
      }
      close();
    }
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
      if (!FileUtil.exists(taskInfo.buildTaskFilePath())) {
        close();
        startDown();
      } else {
        taskInfo.setStatus(HttpDownStatus.RUNNING);
        taskInfo.getChunkInfoList().forEach((chunk) -> chunk.setErrorCount(0));
        long curTime = System.currentTimeMillis();
        taskInfo.setPauseTime(taskInfo.getPauseTime() + (curTime - taskInfo.getLastTime()));
        taskInfo.setLastTime(curTime);
        //线程初始化
        clientLoopGroup = new NioEventLoopGroup(1);
        for (ConnectInfo connectInfo : taskInfo.getConnectInfoList()) {
          if (connectInfo.getStatus() == HttpDownStatus.PAUSE
              || connectInfo.getStatus() == HttpDownStatus.CONNECTING_FAIL) {
          }
        }
        /*for (ChunkInfo chunkInfo : taskInfo.getChunkInfoList()) {
          if (chunkInfo.getStatus() == HttpDownStatus.PAUSE
              || chunkInfo.getStatus() == HttpDownStatus.CONNECTING_FAIL) {
            chunkInfo.setPauseTime(taskInfo.getPauseTime());
            chunkInfo.setLastTime(curTime);
          }
        }*/
      }
    }
    if (callback != null) {
      callback.onContinue(httpDownInfo);
    }
  }

  public void close() {
    TaskInfo taskInfo = httpDownInfo.getTaskInfo();
    synchronized (taskInfo) {
      for (ConnectInfo connectInfo : httpDownInfo.getTaskInfo().getConnectInfoList()) {
        close(connectInfo);
      }
    }
    if (clientLoopGroup != null) {
      clientLoopGroup.shutdownGracefully();
    }
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
      TimeoutCheckTask.getInstance().delBoot(httpDownInfo.getTaskInfo().getId());
      FileUtil.deleteIfExists(taskInfo.buildTaskRecordFilePath());
      FileUtil.deleteIfExists(taskInfo.buildTaskRecordBakFilePath());
      if (delFile) {
        FileUtil.deleteIfExists(taskInfo.buildTaskFilePath());
      }
      if (callback != null) {
        callback.onDelete(httpDownInfo);
      }
    }
  }

  public static class HttpDownBootstrapBuilder {

    private HttpDownInfo httpDownInfo;
    private int retryCount;
    private HttpDownCallback callback;

    public HttpDownBootstrapBuilder httpDownInfo(HttpDownInfo httpDownInfo) {
      this.httpDownInfo = httpDownInfo;
      return this;
    }

    public HttpDownBootstrapBuilder retryCount(int retryCount) {
      this.retryCount = retryCount;
      return this;
    }

    public HttpDownBootstrapBuilder callback(HttpDownCallback callback) {
      this.callback = callback;
      return this;
    }

    public HttpDownBootstrap build() {
      HttpDownBootstrap bootstrap = new HttpDownBootstrap();
      bootstrap.setHttpDownInfo(httpDownInfo);
      bootstrap.setRetryCount(retryCount <= 0 ? 5 : retryCount);
      bootstrap.setCallback(callback);
      TimeoutCheckTask.getInstance().addBoot(bootstrap);
      return bootstrap;
    }
  }
}
