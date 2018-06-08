package lee.study.down.boot;

import io.netty.channel.nio.NioEventLoopGroup;
import java.util.Map;
import lee.study.down.dispatch.HttpDownCallback;
import lee.study.down.model.HttpDownInfo;
import lee.study.down.model.HttpRequestInfo;
import lee.study.down.model.TaskInfo;
import lee.study.down.util.HttpDownUtil;

public class HttpDownBootstrapBuilderAdapter<T extends AbstractHttpDownBootstrapBuilder> extends AbstractHttpDownBootstrapBuilder {

  @Override
  public T timeout(int timeout) {
    return super.timeout(timeout);
  }

  @Override
  public T retryCount(int retryCount) {
    return super.retryCount(retryCount);
  }

  @Override
  public T autoRename(boolean autoRename) {
    return super.autoRename(autoRename);
  }

  @Override
  public T speedLimit(long speedLimit) {
    return super.speedLimit(speedLimit);
  }

  @Override
  public T loopGroup(NioEventLoopGroup loopGroup) {
    return super.loopGroup(loopGroup);
  }

  @Override
  public T callback(HttpDownCallback callback) {
    return super.callback(callback);
  }

  @Override
  protected HttpDownInfo getHttpDownInfo() throws Exception {
    return null;
  }
}
