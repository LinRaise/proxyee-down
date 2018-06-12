package lee.study.down.boot;

import io.netty.channel.nio.NioEventLoopGroup;
import java.util.Map;
import lee.study.down.dispatch.HttpDownCallback;
import lee.study.down.model.HttpDownInfo;
import lee.study.down.model.HttpRequestInfo;
import lee.study.down.model.TaskInfo;
import lee.study.down.util.HttpDownUtil;

/**
 * 通过URL创建一个下载任务
 */
public class URLHttpDownBootstrapBuilder extends HttpDownBootstrapBuilderAdapter<URLHttpDownBootstrapBuilder> {

  private String url;
  private Map<String, String> heads;
  private String body;
  private String path;
  private int connections;

  public URLHttpDownBootstrapBuilder url(String url) {
    this.url = url;
    return this;
  }

  public URLHttpDownBootstrapBuilder heads(Map<String, String> heads) {
    this.heads = heads;
    return this;
  }

  public URLHttpDownBootstrapBuilder body(String body) {
    this.body = body;
    return this;
  }

  public URLHttpDownBootstrapBuilder path(String path) {
    this.path = path;
    return this;
  }

  public URLHttpDownBootstrapBuilder connections(int connections) {
    this.connections = connections;
    return this;
  }


  @Override
  protected HttpDownInfo getHttpDownInfo() throws Exception {
    HttpRequestInfo requestInfo = HttpDownUtil.buildGetRequest(url, heads, body);
    TaskInfo taskInfo = HttpDownUtil.getTaskInfo(requestInfo, null, null, getLoopGroup());
    taskInfo.setFilePath(path);
    taskInfo.setConnections(taskInfo.isSupportRange() ? (connections <= 0 ? 32 : connections) : 1);
    return new HttpDownInfo(taskInfo, requestInfo);
  }

  @Override
  public HttpDownBootstrap build() {
    autoRename(true).loopGroup(new NioEventLoopGroup(1));
    return super.build();
  }
}
