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
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.ReferenceCountUtil;
import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
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
      ch.pipeline().addLast(SslContextBuilder.forClient()
          .trustManager(InsecureTrustManagerFactory.INSTANCE)
          .build()
          .newHandler(ch.alloc(),
              requestProto.getHost(),
              requestProto.getPort()));
    }
    ch.pipeline().addLast("httpCodec", new HttpClientCodec());
    ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {

      private TaskInfo taskInfo = bootstrap.getHttpDownInfo().getTaskInfo();
      private ChunkInfo chunkInfo = taskInfo.getChunkInfoList().get(connectInfo.getChunkIndex());
      private SeekableByteChannel fileChannel;
      private HttpDownCallback callback = bootstrap.getCallback();
      private long realContentSize;
      private boolean isSucc;

      @Override
      public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        try {
          if (msg instanceof HttpContent) {
            if (!isSucc) {
              return;
            }
            HttpContent httpContent = (HttpContent) msg;
            ByteBuf byteBuf = httpContent.content();
            int size = byteBuf.readableBytes();
            fileChannel.write(byteBuf.nioBuffer());
            chunkInfo.setDownSize(chunkInfo.getDownSize() + size);
            connectInfo.setDownSize(connectInfo.getDownSize() + size);
            //下载完成
            if (connectInfo.getDownSize() >= connectInfo.getTotalSize()) {
              //判断是否要去支持其他分段
            }
            if (chunkInfo.getDownSize() == chunkInfo.getTotalSize()
                || (!taskInfo.isSupportRange() && msg instanceof LastHttpContent)) {
              LOGGER.debug("分段下载完成：channelId[" + ctx.channel().id() + "]\t" + chunkInfo);
              bootstrap.close(connectInfo);
              //分段下载完成回调
              chunkInfo.setStatus(HttpDownStatus.DONE);
              taskInfo.refresh(chunkInfo);
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
                taskInfo.setStatus(HttpDownStatus.DONE);
                LOGGER.debug("下载完成：channelId[" + ctx.channel().id() + "]\t" + chunkInfo);
                bootstrap.close();
                if (callback != null) {
                  callback.onDone(bootstrap.getHttpDownInfo());
                }
              }
            }
          } else {
            HttpResponse httpResponse = (HttpResponse) msg;
            Integer responseCode = httpResponse.status().code();
            if (responseCode < 200 || responseCode >= 300) {
              LOGGER.warn("响应状态码异常：" + responseCode + "\t" + connectInfo);
              if (responseCode >= 400 && responseCode < 500) {
                chunkInfo.setStatus(HttpDownStatus.ERROR_WAIT_CONNECT);
              }
              safeClose(ctx.channel());
              return;
            }
            realContentSize = HttpDownUtil.getDownContentSize(httpResponse.headers());
            LOGGER.debug("下载响应：channelId[" + ctx.channel().id() + "]\t contentSize[" + realContentSize + "]" + connectInfo);
            fileChannel = Files.newByteChannel(Paths.get(taskInfo.buildTaskFilePath()), StandardOpenOption.WRITE);
            isSucc = true;
          }
        } catch (Exception e) {
          throw e;
        } finally {
          ReferenceCountUtil.release(msg);
        }
      }

      @Override
      public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        LOGGER.error("down onChunkError:", cause);
        chunkInfo.setErrorCount(chunkInfo.getErrorCount() + 1);
        safeClose(ctx.channel());
        if (callback != null) {
          callback.onChunkError(bootstrap.getHttpDownInfo(), chunkInfo, cause);
        }
      }

      @Override
      public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
        super.channelUnregistered(ctx);
        safeClose(ctx.channel());
      }

      private void safeClose(Channel channel) {
        try {
          HttpDownUtil.safeClose(channel, fileChannel);
        } catch (IOException e) {
          LOGGER.error("safeClose fail:", e);
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
