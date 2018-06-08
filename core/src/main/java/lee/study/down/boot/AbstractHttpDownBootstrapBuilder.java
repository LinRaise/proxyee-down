package lee.study.down.boot;

import io.netty.channel.nio.NioEventLoopGroup;
import lee.study.down.dispatch.HttpDownCallback;
import lee.study.down.exception.BootstrapBuildException;
import lee.study.down.model.HttpDownInfo;

/**
 * 用于创建一个HTTP下载器
 */
public abstract class AbstractHttpDownBootstrapBuilder {

  private int timeout;
  private int retryCount;
  private boolean autoRename;
  private long speedLimit;

  private NioEventLoopGroup loopGroup;
  private HttpDownCallback callback;

  /**
   * 线程池设置 默认值：new NioEventLoopGroup(1)
   */
  public <T extends AbstractHttpDownBootstrapBuilder> T timeout(int timeout) {
    this.timeout = timeout;
    return (T) this;
  }

  /**
   * 线程池设置 默认值：new NioEventLoopGroup(1)
   */
  public <T extends AbstractHttpDownBootstrapBuilder> T retryCount(int retryCount) {
    this.retryCount = retryCount;
    return (T) this;
  }

  /**
   * 线程池设置 默认值：new NioEventLoopGroup(1)
   */
  public <T extends AbstractHttpDownBootstrapBuilder> T autoRename(boolean autoRename) {
    this.autoRename = autoRename;
    return (T) this;
  }

  /**
   * 线程池设置 默认值：new NioEventLoopGroup(1)
   */
  public <T extends AbstractHttpDownBootstrapBuilder> T speedLimit(long speedLimit) {
    this.speedLimit = speedLimit;
    return (T) this;
  }

  /**
   * 线程池设置 默认值：new NioEventLoopGroup(1)
   */
  public <T extends AbstractHttpDownBootstrapBuilder> T loopGroup(NioEventLoopGroup loopGroup) {
    this.loopGroup = loopGroup;
    return (T) this;
  }

  /**
   * 下载回调类
   */
  public <T extends AbstractHttpDownBootstrapBuilder> T callback(HttpDownCallback callback) {
    this.callback = callback;
    return (T) this;
  }

  protected NioEventLoopGroup getLoopGroup() {
    return loopGroup;
  }

  protected HttpDownCallback getCallback() {
    return callback;
  }

  protected abstract HttpDownInfo getHttpDownInfo() throws Exception;

  public HttpDownBootstrap build() {
    try {
      HttpDownBootstrap bootstrap = new HttpDownBootstrap();
      if (loopGroup == null) {
        loopGroup = new NioEventLoopGroup(1);
      }
      HttpDownInfo httpDownInfo = getHttpDownInfo();
      httpDownInfo.setTimeout(timeout <= 0 ? 30 : timeout);
      httpDownInfo.setRetryCount(retryCount <= 0 ? 5 : timeout);
      httpDownInfo.setAutoRename(autoRename);
      httpDownInfo.setSpeedLimit(speedLimit);
      bootstrap.setHttpDownInfo(httpDownInfo);
      bootstrap.setLoopGroup(loopGroup);
      bootstrap.setCallback(callback);
      return bootstrap;
    } catch (Exception e) {
      throw new BootstrapBuildException("build HttpDownBootstrap error", e);
    }
  }
}
