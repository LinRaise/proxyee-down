package lee.study.down.handle;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import lee.study.down.boot.HttpDownBootstrap;
import lee.study.down.constant.HttpDownStatus;
import lee.study.down.dispatch.HttpDownCallback;
import lee.study.down.model.ChunkInfo;
import lee.study.down.model.ConnectInfo;
import lee.study.down.model.HttpRequestInfo;
import lee.study.down.model.TaskInfo;
import lee.study.down.util.HttpDownUtil;
import lee.study.proxyee.proxy.ProxyHandleFactory;
import lee.study.proxyee.util.ProtoUtil.RequestProto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpDownInitializer extends ChannelInitializer {

  private static final Logger LOGGER = LoggerFactory.getLogger(HttpDownInitializer.class);
  private static final long SIZE_1MB = 1024 * 1024L;
  private static final double TIME_1S_NANO = (double) TimeUnit.SECONDS.toNanos(1);

  private boolean isSsl;
  private HttpDownBootstrap bootstrap;
  private ConnectInfo connectInfo;

  public HttpDownInitializer(boolean isSsl, HttpDownBootstrap bootstrap,
      ConnectInfo connectInfo) {
    this.isSsl = isSsl;
    this.bootstrap = bootstrap;
    this.connectInfo = connectInfo;
  }

  @Override
  protected void initChannel(Channel ch) throws Exception {
    if (bootstrap.getHttpDownInfo().getProxyConfig() != null) {
      ch.pipeline().addLast(ProxyHandleFactory.build(bootstrap.getHttpDownInfo().getProxyConfig()));
    }
    if (isSsl) {
      RequestProto requestProto = ((HttpRequestInfo) bootstrap.getHttpDownInfo().getRequest()).requestProto();
      ch.pipeline().addLast(HttpDownUtil.getSslContext().newHandler(ch.alloc(), requestProto.getHost(), requestProto.getPort()));
    }
    ch.pipeline().addLast("downTimeout", new DownTimeoutHandler(bootstrap.getHttpDownInfo().getTimeout()));
    ch.pipeline().addLast("httpCodec", new HttpClientCodec());
    ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {

      private TaskInfo taskInfo = bootstrap.getHttpDownInfo().getTaskInfo();
      private ChunkInfo chunkInfo = taskInfo.getChunkInfoList().get(connectInfo.getChunkIndex());
      private SeekableByteChannel fileChannel;
      private HttpDownCallback callback = bootstrap.getCallback();
      private boolean normalClose;
      private boolean isSuccess;

      @Override
      public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        try {
          if (msg instanceof HttpContent) {
            synchronized (taskInfo) {
              if (!isSuccess || !fileChannel.isOpen() || !ctx.channel().isOpen()) {
                return;
              }
              HttpContent httpContent = (HttpContent) msg;
              ByteBuf byteBuf = httpContent.content();
              int size = byteBuf.readableBytes();
              //判断是否超出下载字节范围
              if (taskInfo.isSupportRange() && connectInfo.getDownSize() + size > connectInfo.getTotalSize()) {
                size = (int) (connectInfo.getTotalSize() - connectInfo.getDownSize());
              }
              fileChannel.write(byteBuf.nioBuffer());
              synchronized (chunkInfo) {
                chunkInfo.setDownSize(chunkInfo.getDownSize() + size);
                connectInfo.setDownSize(connectInfo.getDownSize() + size);
              }
              if (bootstrap.getHttpDownInfo().getSpeedLimit() > 0) {
                long time = System.nanoTime();
                if (taskInfo.getLastStartTime() <= 0) {
                  taskInfo.setLastStartTime(time);
                } else {
                  //计算平均速度是否超过速度限制
                  long useTime = time - taskInfo.getLastStartTime();
                  double speed = taskInfo.getDownSize() / (double) useTime;
                  double limitSpeed = bootstrap.getHttpDownInfo().getSpeedLimit() / TIME_1S_NANO;
                  long sleepTime = (long) ((speed - limitSpeed) * useTime);
                  if (sleepTime > 0) {
                    TimeUnit.NANOSECONDS.sleep(sleepTime);
                    //刷新其他连接的lastReadTime
                    for (ConnectInfo ci : taskInfo.getConnectInfoList()) {
                      if (ci.getConnectChannel() != null && ci.getConnectChannel() != ch) {
                        DownTimeoutHandler downTimeout = (DownTimeoutHandler) ci.getConnectChannel().pipeline().get("downTimeout");
                        if (downTimeout != null) {
                          downTimeout.updateLastReadTime(sleepTime);
                        }
                      }
                    }
                  }
                }
              }
              if (!taskInfo.isSupportRange() && !(httpContent instanceof LastHttpContent)) {
                return;
              }
              long time = System.currentTimeMillis();
              if (connectInfo.getDownSize() >= connectInfo.getTotalSize()) {
                LOGGER.debug("连接下载完成：" + connectInfo + "\t" + chunkInfo);
                normalClose = true;
                bootstrap.close(connectInfo);
                //判断分段是否下载完成
                if (isChunkDownDone(httpContent)) {
                  LOGGER.debug("分段下载完成：" + connectInfo + "\t" + chunkInfo);
                  synchronized (chunkInfo) {
                    chunkInfo.setStatus(HttpDownStatus.DONE);
                    //计算最后平均下载速度
                    chunkInfo.setSpeed(calcSpeed(chunkInfo.getTotalSize(), time - taskInfo.getStartTime() - chunkInfo.getPauseTime()));
                  }
                  //分段下载完成回调
                  if (callback != null) {
                    callback.onChunkDone(bootstrap.getHttpDownInfo(), chunkInfo);
                  }
                  //所有分段都下载完成
                  if (taskInfo.getChunkInfoList().stream().allMatch((chunk) -> chunk.getStatus() == HttpDownStatus.DONE)) {
                    if (!taskInfo.isSupportRange()) {  //chunked编码最后更新文件大小
                      taskInfo.setTotalSize(taskInfo.getDownSize());
                      taskInfo.getChunkInfoList().get(0).setTotalSize(taskInfo.getDownSize());
                    }
                    //文件下载完成回调
                    LOGGER.debug("任务下载完成：" + chunkInfo);
                    connectInfo.setStatus(HttpDownStatus.DONE);
                    taskInfo.setStatus(HttpDownStatus.DONE);
                    //计算平均速度
                    taskInfo.setSpeed(calcSpeed(taskInfo.getTotalSize(), System.currentTimeMillis() - taskInfo.getStartTime()));
                    bootstrap.done();
                    return;
                  }
                }
                //判断是否要去支持其他分段,找一个下载最慢的分段
                ChunkInfo supportChunk = taskInfo.getChunkInfoList()
                    .stream()
                    .filter(chunk -> chunk.getIndex() != connectInfo.getChunkIndex() && chunk.getStatus() != HttpDownStatus.DONE)
                    .min(Comparator.comparingLong(ChunkInfo::getDownSize))
                    .orElse(null);
                if (supportChunk == null) {
                  connectInfo.setStatus(HttpDownStatus.DONE);
                  return;
                }
                ConnectInfo maxConnect = taskInfo.getConnectInfoList()
                    .stream()
                    .filter(connect -> connect.getChunkIndex() == supportChunk.getIndex() && connect.getTotalSize() - connect.getDownSize() >= SIZE_1MB)
                    .max((c1, c2) -> (int) (c1.getStartPosition() - c2.getStartPosition()))
                    .orElse(null);
                if (maxConnect == null) {
                  connectInfo.setStatus(HttpDownStatus.DONE);
                  return;
                }
                //把这个分段最后一个下载连接分成两个
                long remainingSize = maxConnect.getTotalSize() - maxConnect.getDownSize();
                long endTemp = maxConnect.getEndPosition();
                maxConnect.setEndPosition(maxConnect.getEndPosition() - (remainingSize / 2));
                //给当前连接重新分配下载区间
                connectInfo.setStartPosition(maxConnect.getEndPosition() + 1);
                connectInfo.setEndPosition(endTemp);
                connectInfo.setChunkIndex(supportChunk.getIndex());
                LOGGER.debug("支持下载：" + connectInfo + "\t" + chunkInfo);
                bootstrap.reConnect(connectInfo, true);
              }
            }
          } else {
            HttpResponse httpResponse = (HttpResponse) msg;
            Integer responseCode = httpResponse.status().code();
            if (responseCode < 200 || responseCode >= 300) {
              LOGGER.warn("响应状态码异常：" + responseCode + "\t" + connectInfo);
              if (responseCode >= 500) {
                connectInfo.setErrorCount(connectInfo.getErrorCount() + 1);
              }
              normalClose = true;
              bootstrap.close(connectInfo);
              ch.eventLoop().schedule(() -> bootstrap.reConnect(connectInfo), 5, TimeUnit.SECONDS);
              return;
            }
            LOGGER.debug("下载响应：" + connectInfo);
            fileChannel = Files.newByteChannel(Paths.get(HttpDownUtil.getTaskFilePath(taskInfo)), StandardOpenOption.WRITE);
            if (taskInfo.isSupportRange()) {
              fileChannel.position(connectInfo.getStartPosition() + connectInfo.getDownSize());
            }
            connectInfo.setFileChannel(fileChannel);
            isSuccess = true;
          }
        } catch (Exception e) {
          throw e;
        } finally {
          ReferenceCountUtil.release(msg);
        }
      }

      private boolean isChunkDownDone(HttpContent httpContent) {
        if (taskInfo.isSupportRange()) {
          if (chunkInfo.getDownSize() >= chunkInfo.getTotalSize()) {
            return true;
          }
        } else if (httpContent instanceof LastHttpContent) {
          return true;
        }
        return false;
      }

      /**
       * 计算下载速度(B/S)
       * @param downSize  下载的字节数
       * @param downTime  下载耗时
       * @return
       */
      private long calcSpeed(long downSize, long downTime) {
        return (long) (downSize / (downTime / 1000D));
      }

      @Override
      public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        LOGGER.error("down onChunkError:", cause);
        normalClose = true;
        bootstrap.close(connectInfo);
        if (callback != null) {
          callback.onChunkError(bootstrap.getHttpDownInfo(), chunkInfo, cause);
        }
        bootstrap.reConnect(connectInfo);
      }

      @Override
      public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
        super.channelUnregistered(ctx);
        //还未下载完成，继续下载
        if (!normalClose && chunkInfo.getStatus() != HttpDownStatus.PAUSE) {
          LOGGER.debug("channelUnregistered:" + connectInfo + "\t" + chunkInfo);
          bootstrap.close(connectInfo);
          bootstrap.reConnect(connectInfo);
        }
      }
    });
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
    super.exceptionCaught(ctx, cause);
    LOGGER.error("down onInit:", cause);
  }
}
