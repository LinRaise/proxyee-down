package lee.study.down.boot;

import lee.study.down.exception.BootstrapBuildException;
import lee.study.down.model.HttpDownInfo;

public class DefaultHttpDownBootstrapBuilder extends AbstractHttpDownBootstrapBuilder {

  private HttpDownInfo httpDownInfo;

  public DefaultHttpDownBootstrapBuilder httpDownInfo(HttpDownInfo httpDownInfo) {
    this.httpDownInfo = httpDownInfo;
    return this;
  }

  @Override
  protected HttpDownInfo getHttpDownInfo() throws BootstrapBuildException {
    return httpDownInfo;
  }
}
