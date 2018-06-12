package lee.study.down.model;

import io.netty.handler.codec.http.HttpRequest;
import java.io.Serializable;
import lee.study.proxyee.proxy.ProxyConfig;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class HttpDownInfo implements Serializable {

  private static final long serialVersionUID = 231649750985691346L;
  private String id;
  private String filePath;
  private String fileName;
  private int connections;
  private long totalSize;
  private boolean supportRange;
  private int timeout;
  private int retryCount;
  private boolean autoRename;
  private long speedLimit;

  private HttpRequest request;
  private ProxyConfig proxyConfig;

  public HttpDownInfo(HttpRequest request) {
    this(request, null);
  }

  public HttpDownInfo(HttpRequest request, ProxyConfig proxyConfig) {
    this.request = request;
    this.proxyConfig = proxyConfig;
  }
}
