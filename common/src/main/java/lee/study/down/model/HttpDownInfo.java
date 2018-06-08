package lee.study.down.model;

import io.netty.handler.codec.http.HttpRequest;
import lee.study.proxyee.proxy.ProxyConfig;
import lombok.Data;

@Data
public class HttpDownInfo {

  private int timeout;
  private int retryCount;
  private boolean autoRename;
  private long speedLimit;

  private TaskInfo taskInfo;
  private HttpRequest request;
  private ProxyConfig proxyConfig;

  public HttpDownInfo(TaskInfo taskInfo, HttpRequest request) {
    this(taskInfo, request, null);
  }

  public HttpDownInfo(TaskInfo taskInfo, HttpRequest request, ProxyConfig proxyConfig) {
    this.taskInfo = taskInfo;
    this.request = request;
    this.proxyConfig = proxyConfig;
  }
}
